package com.example.qgent.data.api

import com.example.qgent.data.SessionStore
import okhttp3.Interceptor
import okhttp3.Response

/**
 * 为需要认证的请求注入 Bearer token。
 * token 统一从 [SessionStore]（SharedPreferences）读取，单一数据源，无需手工同步。
 */
class AuthInterceptor : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()
        val tokenValue = SessionStore.accessToken()
        if (tokenValue.isNullOrEmpty()) return chain.proceed(original)

        val request = original.newBuilder()
            .header("Authorization", "Bearer $tokenValue")
            .build()
        return chain.proceed(request)
    }
}
