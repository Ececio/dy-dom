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
 * 为什么要绕这么一圈：
 * 抖音的作品列表接口必须带 App 内的签名（X-Gorgon / X-Bogus），裸 HTTP 请求只会返回空列表。
 * 所以这里不自己拼请求，而是复用抖音自己的 Retrofit 栈：
 *
 *  `RetrofitFactory.createIRetrofitFactorybyMonsterPlugin(false)` -> `create(host)` -> `create(接口)`
 *
 * 这样公共参数、签名、Cookie 都由抖音的拦截器自动补全，和 App 打开别人主页时的请求完全一致。
 *
 * 接口本身按类名候选 + `@GET("/aweme/v1/aweme/post/")` 注解识别，抖音改包名时只需往候选里加一行。
 */
object UserPostApi {
    private const val TAG = "UserPostApi"
    private const val POST_PATH = "/aweme/v1/aweme/post/"

    /**
     * 已知携带作品列表接口的接口类 / 外部类，按优先级排列。
     * 31.5.0：`PostAwemeRepo$TabApi`；备用：`IFeedLiveShareProfileApi`。
     */
    private val CANDIDATE_APIS = listOf(
        "com.ss.android.ugc.aweme.profile.tab.api.PostAwemeRepo\$TabApi",
        "com.ss.android.ugc.aweme.feedliveshare.screen.api.IFeedLiveShareProfileApi",
    )

    /** 只知道外部类时，从 `declaredClasses` 里找带作品接口的内部接口 */
    private val CANDIDATE_OUTER = listOf(
        "com.ss.android.ugc.aweme.profile.tab.api.PostAwemeRepo",
    )

    /** 拿不到 App 真实域名时的兜底，按顺序尝试 */
    private val FALLBACK_HOSTS = listOf(
        "https://aweme.snssdk.com",
        "https://api.amemv.com",
        "https://api3-normal-c-lf.amemv.com",
        "https://api16-normal-c-useast1a.tiktokv.com",
    )

    @Volatile
    private var endpoint: Endpoint? = null

    @Volatile
    private var lastError: String? = null

    private data class Endpoint(val api: Any, val method: Method)

    /** 最近一次解析失败的原因，用于弹窗提示 */
    fun lastError(): String? = lastError

