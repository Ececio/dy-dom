package io.github.fplus.core.hook.logic

import com.ss.android.ugc.aweme.feed.model.Aweme
import io.github.xpler.core.XplerLog
import io.github.xpler.core.lparam
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.lang.reflect.Method
import kotlin.coroutines.Continuation

/** 用户作品列表的一页 */
data class UserPostPage(
    val awemes: List<Aweme>,
    val hasMore: Boolean,
    val maxCursor: Long,
    val error: String? = null,
)

/**
 * 用户作品列表接口（`/aweme/v1/aweme/post/`）。
 *
 * 为什么不能自己拼 HTTP：作品列表必须带 App 内签名（X-Gorgon / X-Bogus），裸请求只会拿到空列表。
 * 所以这里复用抖音自己的 Retrofit 栈：
 *
 *  `RetrofitFactory.createIRetrofitFactorybyMonsterPlugin(false)` -> `create(host)` -> `create(接口)`
 *
 * 这样公共参数、签名、Cookie 全部由 App 拦截器补全。
 *
 * 31.5.0 里 `PostAwemeRepo$TabApi.fetchPublicList` 已经基本没人调用（页面列表已改成
 * `/aweme/v1/aweme/weak/post/`），直接调它容易拿到空列表。所以这里准备了多个“App 自己在用”的
 * 接口按顺序尝试，谁先返回作品就用谁：
 *
 *  1. `IFeedLiveShareProfileApi#getPublishAwemeList`：精确参数 `user_id/max_cursor/count`，返回 Single
 *  2. `CommonNetApi#getFeedItemList`：`count/user_id/sec_user_id/source/page_scene/...`，返回 Observable
 *  3. `PostAwemeRepo$TabApi#fetchPublicListInCoroutine`：`@QueryMap` 版本（suspend）
 *  4. `PostAwemeRepo$TabApi#fetchPublicList`：`@MaxLength int + @QueryMap`
 *  5. 都失败时再探测 weak post，把结果写进日志，便于下一版适配
 */
object UserPostApi {
    private const val TAG = "UserPostApi"

    /** 等宿主 Deferred 的最长时间 */
    private const val DEFERRED_TIMEOUT_MS = 20_000L

    /** 一个候选接口：类名 + 方法名 + 参数取值规则 */
    private class Spec(
        val label: String,
        val className: String,
        val methodName: String,
        /** 是否支持翻页（有 cursor 参数）；不支持时只用于第一页 */
        val paging: Boolean,
        val values: (secUid: String?, uid: String?, cursor: Long, count: Int) -> Map<String, Any?>,
    )

    private val SPECS = listOf(
        // 精确参数版本，最可靠：返回 Single<FeedItemList>
        Spec(
            label = "liveShare.getPublishAwemeList",
            className = "com.ss.android.ugc.aweme.feedliveshare.screen.api.IFeedLiveShareProfileApi",
            methodName = "getPublishAwemeList",
            paging = true,
        ) { secUid, uid, cursor, count ->
            linkedMapOf(
                "user_id" to uid.orEmpty(),
                "max_cursor" to cursor,
                "count" to count,
                "together_room_id" to null,
                "room_provider" to 0,
            )
        },

        // IM 里展示用户作品用的接口，同样是 /aweme/v1/aweme/post/
        Spec(
            label = "im.getFeedItemList",
            className = "com.ss.android.ugc.aweme.im.compatible.CommonNetApi",
            methodName = "getFeedItemList",
            paging = false,
        ) { secUid, uid, cursor, count ->
            linkedMapOf(
                "count" to count,
                "user_id" to uid.orEmpty(),
                "sec_user_id" to secUid.orEmpty(),
                "source" to 0,
                "page_scene" to 0,
                "publish_video_strategy_type" to 0,
            )
        },

        // 主页 Tab 的老接口，保留兜底
        Spec(
            label = "tab.fetchPublicListInCoroutine",
            className = "com.ss.android.ugc.aweme.profile.tab.api.PostAwemeRepo\$TabApi",
            methodName = "fetchPublicListInCoroutine",
            paging = true,
        ) { secUid, uid, cursor, count -> postParams(secUid, uid, cursor, count) },

        Spec(
            label = "tab.fetchPublicList",
            className = "com.ss.android.ugc.aweme.profile.tab.api.PostAwemeRepo\$TabApi",
            methodName = "fetchPublicList",
            paging = true,
        ) { secUid, uid, cursor, count -> postParams(secUid, uid, cursor, count) },
    )

    private val WEAK_CLASS = "com.ss.android.ugc.aweme.profile.tab.api.PostAwemeRepo\$TabApi"
    private const val WEAK_METHOD = "fetchWeakPostList"

    /** 主页作品列表真正的入口：`PostAwemeRepo.LIZ(参数对象, itemListId, JsonObject)` */
    private const val WEAK_REPO_CLASS = "com.ss.android.ugc.aweme.profile.tab.api.PostAwemeRepo"

    /** 按 ID 取完整作品的接口，都是 `/aweme/v1/multi/aweme/detail/` */
    private val DETAIL_SPECS = listOf(
        "com.ss.android.ugc.aweme.sticker.flash.detail.FlashMobDetailApi" to "getAwemeByIds",
        "com.ss.android.ugc.aweme.history.HistoryModel\$API" to "getAwemeDetail",
    )

    /** 拿不到 App 真实域名时的兜底，按顺序尝试 */
    private val FALLBACK_HOSTS = listOf(
        "https://aweme.snssdk.com",
        "https://api.amemv.com",
        "https://api3-normal-c-lf.amemv.com",
    )

    private class Endpoint(
        val spec: Spec,
        val api: Any,
        val method: Method,
        val host: String,
    ) {
        val label: String get() = "${spec.label}@${host.removePrefix("https://")}"
    }

    @Volatile
    private var endpoints: List<Endpoint>? = null

    @Volatile
    private var lastError: String? = null

    /** 上一次成功的接口，下次优先用，避免每页都重新试错 */
    @Volatile
    private var preferred: Endpoint? = null

    /** 最近一次解析失败的原因，用于弹窗提示 */
    fun lastError(): String? = lastError

    fun isAvailable(): Boolean = resolveEndpoints().isNotEmpty()

