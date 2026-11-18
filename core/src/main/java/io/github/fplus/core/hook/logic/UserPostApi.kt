package io.github.fplus.core.hook.logic

import com.ss.android.ugc.aweme.feed.model.Aweme
import io.github.xpler.core.XplerLog
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
 * 为什么要绕这么一圈：
 * 抖音的作品列表接口必须带 App 内的签名（X-Gorgon / X-Bogus），裸 HTTP 请求只会返回空列表。
 * 所以这里不自己拼请求，而是复用抖音自己的 Retrofit 栈：
 *
 *  `ServiceManager.getService(IRetrofitFactory)` -> `createBuilder(host)` -> `build()` -> `create(接口)`
 *
 * 这样公共参数、签名、Cookie 都由抖音的拦截器自动补全，和 App 打开别人主页时的请求完全一致。
 * 接口本身按类名候选 + `@GET("/aweme/v1/aweme/post/")` 注解识别，抖音改包名时只需往候选里加一行。
 */
object UserPostApi {
    private const val TAG = "UserPostApi"
    private const val POST_PATH = "/aweme/v1/aweme/post/"

    /**
     * 已知携带作品列表接口的接口类，按优先级排列。
     * 31.5.0：PostAwemeRepo$TabApi；备用：IFeedLiveShareProfileApi。
     */
    private val CANDIDATE_APIS = listOf(
        "com.ss.android.ugc.aweme.profile.tab.api.PostAwemeRepo\$TabApi",
        "com.ss.android.ugc.aweme.feedliveshare.screen.api.IFeedLiveShareProfileApi",
    )

    @Volatile
    private var endpoint: Endpoint? = null

    private data class Endpoint(val api: Any, val method: Method)

    /**
     * 接口是否可用（只做一次解析，失败会缓存，避免每次长按都重试）
     */
    fun isAvailable(): Boolean = resolveEndpoint() != null

    /**
     * 拉取一页作品
     *
     * @param secUid   作者 sec_uid，优先使用
     * @param uid      作者 uid，sec_uid 为空时使用
     * @param cursor   游标，第一页传 0
     * @param count    每页数量
     */
    suspend fun fetchPage(
        secUid: String?,
        uid: String?,
        cursor: Long,
        count: Int,
    ): UserPostPage {
        val endpoint = resolveEndpoint()
            ?: return UserPostPage(emptyList(), false, 0L, "未找到作品列表接口（当前抖音版本可能不兼容）")

        return try {
            val result = invoke(endpoint, buildParams(secUid, uid, cursor, count))
            parsePage(result)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            XplerLog.e(e)
            UserPostPage(emptyList(), false, 0L, "请求失败: ${e.javaClass.simpleName} ${e.message.orEmpty()}".trim())
        }
    }

    private fun buildParams(
        secUid: String?,
        uid: String?,
        cursor: Long,
        count: Int,
    ): Map<String, String> {
        val params = LinkedHashMap<String, String>()
        params["cursor"] = cursor.toString()
        // 不同版本的接口用不同的游标字段，两个都带上，值一致不会有副作用
        params["max_cursor"] = cursor.toString()
        if (!secUid.isNullOrBlank()) {
            params["sec_user_id"] = secUid
        } else if (!uid.isNullOrBlank()) {
            params["user_id"] = uid
        }
        params["count"] = count.toString()
        params["source"] = "0"
        return params
    }

