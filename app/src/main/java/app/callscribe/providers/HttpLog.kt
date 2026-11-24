package app.callscribe.providers

import app.callscribe.Logx
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response

/**
 * Logs every HTTP call the app makes: method, host, the last path segment (e.g. "run", "status", "health"),
 * status code and time. Never the full URL (it can carry endpoint ids or keys), headers or bodies.
 */
object HttpLog : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val req = chain.request()
        val what = "${req.method} ${req.url.host} …/${req.url.pathSegments.lastOrNull()?.take(20).orEmpty()}"
        val t0 = System.nanoTime()
        return try {
            chain.proceed(req).also { r ->
                val ms = (System.nanoTime() - t0) / 1_000_000
                if (r.isSuccessful) Logx.d("Http", "$what → ${r.code} in $ms ms")
                else Logx.w("Http", "$what → HTTP ${r.code} in $ms ms")
            }
        } catch (e: java.io.IOException) {
            Logx.w("Http", "$what → ${e.javaClass.simpleName}: ${e.message}")
            throw e
        }
    }

    /** A client builder with logging already attached. */
    fun builder(): OkHttpClient.Builder = OkHttpClient.Builder().addInterceptor(HttpLog)
}