    /**
     * 拉取一页作品
     *
     * @param secUid 作者 sec_uid
     * @param uid    作者 uid（数字 ID，部分接口只认这个）
     * @param cursor 游标，第一页传 0
     * @param count  每页数量
     */
    suspend fun fetchPage(
        secUid: String?,
        uid: String?,
        cursor: Long,
        count: Int,
    ): UserPostPage {
        val all = resolveEndpoints()
        if (all.isEmpty()) {
            return UserPostPage(
                emptyList(), false, 0L,
                "未找到作品列表接口" + lastError?.let { ": $it" }.orEmpty(),
            )
        }

        // 上次成功的接口优先，其余按顺序兜底
        val ordered = ArrayList<Endpoint>(all.size)
        val best = preferred
        // 翻页时只能用带 cursor 的接口，否则会一直返回第一页
        val usable = if (cursor == 0L) all else all.filter { it.spec.paging }
        if (usable.isEmpty()) return UserPostPage(emptyList(), false, 0L, null)
        if (best != null && usable.any { it === best }) ordered.add(best)
        ordered.addAll(usable.filter { it !== best })

        return if (cursor == 0L) {
            fetchFirstPageMerged(ordered, secUid, uid, count)
        } else {
            fetchNextPage(ordered, secUid, uid, cursor, count)
        }
    }

    /**
     * 第一页会把所有可用接口都问一遍再合并。
     *
     * 原因：有的接口会过滤掉图文作品（实测 `getPublishAwemeList` 只要视频，作者 3 个作品只返回 2 个），
     * 只取第一个非空结果就会漏作品。合并后顺便记住哪个接口给的最多，后续翻页就用它。
     */
    private suspend fun fetchFirstPageMerged(
        ordered: List<Endpoint>,
        secUid: String?,
        uid: String?,
        count: Int,
    ): UserPostPage {
        val collected = LinkedHashMap<String, Aweme>()
        var bestEndpoint: Endpoint? = null
        var bestCount = 0
        var bestHasMore = false
        var bestCursor = 0L
        var lastError: String? = null
        val counts = ArrayList<String>(ordered.size)

        for (endpoint in ordered) {
            val values = endpoint.spec.values(secUid, uid, 0L, count)
            BatchDebug.log("[${endpoint.label}] cursor=0 count=$count params=$values")
            val page = try {
                val raw = invoke(endpoint, values, 0L, count)
                BatchDebug.log("[${endpoint.label}] response=${describe(raw)}")
                parsePage(raw)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                val cause = e.cause ?: e
                BatchDebug.log("[${endpoint.label}] 调用失败: ${cause.javaClass.name}: ${cause.message}")
                XplerLog.e("$TAG 调用失败(${endpoint.label}): ${cause.javaClass.name}: ${cause.message}")
                lastError = "请求失败: ${cause.javaClass.simpleName} ${cause.message.orEmpty()}".trim()
                continue
            }

            counts.add("${endpoint.label}=${page.awemes.size}")
            if (page.awemes.isEmpty()) {
                if (page.error != null) lastError = page.error
                continue
            }

            page.awemes.forEachIndexed { index, aweme ->
                val id = aweme.aid.orEmpty()
                val key = if (id.isNotBlank()) id else "${endpoint.label}#$index"
                collected.putIfAbsent(key, aweme)
            }
            if (page.awemes.size > bestCount) {
                bestCount = page.awemes.size
                bestEndpoint = endpoint
                bestHasMore = page.hasMore
                bestCursor = page.maxCursor
            }
        }

        BatchDebug.log("第一页各接口作品数: " + counts.joinToString())
        val best = bestEndpoint
        if (best != null && best.spec.paging) preferred = best
        if (best != null && collected.size != bestCount) {
            BatchDebug.log("合并后 ${collected.size} 个作品（单接口最多 $bestCount 个）")
        }

        // 跟主页实际在用的 weak post 列表对账，补回被某个接口过滤掉的作品（实测图文会被过滤）
        val weakIds = fetchWeakPostIds(secUid, uid, count)
        if (weakIds.isNotEmpty()) {
            BatchDebug.log("weak post ID: " + weakIds.joinToString(", ") { it.takeLast(8) })
            val missing = weakIds.filter { it !in collected }
            BatchDebug.log("weak post 对账: 列表=${weakIds.size}, 已拿到=${collected.size}, 缺少=${missing.size}")
            if (missing.isNotEmpty()) {
                fetchAwemesByIds(missing).forEach { aweme ->
                    val id = aweme.aid.orEmpty()
                    if (id.isNotBlank()) collected[id] = aweme
                }
            }
        }

        // 有 weak post 时按它的顺序输出，保证和主页一致
        val merged = if (weakIds.isNotEmpty()) {
            val weakSet = weakIds.toHashSet()
            buildList(collected.size) {
                weakIds.forEach { id -> collected[id]?.let { add(it) } }
                collected.forEach { (key, value) -> if (key !in weakSet) add(value) }
            }
        } else {
            collected.values.toList()
        }

        if (merged.isNotEmpty()) {
            BatchDebug.log(
                "第一页作品: " + merged.joinToString(", ") { item ->
                    val kind = if (item.getImageUrlList().isNotEmpty()) "图文" else "视频"
                    "${item.aid.orEmpty().takeLast(8)}$kind"
                }
            )
            return UserPostPage(
                awemes = merged,
                hasMore = best?.let { bestHasMore } ?: false,
                maxCursor = best?.let { bestCursor } ?: 0L,
            )
        }

        return UserPostPage(emptyList(), false, 0L, lastError ?: "接口返回为空")
    }

    private suspend fun fetchNextPage(
        ordered: List<Endpoint>,
        secUid: String?,
        uid: String?,
        cursor: Long,
        count: Int,
    ): UserPostPage {
        var lastError: String? = null
        for (endpoint in ordered) {
            val values = endpoint.spec.values(secUid, uid, cursor, count)
            BatchDebug.log("[${endpoint.label}] cursor=$cursor count=$count params=$values")
            val page = try {
                val raw = invoke(endpoint, values, cursor, count)
                BatchDebug.log("[${endpoint.label}] response=${describe(raw)}")
                parsePage(raw)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                val cause = e.cause ?: e
                BatchDebug.log("[${endpoint.label}] 调用失败: ${cause.javaClass.name}: ${cause.message}")
                lastError = "请求失败: ${cause.javaClass.simpleName} ${cause.message.orEmpty()}".trim()
                continue
            }

            if (page.awemes.isNotEmpty()) {
                if (endpoint.spec.paging) preferred = endpoint
                return page
            }
            // 翻页后为空说明已到底
            BatchDebug.log("[${endpoint.label}] 作品为空${page.error?.let { " ($it)" }.orEmpty()}")
            return page
        }
        return UserPostPage(emptyList(), false, 0L, lastError)
    }