    private suspend fun invoke(endpoint: Endpoint, params: Map<String, String>): Any? {
        val method = endpoint.method
        method.isAccessible = true

        val isSuspend = isSuspendMethod(method)

        if (isSuspend) {
            return suspendCancellableCoroutine<Any?> { continuation ->
                try {
                    method.invoke(endpoint.api, params, continuation)
                } catch (e: Throwable) {
                    continuation.resumeWith(Result.failure(e))
                }
            }
        }

        return withContext(Dispatchers.IO) {
            val result = method.invoke(endpoint.api, params) ?: return@withContext null
            when {
                result is kotlinx.coroutines.Deferred<*> -> result.await()
                result.javaClass.name.startsWith("io.reactivex") ->
                    result.javaClass.getMethod("blockingFirst").invoke(result)

                else -> {
                    // com.bytedance.retrofit2.Call
                    val response = result.javaClass.getMethod("execute").invoke(result)
                    response?.javaClass?.getMethod("body")?.invoke(response)
                }
            }
        }
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

        // 反射字段读不到时再试 getter，例如 statusCode -> getStatusCode
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

    private fun resolveEndpoint(): Endpoint? {
        endpoint?.let { return it }
        synchronized(this) {
            endpoint?.let { return it }
            // 只在成功时缓存：失败（例如调用时机太早、接口类还没加载）下次长按可以重试
            endpoint = runCatching { buildEndpoint() }
                .onFailure { XplerLog.e(it) }
                .getOrNull()
            return endpoint
        }
    }

    private fun buildEndpoint(): Endpoint? {
        val host = apiBaseUrl() ?: return null
        val factory = retrofitFactory() ?: return null

        for (name in CANDIDATE_APIS) {
            val clazz = runCatching { Class.forName(name) }.getOrNull() ?: continue
            if (!clazz.isInterface) continue

            val method = selectMethod(clazz) ?: continue

            // 单个候选创建失败（Retrofit 不认这个接口等）时继续试下一个
            val api = runCatching { createService(factory, host, clazz) }
                .onFailure { XplerLog.e(it) }
                .getOrNull() ?: continue

            XplerLog.d("$TAG 使用接口: $name#${method.name}")
            return Endpoint(api, method)
        }

        XplerLog.d("$TAG 未找到作品列表接口, host=$host")
        return null
    }

    /**
     * 同一个接口类里可能同时有 `fetchPublicList(int, Map)`（返回 Deferred）和
     * `fetchPublicListInCoroutine(Map, Continuation)`（suspend）两个方法，declaredMethods 顺序不保证。
     * 优先选 suspend 版本，其次选只有一个 Map 参数的非 suspend 版本，保证调用时参数个数对得上。
     */
    private fun selectMethod(clazz: Class<*>): Method? {
        val candidates = clazz.declaredMethods.filter { isPostMethod(it) }
        return candidates.firstOrNull { isSuspendMethod(it) }
            ?: candidates.firstOrNull { method ->
                method.parameterTypes.size == 1 &&
                        Map::class.java.isAssignableFrom(method.parameterTypes[0])
            }
    }

    private fun isSuspendMethod(method: Method): Boolean {
        return method.parameterTypes.lastOrNull()
            ?.let { Continuation::class.java.isAssignableFrom(it) } == true
    }

    /** 方法上是 `@GET("/aweme/v1/aweme/post/")` 且参数里有 Map（@QueryMap） */
    private fun isPostMethod(method: Method): Boolean {
        val path = method.annotations
            .firstOrNull { it.annotationClass.java.name == "com.bytedance.retrofit2.http.GET" }
            ?.let { annotation ->
                runCatching {
                    annotation.annotationClass.java.getMethod("value").invoke(annotation) as? String
                }.getOrNull()
            }
            ?: return false

        return path.startsWith(POST_PATH) &&
                method.parameterTypes.any { Map::class.java.isAssignableFrom(it) }
    }

    /**
     * 接口域名：`CommonConstants.API_URL_PREFIX_API` = `https://` + `AppContextManager.getApiHost().API_HOST_I_SNSSDK`，
     * 和抖音自己创建 Retrofit 时用的是同一个值。
     */
    private fun apiBaseUrl(): String? {
        runCatching {
            val value = Class.forName("com.ss.android.constants.CommonConstants")
                .getField("API_URL_PREFIX_API")
                .get(null) as? String
            if (!value.isNullOrBlank()) return value
        }

        runCatching {
            val apiHost = Class.forName("com.bytedance.ies.ugc.appcontext.AppContextManager")
                .getMethod("getApiHost")
                .invoke(null) ?: return null
            val host = apiHost.javaClass.getField("API_HOST_I_SNSSDK").get(apiHost) as? String
                ?: return null
            return if (host.startsWith("http")) host else "https://$host"
        }

        return null
    }

    private fun retrofitFactory(): Any? {
        val managerClass = Class.forName("com.ss.android.ugc.aweme.framework.services.ServiceManager")
        val manager = managerClass.getMethod("get").invoke(null)
        val factoryClass = Class.forName("com.bytedance.ies.ugc.aweme.network.IRetrofitFactory")
        return managerClass.getMethod("getService", Class::class.java).invoke(manager, factoryClass)
    }

    private fun createService(factory: Any, host: String, clazz: Class<*>): Any? {
        val factoryClass = Class.forName("com.bytedance.ies.ugc.aweme.network.IRetrofitFactory")
        val builder = factoryClass.getMethod("createBuilder", String::class.java).invoke(factory, host)

        val builderClass = Class.forName("com.bytedance.ies.ugc.aweme.network.IBuilder")
        val retrofit = builderClass.getMethod("build").invoke(builder)

        val retrofitClass = Class.forName("com.bytedance.ies.ugc.aweme.network.IRetrofit")
        return retrofitClass.getMethod("create", Class::class.java).invoke(retrofit, clazz)
    }
}
