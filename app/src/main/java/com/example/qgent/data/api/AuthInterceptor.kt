package com.example.qgent.data.api

import okhttp3.Interceptor
import okhttp3.Response

/**
 * 为需要认证的请求注入 Bearer token。
 * token 由外部设置（登录成功后由 A 方调用 [setToken]）。
 */
class AuthInterceptor : Interceptor {

    companion object {
        @Volatile
        private var token: String? = null

        fun setToken(token: String?) {
            Companion.token = token
        }

        fun getToken(): String? = token
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()
        val tokenValue = token
        if (tokenValue.isNullOrEmpty()) return chain.proceed(original)

        val request = original.newBuilder()
            .header("Authorization", "Bearer $tokenValue")
            .build()
        return chain.proceed(request)
    }
}