    /** 主页老接口的参数，按 App 里 `PostAwemeRepo` 的写法拼 */
    private fun postParams(
        secUid: String?,
        uid: String?,
        cursor: Long,
        count: Int,
    ): Map<String, Any?> {
        val params = linkedMapOf<String, Any?>()
        params["max_cursor"] = cursor.toString()
        // 两个 ID 都带上：老接口有时只认 user_id，有时只认 sec_user_id
        if (!secUid.isNullOrBlank()) params["sec_user_id"] = secUid
        if (!uid.isNullOrBlank()) params["user_id"] = uid
        params["count"] = count.toString()
        params["publish_video_strategy_type"] = "0"
        params["source"] = "0"
        params["user_avatar_shrink"] = ""
        params["video_cover_shrink"] = ""
        params["show_live_replay_strategy"] = "0"
        params["is_order_flow"] = "0"
        params["page_from"] = "2"
        params["location_permission"] = "0"
        params["familiar_collects"] = "0"
        params["item_list_id"] = ""
        params["collection_item_list_id"] = "{}"
        return params
    }

    /**
     * weak post（`/aweme/v1/aweme/weak/post/`）的参数。
     *
     * 注意游标字段名是 `cursor`，不是 `max_cursor`；老的 `/aweme/v1/aweme/post/` 用 `max_cursor`。
     * 传错会被服务端直接拒绝（status=5 参数不合法）。
     */
    private fun weakParams(
        secUid: String?,
        uid: String?,
        cursor: Long,
        count: Int,
    ): Map<String, Any?> {
        val params = linkedMapOf<String, Any?>()
        params["cursor"] = cursor.toString()
        if (!secUid.isNullOrBlank()) params["sec_user_id"] = secUid
        else if (!uid.isNullOrBlank()) params["user_id"] = uid
        params["count"] = count.toString()
        params["publish_video_strategy_type"] = "0"
        params["source"] = "0"
        params["user_avatar_shrink"] = ""
        params["video_cover_shrink"] = ""
        params["show_live_replay_strategy"] = "0"
        params["is_order_flow"] = "0"
        params["page_from"] = "2"
        params["location_permission"] = "0"
        params["familiar_collects"] = "0"
        params["post_serial_strategy"] = "0"
        params["item_list_id"] = ""
        params["collection_item_list_id"] = "{}"
        return params
    }

    private suspend fun invoke(
        endpoint: Endpoint,
        values: Map<String, Any?>,
        cursor: Long,
        count: Int,
    ): Any? {
        val method = endpoint.method
        method.isAccessible = true
        val hasMap = method.parameterTypes.any { Map::class.java.isAssignableFrom(it) }
        val stringMap = toStringMap(values)

        if (isSuspendMethod(method)) {
            return suspendCancellableCoroutine { continuation ->
                try {
                    val args = if (hasMap) {
                        buildMapArgs(method, stringMap, cursor, count, continuation)
                    } else {
                        buildTypedArgs(method, values, continuation)
                    }
                    method.invoke(endpoint.api, *args)
                } catch (e: Throwable) {
                    continuation.resumeWith(Result.failure(e.cause ?: e))
                }
            }
        }

        return withContext(Dispatchers.IO) {
            val args = if (hasMap) {
                buildMapArgs(method, stringMap, cursor, count, null)
            } else {
                buildTypedArgs(method, values, null)
            }
            val result = method.invoke(endpoint.api, *args) ?: return@withContext null
            awaitResult(result)
        }
    }

    /** 方法最后一个是 Continuation 参数即为 suspend 方法 */
    private fun isSuspendMethod(method: Method): Boolean {
        return method.parameterTypes.lastOrNull()?.let { isContinuation(it) } == true
    }

    /**
     * 宿主和模块各自带了一份 kotlin-stdlib，`Continuation::class.java` 和宿主的
     * `kotlin.coroutines.Continuation` 不是同一个 Class，所以只能按名字判断。
     */
    private fun isContinuation(type: Class<*>): Boolean {
        return type.name == "kotlin.coroutines.Continuation"
    }

    /**
     * 给宿主的 suspend 方法准备 Continuation。
     *
     * 直接传我们自己的 CancellableContinuation 会 `argument type mismatch`（两个 ClassLoader 的接口类不同），
     * 所以用宿主的接口类型做动态代理，把 `resumeWith(宿主 Result)` 翻译成我们自己的 Result。
     */
    private fun wrapContinuation(type: Class<*>, target: Continuation<*>): Any {
        val loader = type.classLoader ?: target.javaClass.classLoader
        val emptyContext = emptyCoroutineContext(loader)

        return java.lang.reflect.Proxy.newProxyInstance(loader, arrayOf(type)) { proxy, method, args ->
            when (method.name) {
                "getContext" -> emptyContext
                "resumeWith" -> {
                    val state = args?.firstOrNull()
                    val failure = failureOf(state)
                    @Suppress("UNCHECKED_CAST")
                    val cont = target as Continuation<Any?>
                    if (failure != null) {
                        cont.resumeWith(Result.failure(failure))
                    } else {
                        cont.resumeWith(Result.success(state))
                    }
                    null
                }
                "toString" -> "FreedomPlusContinuation"
                "hashCode" -> System.identityHashCode(target)
                "equals" -> proxy === args?.firstOrNull()
                else -> null
            }
        }
    }

