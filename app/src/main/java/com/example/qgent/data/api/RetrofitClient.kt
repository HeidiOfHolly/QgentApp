package com.example.qgent.data.api

import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

object RetrofitClient {

    const val BASE_URL = "http://47.113.224.195:32500/api/v1/"

    private val loggingInterceptor = HttpLoggingInterceptor().apply {
        level = HttpLoggingInterceptor.Level.BODY
    }

    // 刷新令牌专用 client：不带鉴权拦截器与 Authenticator，避免刷新请求触发递归
    private val refreshHttpClient = OkHttpClient.Builder()
        .addInterceptor(loggingInterceptor)
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val refreshService: QgApiService by lazy {
        Retrofit.Builder()
            .baseUrl(BASE_URL)
            .client(refreshHttpClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(QgApiService::class.java)
    }

    // 带鉴权拦截器的通用 client：供 Retrofit service 与附件下载（GET content.url）共用
    val httpClient: OkHttpClient = OkHttpClient.Builder()
        .addInterceptor(AuthInterceptor())
        .authenticator(TokenAuthenticator(refreshService))
        .addInterceptor(loggingInterceptor)
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    // 预签名 PUT 直传专用：不带鉴权拦截器（签名已在 URL 中）
    val uploadClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    /** content.url 相对路径 → 绝对地址；本地 uri / http 原样返回 */
    fun resolveMediaUrl(path: String): String = when {
        path.startsWith("content://") || path.startsWith("http") -> path
        else -> BASE_URL.trimEnd('/') + (if (path.startsWith("/")) path else "/$path")
    }

    val service: QgApiService by lazy {
        Retrofit.Builder()
            .baseUrl(BASE_URL)
            .client(httpClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(QgApiService::class.java)
    }
}
