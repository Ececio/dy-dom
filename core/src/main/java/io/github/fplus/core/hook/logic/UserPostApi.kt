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

        var lastPage: UserPostPage? = null
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
                XplerLog.e("$TAG 调用失败(${endpoint.label}): ${cause.javaClass.name}: ${cause.message}")
                lastPage = UserPostPage(
                    emptyList(), false, 0L,
                    "请求失败: ${cause.javaClass.simpleName} ${cause.message.orEmpty()}".trim(),
                )
                continue
            }

            if (page.awemes.isNotEmpty()) {
                if (endpoint.spec.paging) preferred = endpoint
                return page
            }
            // 第一页就空：继续换下一个接口；翻页后为空说明已到底，直接返回
            BatchDebug.log("[${endpoint.label}] 作品为空${page.error?.let { " ($it)" }.orEmpty()}")
            if (lastPage == null || lastPage.error == null) lastPage = page
            if (cursor != 0L) return page
        }

        // 全部接口都空 -> 探测 weak post，把真实情况写进日志
        if (cursor == 0L) probeWeakPost(secUid, uid, count)

        return lastPage ?: UserPostPage(emptyList(), false, 0L, "接口返回为空")
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
        if (!secUid.isNullOrBlank()) params["sec_user_id"] = secUid else if (!uid.isNullOrBlank()) params["user_id"] = uid
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
        return method.parameterTypes.lastOrNull()
            ?.let { Continuation::class.java.isAssignableFrom(it) } == true
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
                Continuation::class.java.isAssignableFrom(type) -> continuation
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
            if (Continuation::class.java.isAssignableFrom(type)) {
                continuation
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
        if (result is kotlinx.coroutines.Deferred<*>) return result.await()

        val name = result.javaClass.name
        if (implements(result.javaClass, "io.reactivex.Single") ||
            implements(result.javaClass, "io.reactivex.Maybe")
        ) {
            return runCatching { result.javaClass.getMethod("blockingGet").invoke(result) }
                .getOrElse { throw it.cause ?: it }
        }
        if (implements(result.javaClass, "io.reactivex.Observable") ||
            implements(result.javaClass, "io.reactivex.Flowable")
        ) {
            return runCatching { result.javaClass.getMethod("blockingFirst").invoke(result) }
                .getOrElse { throw it.cause ?: it }
        }
        if (name.contains("retrofit2.Call") ||
            implements(result.javaClass, "com.bytedance.retrofit2.Call") ||
            implements(result.javaClass, "retrofit2.Call")
        ) {
            val response = result.javaClass.getMethod("execute").invoke(result) ?: return null
            return response.javaClass.getMethod("body").invoke(response)
        }
        return result
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
        val awemes = (readValue(list, "items", "getItems") as? List<*>)
            ?.filterIsInstance<Aweme>()
            ?: emptyList()

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

    /** 只用于日志：把响应关键字段压成一行 */
    private fun describe(raw: Any?): String {
        if (raw == null) return "null"
        val items = readValue(raw, "items", "getItems") as? List<*>
        return buildString {
            append("type=").append(raw.javaClass.simpleName)
            append(" status=").append(readValue(raw, "status_code", "statusCode", "getStatusCode"))
            append(" msg=").append(readValue(raw, "status_msg", "statusMsg", "getStatusMsg"))
            append(" items=").append(items?.size)
            append(" first=").append(items?.firstOrNull()?.javaClass?.simpleName)
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

    /** 探测 weak post：31.5.0 主页列表走的是它，日志里记录条数便于后续适配 */
    private fun probeWeakPost(secUid: String?, uid: String?, count: Int) {
        runCatching {
            val loader = hostClassLoader()
            val factory = retrofitFactory(loader) ?: return
            val clazz = loadClass(WEAK_CLASS, loader) ?: return
            val method = clazz.declaredMethods.firstOrNull { it.name == WEAK_METHOD } ?: return
            val host = preferred?.host ?: hostCandidates(loader).firstOrNull() ?: return
            val retrofit = createRetrofit(factory, host) ?: return
            val api = createService(retrofit, clazz) ?: return

            val params = toStringMap(postParams(secUid, uid, 0L, count))
            method.isAccessible = true
            val result = method.invoke(api, params) ?: return
            val list = awaitResultBlocking(result)
            val weak = readValue(list ?: return, "weakPostAwemeList", "getWeakPostAwemeList") as? List<*>
            BatchDebug.log("weak post: ${describe(list)}, weakCount=${weak?.size}")
            weak?.firstOrNull()?.let { first ->
                val id = readValue(first, "awemeId", "getAwemeId")
                BatchDebug.log("weak post firstId=$id")
            }
        }.onFailure {
            BatchDebug.log("weak post 探测失败: ${it.javaClass.simpleName}: ${it.message}")
        }
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