    /**
     * 宿主版 `EmptyCoroutineContext`。抖音的 kotlin-stdlib 被 R8 处理过，这个类可能已经被改名/裁掉，
     * 取不到时用动态代理造一个合法的空 context（`get` 返回 null、`fold` 返回 initial）。
     */
    private fun emptyCoroutineContext(loader: ClassLoader?): Any? {
        runCatching {
            val cls = Class.forName("kotlin.coroutines.EmptyCoroutineContext", false, loader)
            cls.getField("INSTANCE").get(null)?.let { return it }
        }

        val contextType = runCatching {
            Class.forName("kotlin.coroutines.CoroutineContext", false, loader)
        }.getOrNull() ?: return null

        return java.lang.reflect.Proxy.newProxyInstance(
            loader ?: contextType.classLoader,
            arrayOf(contextType),
        ) { proxy, method, args ->
            when (method.name) {
                "get" -> null
                "minusKey", "plus" -> proxy
                "fold" -> args?.firstOrNull()
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.firstOrNull()
                "toString" -> "FreedomPlusEmptyContext"
                else -> null
            }
        }
    }

    /** 宿主的 kotlin.Result 失败状态被擦除成 `kotlin.Result$Failure`，从里面取回异常 */
    private fun failureOf(state: Any?): Throwable? {
        if (state == null) return null
        val cls = state.javaClass
        if (!cls.name.endsWith("Result\$Failure")) return null
        return runCatching {
            cls.getDeclaredField("exception").apply { isAccessible = true }.get(state) as? Throwable
        }.getOrNull()
    }

    private fun toStringMap(values: Map<String, Any?>): Map<String, String> {
        val map = LinkedHashMap<String, String>()
        values.forEach { (k, v) ->
            if (v == null) return@forEach
            map[k] = v.toString()
        }
        return map
    }

    /** `@QueryMap` 风格的接口，例如 `fetchPublicList(int, Map)` */
    private fun buildMapArgs(
        method: Method,
        params: Map<String, String>,
        cursor: Long,
        count: Int,
        continuation: Continuation<*>?,
    ): Array<Any?> {
        val types = method.parameterTypes
        return Array(types.size) { index ->
            val type = types[index]
            when {
                isContinuation(type) ->
                    continuation?.let { wrapContinuation(type, it) }
                Map::class.java.isAssignableFrom(type) -> params
                type == Int::class.javaPrimitiveType || type == Integer::class.java -> count
                type == Long::class.javaPrimitiveType || type == java.lang.Long::class.java -> cursor
                type == Boolean::class.javaPrimitiveType || type == java.lang.Boolean::class.java -> false
                type == String::class.java -> params["sec_user_id"] ?: params["user_id"].orEmpty()
                else -> null
            }
        }
    }

    /** 精确参数风格的接口，参数名从 `@Query` / `@Field` 注解里取 */
    private fun buildTypedArgs(
        method: Method,
        values: Map<String, Any?>,
        continuation: Continuation<*>?,
    ): Array<Any?> {
        val types = method.parameterTypes
        return Array(types.size) { index ->
            val type = types[index]
            if (isContinuation(type)) {
                continuation?.let { wrapContinuation(type, it) }
            } else {
                val name = paramName(method, index)
                if (name != null && values.containsKey(name)) {
                    coerce(values[name], type)
                } else {
                    defaultValue(type)
                }
            }
        }
    }

    /** 读取 `@Query("x")` / `@Field("x")` 里的参数名 */
    private fun paramName(method: Method, index: Int): String? {
        val annotations = method.parameterAnnotations.getOrNull(index).orEmpty()
        for (annotation in annotations) {
            val simpleName = annotation.annotationClass.java.simpleName
            if (simpleName != "Query" && simpleName != "Field") continue
            val value = runCatching {
                annotation.annotationClass.java.getMethod("value").invoke(annotation) as? String
            }.getOrNull()
            if (!value.isNullOrBlank()) return value
        }
        return null
    }

    private fun coerce(value: Any?, type: Class<*>): Any? {
        if (value == null) return if (type.isPrimitive) defaultValue(type) else null
        return when {
            type == String::class.java -> value.toString()
            type == Int::class.javaPrimitiveType || type == Integer::class.java ->
                (value as? Number)?.toInt() ?: value.toString().toIntOrNull() ?: 0
            type == Long::class.javaPrimitiveType || type == java.lang.Long::class.java ->
                (value as? Number)?.toLong() ?: value.toString().toLongOrNull() ?: 0L
            type == Boolean::class.javaPrimitiveType || type == java.lang.Boolean::class.java ->
                (value as? Boolean) ?: (value.toString() == "true")
            else -> value
        }
    }

    private fun defaultValue(type: Class<*>): Any? = when {
        type == String::class.java -> ""
        type == Int::class.javaPrimitiveType || type == Integer::class.java -> 0
        type == Long::class.javaPrimitiveType || type == java.lang.Long::class.java -> 0L
        type == Boolean::class.javaPrimitiveType || type == java.lang.Boolean::class.java -> false
        else -> null
    }

    /** Deferred / Single / Observable / Call / 直接返回实体 */
    private suspend fun awaitResult(result: Any): Any? {
        val cls = result.javaClass
        if (implements(cls, "kotlinx.coroutines.Deferred") || cls.name.contains("Deferred")) {
            return awaitDeferred(result)
        }

        val name = cls.name
        if (implements(cls, "io.reactivex.Single") ||
            implements(cls, "io.reactivex.Maybe")
        ) {
            return runCatching { cls.getMethod("blockingGet").invoke(result) }
                .getOrElse { throw it.cause ?: it }
        }
        if (implements(cls, "io.reactivex.Observable") ||
            implements(cls, "io.reactivex.Flowable")
        ) {
            return runCatching { cls.getMethod("blockingFirst").invoke(result) }
                .getOrElse { throw it.cause ?: it }
        }
        if (name.contains("retrofit2.Call") ||
            implements(cls, "com.bytedance.retrofit2.Call") ||
            implements(cls, "retrofit2.Call")
        ) {
            val response = cls.getMethod("execute").invoke(result) ?: return null
            return response.javaClass.getMethod("body").invoke(response)
        }
        return result
    }