    /**
     * 接口是否可用（只在成功时缓存，失败下次长按会重试）
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
            ?: return UserPostPage(
                emptyList(), false, 0L,
                "未找到作品列表接口（当前抖音版本可能不兼容）" + lastError?.let { ": $it" }.orEmpty(),
            )

        return try {
            val result = invoke(endpoint, buildParams(secUid, uid, cursor, count), cursor, count)
            parsePage(result)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            XplerLog.e("$TAG 请求失败: ${e.javaClass.name}: ${e.message}")
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
        // 不同版本的接口用不同的游标字段，两个都带上，值一致不会有副作用
        params["cursor"] = cursor.toString()
        params["max_cursor"] = cursor.toString()
        if (!secUid.isNullOrBlank()) {
            params["sec_user_id"] = secUid
        } else if (!uid.isNullOrBlank()) {
            params["user_id"] = uid
        }
        params["count"] = count.toString()
        params["source"] = "0"
        params["page_from"] = "2"
        params["location_permission"] = "0"
        params["familiar_collects"] = "0"
        params["item_list_id"] = ""
        params["collection_item_list_id"] = "{}"
        return params
    }

    private suspend fun invoke(
        endpoint: Endpoint,
        params: Map<String, String>,
        cursor: Long,
        count: Int,
    ): Any? {
        val method = endpoint.method
        method.isAccessible = true

        if (isSuspendMethod(method)) {
            return suspendCancellableCoroutine { continuation ->
                try {
                    method.invoke(
                        endpoint.api,
                        *buildArgs(method, params, cursor, count, continuation),
                    )
                } catch (e: Throwable) {
                    continuation.resumeWith(Result.failure(e))
                }
            }
        }

        return withContext(Dispatchers.IO) {
            val result = method.invoke(
                endpoint.api,
                *buildArgs(method, params, cursor, count, null),
            ) ?: return@withContext null

            awaitResult(result)
        }
    }

    /** 按参数类型构造实参，兼容 `(Map)`、`(int, Map)`、`(Map, Continuation)` 等写法 */
    private fun buildArgs(
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
                type == String::class.java -> queryValue(method, index, params)
                else -> null
            }
        }
    }

    /** 非 Map 参数（例如 `@Query("sec_user_id") String secUid`）按注解里的名字取值 */
    private fun queryValue(method: Method, index: Int, params: Map<String, String>): String {
        val annotations = method.parameterAnnotations.getOrNull(index).orEmpty()
        for (annotation in annotations) {
            val name = annotation.annotationClass.java.name
            if (!name.endsWith(".Query")) continue
            val value = runCatching {
                annotation.annotationClass.java.getMethod("value").invoke(annotation) as? String
            }.getOrNull()
            if (!value.isNullOrBlank()) {
                params[value]?.let { return it }
                if (value == "sec_user_id") return params["sec_user_id"].orEmpty()
                if (value == "user_id") return params["user_id"].orEmpty()
            }
        }
        return params["sec_user_id"] ?: params["user_id"].orEmpty()
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
            val built = runCatching { buildEndpoint() }
                .onFailure {
                    lastError = "${it.javaClass.simpleName}: ${it.message.orEmpty()}"
                    XplerLog.e("$TAG 解析失败: ${it.javaClass.name}: ${it.message}")
                    XplerLog.e(it)
                }
                .getOrNull()
            if (built != null) {
                lastError = null
                endpoint = built
            }
            return endpoint
        }
    }

    private fun buildEndpoint(): Endpoint? {
        val loader = hostClassLoader()
        val classes = apiClasses(loader)
        if (classes.isEmpty()) {
            lastError = "找不到作品接口类"
            XplerLog.d("$TAG 找不到作品接口类")
            return null
        }

        val factory = retrofitFactory(loader) ?: run {
            lastError = "找不到 IRetrofitFactory"
            XplerLog.d("$TAG 找不到 IRetrofitFactory")
            return null
        }

        val hosts = hostCandidates(loader)
        XplerLog.d("$TAG 候选域名: $hosts")
        XplerLog.d("$TAG 候选接口: ${classes.map { it.name }}")

        for (host in hosts) {
            val retrofit = runCatching { createRetrofit(factory, host) }
                .onFailure { XplerLog.e("$TAG 创建 Retrofit 失败($host): ${it.message}") }
                .getOrNull() ?: continue

            for (clazz in classes) {
                val method = selectMethod(clazz)
                if (method == null) {
                    XplerLog.d("$TAG ${clazz.name} 没有作品列表方法")
                    continue
                }

                val api = runCatching { createService(retrofit, clazz) }
                    .onFailure { XplerLog.e("$TAG 创建服务失败(${clazz.name}): ${it.javaClass.name}: ${it.message}") }
                    .getOrNull() ?: continue

                XplerLog.d("$TAG 使用接口: ${clazz.name}#${method.name}, host=$host")
                return Endpoint(api, method)
            }
        }

        lastError = "所有候选接口/域名均创建失败"
        XplerLog.d("$TAG 未找到可用的作品列表接口")
        return null
    }

    private fun hostClassLoader(): ClassLoader? {
        return runCatching { lparam.classLoader }.getOrNull()
                ?: runCatching { Aweme::class.java.classLoader }.getOrNull()
    }

    private fun loadClass(name: String, loader: ClassLoader?): Class<*>? {
        return runCatching { Class.forName(name, false, loader) }.getOrNull()
    }

    /** 接口类候选 + 外部类内部接口，按作品接口注解筛选 */
    private fun apiClasses(loader: ClassLoader?): List<Class<*>> {
        val result = LinkedHashSet<Class<*>>()

        for (name in CANDIDATE_APIS) {
            val clazz = loadClass(name, loader) ?: continue
            if (clazz.isInterface) result.add(clazz)
        }

        for (name in CANDIDATE_OUTER) {
            val outer = loadClass(name, loader) ?: continue
            outer.declaredClasses
                .filter { it.isInterface && selectMethod(it) != null }
                .forEach { result.add(it) }
        }

        // 只在候选都失效时才回到注解扫描，成本较高
        if (result.isEmpty()) {
            for (name in CANDIDATE_OUTER) {
                loadClass(name, loader)?.declaredClasses?.forEach { inner ->
                    if (inner.isInterface && inner.declaredMethods.any { isPostMethod(it) }) result.add(inner)
                }
            }
        }
        return result.toList()
    }

    /**
     * 同一个接口类里可能同时有 `fetchPublicList(int, Map)`（返回 Deferred）和
     * `fetchPublicListInCoroutine(Map, Continuation)`（suspend）两个方法，declaredMethods 顺序不保证。
     * 优先选 suspend 版本，其次选只有一个 Map 参数的非 suspend 版本，最后选任意带 Map 的版本。
     */
    private fun selectMethod(clazz: Class<*>): Method? {
        val candidates = clazz.declaredMethods.filter { isPostMethod(it) }
        if (candidates.isEmpty()) return null
        return candidates.firstOrNull { method ->
            isSuspendMethod(method) && method.parameterTypes.size == 2
        } ?: candidates.firstOrNull { method ->
            !isSuspendMethod(method) && method.parameterTypes.size == 1 &&
                    Map::class.java.isAssignableFrom(method.parameterTypes[0])
        } ?: candidates.firstOrNull { isSuspendMethod(it) }
        ?: candidates.first()
    }

    private fun isSuspendMethod(method: Method): Boolean {
        return method.parameterTypes.lastOrNull()
            ?.let { Continuation::class.java.isAssignableFrom(it) } == true
    }

    /** 方法上是 `@GET("/aweme/v1/aweme/post/")` 且参数里有 Map（@QueryMap） */
    private fun isPostMethod(method: Method): Boolean {
        val path = method.annotations
            .firstOrNull {
                val name = it.annotationClass.java.name
                name == "com.bytedance.retrofit2.http.GET" || name == "retrofit2.http.GET"
            }
            ?.let { annotation ->
                runCatching {
                    annotation.annotationClass.java.getMethod("value").invoke(annotation) as? String
                }.getOrNull()
            }
            ?: return false

        if (path.trimEnd('/') != POST_PATH.trimEnd('/')) return false
        // @FormUrlEncoded 的 POST 不可能是这个接口
        if (method.annotations.any { it.annotationClass.java.name.endsWith(".FormUrlEncoded") }) return false
        return method.parameterTypes.any { Map::class.java.isAssignableFrom(it) }
    }

    /** 优先 App 真实域名，其次是历史兜底域名 */
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

    /**
     * 接口域名：`https://` + `AppContextManager.getApiHost().API_HOST_I_SNSSDK`，
     * 和抖音创建 Retrofit 时用的是同一个值。
     *
     * 先读 `AppContextManager`，再读 `CommonConstants`：后者静态初始化会再取一遍 ApiHost，
     * 万一初始化异常，类会进入 Error 状态，后续都取不到。
     */
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
            // 优先已知字段
            for (name in arrayOf("API_URL_PREFIX_API", "LIZIZ", "LIZ")) {
                val value = runCatching {
                    constants.getField(name).apply { isAccessible = true }.get(null) as? String
                }.getOrNull()
                if (isValidHost(value)) return value
            }
            // 已知字段没命中时，退而求其次取任意一个合法域名
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
            }.onFailure { XplerLog.e("$TAG createIRetrofitFactorybyMonsterPlugin 失败: ${it.message}") }

            runCatching {
                factoryClass.getMethod("createNewServiceImplInstanceForSPI")
                    .invoke(null)
                    ?.let { return it }
            }.onFailure { XplerLog.e("$TAG createNewServiceImplInstanceForSPI 失败: ${it.message}") }

            runCatching {
                factoryClass.getConstructor().newInstance()
                    ?.let { return it }
            }.onFailure { XplerLog.e("$TAG 实例化 RetrofitFactory 失败: ${it.message}") }
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
            }.onFailure { XplerLog.e("$TAG ServiceManager 取 IRetrofitFactory 失败: ${it.message}") }

            runCatching {
                val monster = loadClass("com.ss.android.ugc.MonsterSingleTon", loader)
                monster?.getMethod("getServiceFromMap", Class::class.java, Boolean::class.javaPrimitiveType)
                    ?.invoke(null, factoryInterface, false)
                    ?.let { return it }
            }.onFailure { XplerLog.e("$TAG MonsterSingleTon 取 IRetrofitFactory 失败: ${it.message}") }
        }

        return null
    }

    private fun createRetrofit(factory: Any, host: String): Any? {
        val factoryInterface = loadClass("com.bytedance.ies.ugc.aweme.network.IRetrofitFactory", factory.javaClass.classLoader)

        // 首选 create(String)，内部就是 createBuilder(host).build()
        if (factoryInterface != null) {
            runCatching {
                factoryInterface.getMethod("create", String::class.java).invoke(factory, host)
                    ?.let { return it }
            }.onFailure { XplerLog.e("$TAG IRetrofitFactory.create 失败: ${it.message}") }
        }

        // 兜底：createBuilder(host) -> build()
        val builder = runCatching {
            if (factoryInterface != null) {
                factoryInterface.getMethod("createBuilder", String::class.java).invoke(factory, host)
            } else {
                factory.javaClass.getMethod("createBuilder", String::class.java).invoke(factory, host)
            }
        }.getOrElse {
            XplerLog.e("$TAG createBuilder 失败: ${it.javaClass.name}: ${it.message}")
            return null
        } ?: return null

        val builderInterface = loadClass("com.bytedance.ies.ugc.aweme.network.IBuilder", builder.javaClass.classLoader)
        val retrofit = runCatching {
            if (builderInterface != null) {
                builderInterface.getMethod("build").invoke(builder)
            } else {
                builder.javaClass.getMethod("build").invoke(builder)
            }
        }.getOrElse {
            XplerLog.e("$TAG IBuilder.build 失败: ${it.javaClass.name}: ${it.message}")
            return null
        }

        return retrofit
    }

    private fun createService(retrofit: Any, clazz: Class<*>): Any? {
        val retrofitInterface = loadClass("com.bytedance.ies.ugc.aweme.network.IRetrofit", retrofit.javaClass.classLoader)
        if (retrofitInterface != null) {
            return retrofitInterface.getMethod("create", Class::class.java).invoke(retrofit, clazz)
        }
        return retrofit.javaClass.getMethod("create", Class::class.java).invoke(retrofit, clazz)
    }
}
