package io.github.fplus.core.hook

import io.github.fplus.core.base.BaseHook
import io.github.fplus.core.config.ConfigV1
import io.github.fplus.core.hook.logic.BatchDebug
import io.github.xpler.core.XplerLog
import io.github.xpler.core.hookBlockRunning
import io.github.xpler.core.proxy.MethodParam
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap

/**
 * 抓 App 自己的网络调用，写到 `Download/Freedom/app_net_tap.log`。
 *
 * 为什么需要：主页作品列表在 31.5.0 上换成了一批我们猜不到的接口/参数，
 * 自己拼参数只会换来 `status=5 参数不合法`。与其继续猜，不如直接记录 App 打开
 * 作者主页时到底调了哪个 retrofit 方法、参数是什么、走哪个域名。
 *
 * 抖音的 Retrofit 是 `com.bytedance.retrofit2.Retrofit`，动态代理的
 * `InvocationHandler` 就是 `Retrofit$1`，所有接口调用都会经过它的
 * `invoke(Object, Method, Object[])`。这里只在方法上带 `/aweme/` 路径时记录，
 * 并跳过信息流之类的高频接口，避免日志被刷爆。
 */
class HRetrofitProxyTap : BaseHook() {
    companion object {
        /** 每次请求都经过这里，反射注解很贵，按 Method 缓存路径（空串表示不关心） */
        private val pathCache = ConcurrentHashMap<Method, String>()

        /** 每个 handler 对应一个 Retrofit 实例，baseUrl 不会变 */
        private val baseUrlCache = ConcurrentHashMap<Any, String>()

        /** 这些接口频率高且和作品列表无关，记下来只会把有用的日志挤掉 */
        private val IGNORED_PATHS = arrayOf(
            "/aweme/v1/feed/",
            "/aweme/v1/aweme/recommend/",
            "/aweme/v1/aweme/related/",
        )

        private const val MAX_ARG_CHARS = 600

        private fun httpPath(method: Method): String {
            pathCache[method]?.let { return it }
            var found = ""
            for (annotation in method.annotations) {
                val path = annotationPath(annotation) ?: continue
                if (!path.contains("/aweme/")) continue
                found = path
                break
            }
            pathCache[method] = found
            return found
        }

        private fun annotationPath(annotation: Annotation): String? {
            val type = annotation.annotationClass.java
            for (name in arrayOf("value", "path")) {
                val raw = runCatching { type.getMethod(name).invoke(annotation) }.getOrNull() ?: continue
                val path = when (raw) {
                    is String -> raw
                    is Array<*> -> raw.filterIsInstance<String>().firstOrNull().orEmpty()
                    else -> ""
                }
                if (path.isNotBlank()) return path
            }
            return null
        }

        /** handler(`Retrofit$1`) → Retrofit.this$0 → server() → getUrl() */
        private fun baseUrlOf(handler: Any): String {
            baseUrlCache[handler]?.let { return it }
            val url = runCatching {
                val clazz = handler.javaClass
                val field = clazz.declaredFields.firstOrNull { it.name == "this\$0" }
                    ?: clazz.declaredFields.firstOrNull { it.type.name.endsWith(".Retrofit") }
                    ?: return@runCatching ""
                field.isAccessible = true
                val retrofit = field.get(handler) ?: return@runCatching ""
                val server = retrofit.javaClass.getMethod("server").invoke(retrofit)
                    ?: return@runCatching ""
                server.javaClass.getMethod("getUrl").invoke(server) as? String ?: ""
            }.getOrDefault("")
            if (url.isNotEmpty()) baseUrlCache[handler] = url
            return url
        }

        private fun describeArgs(args: Array<Any?>?): String {
            if (args.isNullOrEmpty()) return "[]"
            return args.joinToString(", ", "[", "]") { describeValue(it) }
        }

        private fun describeValue(value: Any?): String {
            if (value == null) return "null"
            val text = when (value) {
                is Map<*, *> -> value.entries.joinToString(", ", "{", "}") { "${it.key}=${it.value}" }
                is Array<*> -> value.joinToString(", ", "[", "]") { it?.toString().orEmpty() }
                is Collection<*> -> value.joinToString(", ", "[", "]") { it?.toString().orEmpty() }
                else -> value.toString()
            }
            return if (text.length > MAX_ARG_CHARS) text.take(MAX_ARG_CHARS) + "…" else text
        }
    }

    override fun setTargetClass(): Class<*> {
        return findClass("com.bytedance.retrofit2.Retrofit\$1")
    }

    @OnBefore("invoke")
    fun invokeBefore(
        params: MethodParam,
        @Param("java.lang.Object") proxy: Any?,
        method: Method?,
        args: Array<Any?>?,
    ) {
        hookBlockRunning(params) {
            // 开关关闭时直接返回, 避免每次请求都做反射和日志拼接
            if (!ConfigV1.get().isNetTapLog) return@hookBlockRunning
            val realMethod = method ?: return@hookBlockRunning
            val path = httpPath(realMethod)
            if (path.isEmpty()) return@hookBlockRunning
            if (IGNORED_PATHS.any { path.contains(it) }) return@hookBlockRunning

            val handler = thisObject ?: proxy?.let {
                runCatching { java.lang.reflect.Proxy.getInvocationHandler(it) }.getOrNull()
            }
            val baseUrl = handler?.let { baseUrlOf(it) }.orEmpty()

            BatchDebug.tap(
                "${realMethod.declaringClass.name}#${realMethod.name} $path @${baseUrl.ifEmpty { "?" }} " +
                        "args=${describeArgs(args)}"
            )
        }.onFailure {
            XplerLog.e(it)
        }
    }
}