    /**
     * 等待宿主的 Deferred。
     *
     * 宿主和模块各带一份 kotlinx-coroutines，`result is Deferred` 永远为 false，`await()` 也调不了，
     * 所以老老实实轮询 `isCompleted` / `getCompleted`（都在 IO 线程，不会卡 UI）。
     */
    private suspend fun awaitDeferred(deferred: Any): Any? {
        val cls = deferred.javaClass
        val isCompleted = runCatching { cls.getMethod("isCompleted") }.getOrNull()
        val getCompleted = runCatching { cls.getMethod("getCompleted") }.getOrNull()
            ?: return null

        val deadline = System.currentTimeMillis() + DEFERRED_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            val done = isCompleted?.let {
                runCatching { it.invoke(deferred) as? Boolean }.getOrNull()
            } ?: true
            if (done) {
                return runCatching { getCompleted.invoke(deferred) }
                    .getOrElse { throw it.cause ?: it }
            }
            withContext(Dispatchers.IO) { Thread.sleep(80) }
        }
        BatchDebug.log("等待 Deferred 超时(${DEFERRED_TIMEOUT_MS}ms)")
        return null
    }

    private fun implements(type: Class<*>, target: String): Boolean {
        var current: Class<*>? = type
        while (current != null) {
            if (current.name == target) return true
            if (current.interfaces.any { implements(it, target) }) return true
            current = current.superclass
        }
        return false
    }

    private fun parsePage(list: Any?): UserPostPage {
        if (list == null) return UserPostPage(emptyList(), false, 0L, "接口返回为空")

        val statusCode = (readValue(list, "statusCode", "status_code", "getStatusCode") as? Number)?.toInt() ?: 0
        val statusMsg = readValue(list, "statusMsg", "status_msg", "getStatusMsg")?.toString().orEmpty()
        val awemes = findAwemeList(list)

        if (awemes.isEmpty() && statusCode != 0) {
            return UserPostPage(emptyList(), false, 0L, "接口错误 $statusCode $statusMsg".trim())
        }

        val hasMore = when (val value = readValue(list, "hasMore", "isHasMore")) {
            is Boolean -> value
            is Number -> value.toInt() != 0
            else -> false
        }
        val maxCursor = (readValue(list, "maxCursor", "getMaxCursor") as? Number)?.toLong() ?: 0L

        return UserPostPage(awemes, hasMore, maxCursor)
    }

    /**
     * 作品列表不总是叫 `items`：`FeedItemResponse` 唯一字段被混淆成 `LIZ`。
     * 所以先按常见名字找，再扫所有 List 字段，取第一个装着 Aweme 的。
     */
    private fun findAwemeList(target: Any): List<Aweme> {
        (readValue(target, "items", "getItems") as? List<*>)
            ?.filterIsInstance<Aweme>()
            ?.takeIf { it.isNotEmpty() }
            ?.let { return it }

        var type: Class<*>? = target.javaClass
        while (type != null && type != Any::class.java) {
            for (field in type.declaredFields) {
                if (!List::class.java.isAssignableFrom(field.type)) continue
                val value = runCatching {
                    field.isAccessible = true
                    field.get(target) as? List<*>
                }.getOrNull() ?: continue
                val awemes = value.filterIsInstance<Aweme>()
                if (awemes.isNotEmpty()) {
                    BatchDebug.log("作品列表字段: ${type.simpleName}.${field.name} (${awemes.size})")
                    return awemes
                }
            }
            type = type.superclass
        }
        return emptyList()
    }

    /** 只用于日志：把响应关键字段压成一行 */
    private fun describe(raw: Any?): String {
        if (raw == null) return "null"
        val awemes = findAwemeList(raw)
        return buildString {
            append("type=").append(raw.javaClass.simpleName)
            append(" status=").append(readValue(raw, "status_code", "statusCode", "getStatusCode"))
            append(" msg=").append(readValue(raw, "status_msg", "statusMsg", "getStatusMsg"))
            append(" items=").append(if (awemes.isEmpty()) null else awemes.size)
            append(" first=").append(awemes.firstOrNull()?.javaClass?.simpleName)
            append(" hasMore=").append(readValue(raw, "hasMore", "isHasMore"))
            append(" maxCursor=").append(readValue(raw, "maxCursor", "getMaxCursor"))
        }
    }

    private fun readValue(target: Any, vararg names: String): Any? {
        var type: Class<*>? = target.javaClass
        while (type != null && type != Any::class.java) {
            val current = type
            for (name in names) {
                val declared = runCatching { current.getDeclaredField(name) }.getOrNull() ?: continue
                val value = runCatching {
                    declared.isAccessible = true
                    declared.get(target)
                }.getOrNull()
                if (value != null) return value
            }
            type = current.superclass
        }

        for (name in names) {
            val capitalized = name.replaceFirstChar { it.uppercaseChar() }
            val getters = listOf(name, "get$capitalized", "is$capitalized").distinct()
            for (getter in getters) {
                val value = runCatching { target.javaClass.getMethod(getter).invoke(target) }.getOrNull()
                if (value != null) return value
            }
        }
        return null
    }

    private fun resolveEndpoints(): List<Endpoint> {
        endpoints?.let { return it }
        synchronized(this) {
            endpoints?.let { return it }
            val built = runCatching { buildEndpoints() }
                .onFailure {
                    lastError = "${it.javaClass.simpleName}: ${it.message.orEmpty()}"
                    BatchDebug.log("解析接口异常: ${it.javaClass.name}: ${it.message}")
                    XplerLog.e("$TAG 解析异常: ${it.javaClass.name}: ${it.message}")
                }
                .getOrDefault(emptyList())
            if (built.isNotEmpty()) lastError = null else lastError = buildError ?: lastError
            endpoints = built
            return built
        }
    }

    private var buildError: String? = null

    private fun buildEndpoints(): List<Endpoint> {
        buildError = null
        val loader = hostClassLoader()
        val factory = retrofitFactory(loader)
        if (factory == null) {
            buildError = "找不到 IRetrofitFactory"
            BatchDebug.log(buildError!!)
            return emptyList()
        }

        val hosts = hostCandidates(loader)
        BatchDebug.log("候选域名: $hosts")

        val result = ArrayList<Endpoint>()
        val failures = ArrayList<String>()

        for (host in hosts) {
            val retrofit = runCatching { createRetrofit(factory, host) }
                .onFailure { BatchDebug.log("创建 Retrofit 失败($host): ${it.message}") }
                .getOrNull() ?: continue

            for (spec in SPECS) {
                val clazz = loadClass(spec.className, loader)
                if (clazz == null) {
                    failures.add("${spec.className} 不存在")
                    continue
                }
                val method = clazz.declaredMethods.firstOrNull { it.name == spec.methodName }
                if (method == null) {
                    failures.add("${spec.className}#${spec.methodName} 不存在")
                    continue
                }
                val api = runCatching { createService(retrofit, clazz) }
                    .onFailure { BatchDebug.log("创建服务失败(${spec.label}): ${it.javaClass.name}: ${it.message}") }
                    .getOrNull() ?: continue
                result.add(Endpoint(spec, api, method, host))
            }
        }

        if (result.isEmpty()) {
            buildError = failures.distinct().joinToString("; ").ifBlank { "所有候选接口/域名均创建失败" }
            BatchDebug.log("接口全部不可用: $buildError")
        } else {
            BatchDebug.log("可用接口: " + result.joinToString { it.label })
        }
        return result
    }

    /**
     * 取 weak post 列表里的作品 ID。
     *
     * 31.5.0 主页列表走的是 `/aweme/v1/aweme/weak/post/`，它包含作者的全部作品（含图文），
     * 只是字段很少（只有 awemeId/desc/点赞/播放）。用它来跟作品列表接口对账，
     * 能发现被某个接口过滤掉的作品。
     *
     * 这里优先直接调用 App 自己的 `PostAwemeRepo.LIZ`：参数实体由 App 构造，`publish_video_strategy_type`、
     * `show_live_replay_strategy`、`is_order_flow`、`post_serial_strategy`、`item_list_id` 这些值
     * 都是 App 运行时算出来的，自己拼很容易漏一个就换来 `status=5 参数不合法`。
     */
    private suspend fun fetchWeakPostIds(secUid: String?, uid: String?, count: Int): List<String> =
        withContext(Dispatchers.IO) {
            // 首选：完全走 App 的参数构造 + App 自己的 TabApi
            val viaRepo = runCatching { weakIdsViaRepo(secUid, uid, count) }
                .onFailure {
                    val cause = it.cause ?: it
                    BatchDebug.log("weak post(App) 失败: ${cause.javaClass.simpleName}: ${cause.message}")
                }
                .getOrDefault(emptyList())
            if (viaRepo.isNotEmpty()) return@withContext viaRepo

            // 兜底：自己拼参数（参数名按反编译结果写，字段名是 cursor 不是 max_cursor）
            runCatching {
                val loader = hostClassLoader()
                val factory = retrofitFactory(loader) ?: return@runCatching emptyList<String>()
                val clazz = loadClass(WEAK_CLASS, loader) ?: return@runCatching emptyList()
                val method = clazz.declaredMethods.firstOrNull { it.name == WEAK_METHOD }
                    ?: return@runCatching emptyList()
                val host = preferred?.host ?: hostCandidates(loader).firstOrNull()
                    ?: return@runCatching emptyList()
                val retrofit = createRetrofit(factory, host) ?: return@runCatching emptyList()
                val api = createService(retrofit, clazz) ?: return@runCatching emptyList()

                val params = toStringMap(weakParams(secUid, uid, 0L, count))
                method.isAccessible = true
                val raw = method.invoke(api, params) ?: return@runCatching emptyList()
                val list = awaitResultBlocking(raw) ?: return@runCatching emptyList()
                val weak = readValue(list, "weakPostAwemeList", "getWeakPostAwemeList") as? List<*>
                BatchDebug.log("weak post(手工): ${describe(list)}, weakCount=${weak?.size}")
                weak.orEmpty()
                    .mapNotNull { item -> item?.let { readValue(it, "awemeId", "getAwemeId")?.toString() } }
                    .filter { it.isNotBlank() }
            }.onFailure {
                val cause = it.cause ?: it
                BatchDebug.log("weak post 探测失败: ${cause.javaClass.simpleName}: ${cause.message}")
            }.getOrDefault(emptyList())
        }

    /**
     * 直接调用 App 的 `PostAwemeRepo.LIZ(参数实体, itemListId, collectionItemListId)`。
     *
     * 参数类的混淆名（`X/0zDU`）每个版本都会变，所以从方法签名里反推，
     * 再用它的 6 参构造 `(int tabType, String userId, String secUid, long cursor, int count, FeedItemList)` 造实例：
     * `tabType=0` 是「作品」页（App 里只有这个值才会带 `post_serial_strategy`）。
     */
    private fun weakIdsViaRepo(secUid: String?, uid: String?, count: Int): List<String> {
        val loader = hostClassLoader() ?: return emptyList()
        val repoClass = loadClass(WEAK_REPO_CLASS, loader) ?: return emptyList()

        // 3 个参数、第 2 个是 String、第 3 个是 Gson 的 JsonObject —— 唯一匹配 weak post
        val build = repoClass.declaredMethods.firstOrNull { method ->
            method.parameterTypes.size == 3 &&
                    method.parameterTypes[1] == String::class.java &&
                    method.parameterTypes[2].name.endsWith("JsonObject")
        } ?: return emptyList()

        val paramClass = build.parameterTypes[0]
        val ctor = paramClass.declaredConstructors.firstOrNull { candidate ->
            val types = candidate.parameterTypes
            types.size == 6 &&
                    types[0] == Int::class.javaPrimitiveType &&
                    types[1] == String::class.java &&
                    types[2] == String::class.java &&
                    types[3] == Long::class.javaPrimitiveType &&
                    types[4] == Int::class.javaPrimitiveType
        } ?: run {
            BatchDebug.log("weak post(App): ${paramClass.name} 没有 6 参构造")
            return emptyList()
        }

        ctor.isAccessible = true
        val param = ctor.newInstance(0, uid.orEmpty(), secUid.orEmpty(), 0L, count, null)

        val jsonClass = loadClass("com.google.gson.JsonObject", loader)
        val collectionItemListId = jsonClass?.getDeclaredConstructor()?.let {
            it.isAccessible = true
            it.newInstance()
        }

        val repo = repoClass.getDeclaredConstructor().apply { isAccessible = true }.newInstance()
        build.isAccessible = true
        val observable = build.invoke(repo, param, "", collectionItemListId) ?: return emptyList()
        val result = awaitResultBlocking(observable) ?: return emptyList()

        val weak = readValue(result, "weakPostAwemeList", "getWeakPostAwemeList") as? List<*>
        val collection = readValue(result, "collectionWeakPostAwemeList", "getCollectionWeakPostAwemeList") as? List<*>
        BatchDebug.log(
            "weak post(App): status=${readValue(result, "statusCode", "getStatusCode")} " +
                    "msg=${readValue(result, "statusMsg", "getStatusMsg")} " +
                    "weak=${weak?.size} collection=${collection?.size}"
        )
        return (weak.orEmpty() + collection.orEmpty()).mapNotNull { item ->
            item ?: return@mapNotNull null
            readValue(item, "awemeId", "getAwemeId")?.toString()?.takeIf { it.isNotBlank() }
        }
    }

    /**
     * 按作品 ID 批量取完整 Aweme（`/aweme/v1/multi/aweme/detail/`）。
     * 用于补回 weak post 里有、但作品列表接口没返回的作品（例如图文）。
     */
    private suspend fun fetchAwemesByIds(ids: List<String>): List<Aweme> {
        if (ids.isEmpty()) return emptyList()
        return withContext(Dispatchers.IO) {
            runCatching {
                val loader = hostClassLoader()
                val factory = retrofitFactory(loader) ?: return@runCatching emptyList<Aweme>()
                val host = preferred?.host ?: hostCandidates(loader).firstOrNull()
                    ?: return@runCatching emptyList()
                val retrofit = createRetrofit(factory, host) ?: return@runCatching emptyList()
                val joined = ids.joinToString(",")

                for ((className, methodName) in DETAIL_SPECS) {
                    val clazz = loadClass(className, loader) ?: continue
                    val method = clazz.declaredMethods.firstOrNull { it.name == methodName } ?: continue
                    val api = runCatching { createService(retrofit, clazz) }.getOrNull() ?: continue

                    val items = try {
                        method.isAccessible = true
                        val values = mapOf(
                            "aweme_ids" to joined,
                            "request_source" to 0,
                            "location_permission" to 0,
                        )
                        val args = buildTypedArgs(method, values, null)
                        val raw = method.invoke(api, *args) ?: continue
                        val detail = awaitResultBlocking(raw) ?: continue
                        readDetailAwemes(detail, ids)
                    } catch (e: Throwable) {
                        val cause = e.cause ?: e
                        BatchDebug.log("按 ID 取详情失败($className): ${cause.javaClass.simpleName}: ${cause.message}")
                        continue
                    }

                    BatchDebug.log("按 ID 取详情($className): 请求 ${ids.size} 个, 返回 ${items.size} 个")
                    if (items.isNotEmpty()) return@runCatching items
                }
                emptyList()
            }.onFailure {
                val cause = it.cause ?: it
                BatchDebug.log("按 ID 取详情异常: ${cause.javaClass.simpleName}: ${cause.message}")
            }.getOrDefault(emptyList())
        }
    }

    /**
     * 解析 `/aweme/v1/multi/aweme/detail/` 的响应。
     *
     * `items` 是被正常返回的作品；`filterList` / `verificationFilterList` 里是被风控/需验证筛掉的作品，
     * 有时候完整数据就在这两个列表里，所以一起收，同一个 aid 优先留带图片/视频的那份。
     */
    private fun readDetailAwemes(detail: Any, requested: List<String>): List<Aweme> {
        val wanted = requested.toHashSet()
        val byId = LinkedHashMap<String, Aweme>()

        fun collect(list: List<*>?) {
            list.orEmpty().filterIsInstance<Aweme>().forEach { aweme ->
                val id = aweme.aid?.takeIf { it.isNotBlank() }
                    ?: readValue(aweme, "awemeId", "getAwemeId")?.toString()?.takeIf { it.isNotBlank() }
                    ?: return@forEach
                val old = byId[id]
                val hasMedia = aweme.images != null || aweme.video != null
                if (old == null) {
                    byId[id] = aweme
                } else if (old.images == null && old.video == null && hasMedia) {
                    byId[id] = aweme
                }
            }
        }

        val items = readValue(detail, "items", "getItems") as? List<*>
        val filtered = readValue(detail, "filterList", "getFilterList") as? List<*>
        val verification = readValue(detail, "verificationFilterList", "getVerificationFilterList") as? List<*>
        collect(items)
        collect(filtered)
        collect(verification)

        BatchDebug.log(
            "detail 列表: items=${items?.size ?: 0}, filter=${filtered?.size ?: 0}, verify=${verification?.size ?: 0}"
        )

        if (wanted.isEmpty()) return byId.values.toList()
        val ordered = wanted.mapNotNull { byId[it] }
        // 服务端可能用别的 id 命中，别丢
        return ordered + byId.values.filter { (it.aid.orEmpty()) !in wanted }
    }

    private fun awaitResultBlocking(result: Any): Any? {
        if (implements(result.javaClass, "io.reactivex.Observable") ||
            implements(result.javaClass, "io.reactivex.Flowable")
        ) {
            return runCatching { result.javaClass.getMethod("blockingFirst").invoke(result) }
                .getOrElse { throw it.cause ?: it }
        }
        if (implements(result.javaClass, "io.reactivex.Single")) {
            return runCatching { result.javaClass.getMethod("blockingGet").invoke(result) }
                .getOrElse { throw it.cause ?: it }
        }
        // bolts Task（HistoryModel$API#getAwemeDetail）
        if (implements(result.javaClass, "bolts.Task")) {
            val task = runCatching { result.javaClass.getMethod("waitForCompletion").invoke(result) }.getOrNull()
                ?: return null
            val faulted = runCatching {
                task.javaClass.getMethod("isFaulted").invoke(task) as Boolean
            }.getOrDefault(false)
            if (faulted) {
                val error = runCatching { task.javaClass.getMethod("getError").invoke(task) }.getOrNull()
                throw (error as? Throwable) ?: RuntimeException("Task failed")
            }
            return runCatching { task.javaClass.getMethod("getResult").invoke(task) }.getOrNull()
        }
        return result
    }

    private fun hostClassLoader(): ClassLoader? {
        return runCatching { lparam.classLoader }.getOrNull()
                ?: runCatching { Aweme::class.java.classLoader }.getOrNull()
    }

    private fun loadClass(name: String, loader: ClassLoader?): Class<*>? {
        return runCatching { Class.forName(name, false, loader) }.getOrNull()
    }

    /**
     * 接口域名：`https://` + `AppContextManager.getApiHost().API_HOST_I_SNSSDK`，
     * 和抖音创建 Retrofit 时用的是同一个值。
     *
     * 先读 `AppContextManager`，再读 `CommonConstants`：后者静态初始化会再取一遍 ApiHost，
     * 万一初始化异常，类会进入 Error 状态，后续都取不到。
     */
    private fun hostCandidates(loader: ClassLoader?): List<String> {
        val hosts = LinkedHashSet<String>()
        appApiHost(loader)?.let { hosts.add(it) }
        hosts.addAll(FALLBACK_HOSTS)
        return hosts.filter { isValidHost(it) }
    }

    private fun isValidHost(host: String?): Boolean {
        if (host.isNullOrBlank() || !host.startsWith("http")) return false
        val rest = host.substringAfter("://")
        return rest.isNotBlank() && rest.contains('.') && !rest.contains(' ')
    }

    private fun appApiHost(loader: ClassLoader?): String? {
        runCatching {
            val manager = loadClass("com.bytedance.ies.ugc.appcontext.AppContextManager", loader) ?: return@runCatching
            val apiHost = manager.getMethod("getApiHost").invoke(null) ?: return@runCatching
            val host = apiHost.javaClass.getField("API_HOST_I_SNSSDK").get(apiHost) as? String
            val raw = host?.trim()
            if (!raw.isNullOrEmpty()) {
                val normalized = if (raw.startsWith("http")) raw else "https://$raw"
                if (isValidHost(normalized)) return normalized
            }
        }

        val constants = loadClass("com.ss.android.constants.CommonConstants", loader)
        if (constants != null) {
            for (name in arrayOf("API_URL_PREFIX_API", "LIZIZ", "LIZ")) {
                val value = runCatching {
                    constants.getField(name).apply { isAccessible = true }.get(null) as? String
                }.getOrNull()
                if (isValidHost(value)) return value
            }
            constants.fields.forEach { field ->
                if (field.type != String::class.java) return@forEach
                val value = runCatching { field.isAccessible = true; field.get(null) as? String }.getOrNull()
                if (isValidHost(value)) return value
            }
        }
        return null
    }

    /**
     * 31.5.0 的真实入口：`RetrofitFactory.createIRetrofitFactorybyMonsterPlugin(false)`，
     * 直接走 `ServiceManager.getService` 可能拿到空实现或 DynamicProxy。
     */
    private fun retrofitFactory(loader: ClassLoader?): Any? {
        val factoryClass = loadClass("com.bytedance.ies.ugc.aweme.network.RetrofitFactory", loader)
        if (factoryClass != null) {
            runCatching {
                factoryClass
                    .getMethod("createIRetrofitFactorybyMonsterPlugin", Boolean::class.javaPrimitiveType)
                    .invoke(null, false)
                    ?.let { return it }
            }.onFailure { BatchDebug.log("createIRetrofitFactorybyMonsterPlugin 失败: ${it.message}") }

            runCatching {
                factoryClass.getMethod("createNewServiceImplInstanceForSPI")
                    .invoke(null)
                    ?.let { return it }
            }.onFailure { BatchDebug.log("createNewServiceImplInstanceForSPI 失败: ${it.message}") }
        }

        val factoryInterface = loadClass("com.bytedance.ies.ugc.aweme.network.IRetrofitFactory", loader)
        if (factoryInterface != null) {
            runCatching {
                val managerClass = loadClass("com.ss.android.ugc.aweme.framework.services.ServiceManager", loader)
                if (managerClass != null) {
                    val manager = managerClass.getMethod("get").invoke(null)
                    managerClass.getMethod("getService", Class::class.java)
                        .invoke(manager, factoryInterface)
                        ?.let { return it }
                }
            }.onFailure { BatchDebug.log("ServiceManager 取 IRetrofitFactory 失败: ${it.message}") }

            runCatching {
                val monster = loadClass("com.ss.android.ugc.MonsterSingleTon", loader)
                monster?.getMethod("getServiceFromMap", Class::class.java, Boolean::class.javaPrimitiveType)
                    ?.invoke(null, factoryInterface, false)
                    ?.let { return it }
            }.onFailure { BatchDebug.log("MonsterSingleTon 取 IRetrofitFactory 失败: ${it.message}") }
        }
        return null
    }

    private fun createRetrofit(factory: Any, host: String): Any? {
        val factoryInterface = loadClass("com.bytedance.ies.ugc.aweme.network.IRetrofitFactory", factory.javaClass.classLoader)

        if (factoryInterface != null) {
            runCatching {
                factoryInterface.getMethod("create", String::class.java).invoke(factory, host)
                    ?.let { return it }
            }.onFailure { BatchDebug.log("IRetrofitFactory.create 失败: ${it.message}") }
        }

        val builder = runCatching {
            if (factoryInterface != null) {
                factoryInterface.getMethod("createBuilder", String::class.java).invoke(factory, host)
            } else {
                factory.javaClass.getMethod("createBuilder", String::class.java).invoke(factory, host)
            }
        }.getOrElse {
            BatchDebug.log("createBuilder 失败: ${it.javaClass.name}: ${it.message}")
            return null
        } ?: return null

        val builderInterface = loadClass("com.bytedance.ies.ugc.aweme.network.IBuilder", builder.javaClass.classLoader)
        return runCatching {
            if (builderInterface != null) {
                builderInterface.getMethod("build").invoke(builder)
            } else {
                builder.javaClass.getMethod("build").invoke(builder)
            }
        }.getOrElse {
            BatchDebug.log("IBuilder.build 失败: ${it.javaClass.name}: ${it.message}")
            null
        }
    }

    private fun createService(retrofit: Any, clazz: Class<*>): Any? {
        val retrofitInterface = loadClass("com.bytedance.ies.ugc.aweme.network.IRetrofit", retrofit.javaClass.classLoader)
        if (retrofitInterface != null) {
            return retrofitInterface.getMethod("create", Class::class.java).invoke(retrofit, clazz)
        }
        return retrofit.javaClass.getMethod("create", Class::class.java).invoke(retrofit, clazz)
    }
}
