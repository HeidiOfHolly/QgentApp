package com.example.qgent.data.api

import com.example.qgent.BuildConfig
import com.example.qgent.data.SessionStore
import com.example.qgent.data.model.RefreshRequest
import com.example.qgent.data.model.toDataOrThrow
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

object RetrofitClient {

    // Retrofit 要求 baseUrl 以 "/" 结尾（否则启动抛 IllegalArgumentException）
    const val BASE_URL = "https://api.qgents.dpdns.org/api/v1/"

    // 请求日志：debug 只打请求行/响应行（BASIC），不再完整记录响应体——
    // BODY 级会让 OkHttp 先把大响应体（群列表/消息列表）完整读一遍再交给 Gson 解析，
    // 高频轮询/事件下拖慢所有请求处理；release 完全关闭。
    private val loggingInterceptor = HttpLoggingInterceptor().apply {
        level = if (BuildConfig.DEBUG) HttpLoggingInterceptor.Level.BASIC else HttpLoggingInterceptor.Level.NONE
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

    /** 主动刷新 access token（WebSocket 握手 401 用：WS 的 token 在 query，Authenticator 改不了），
     *  成功写回 SessionStore；refresh 也失败返回 false（交给上层退避重试） */
    suspend fun refreshAccessToken(): Boolean {
        val refreshToken = SessionStore.refreshToken() ?: return false
        return runCatching {
            refreshService.refresh(RefreshRequest(refreshToken)).toDataOrThrow()
        }.map { r ->
            SessionStore.updateTokens(r.accessToken, r.refreshToken)
            true
        }.getOrDefault(false)
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

    /**
     * content.url 相对路径 → 绝对地址；本地 uri 原样返回。
     * 附件鉴权代理 URL（/projects/{id}/attachments/{id}/content）即使存的是完整地址
     * （如 web 开发环境存的 http://localhost:8080/...），也提取路径段用本端 BASE_URL 重建，
     * 保证「发送方环境地址」对接收方可访问；其余完整 URL（头像 OSS 直链等）原样返回。
     */
    fun resolveMediaUrl(path: String): String {
        return when {
            path.startsWith("content://") || path.startsWith("file://") -> path
            path.startsWith("http://") || path.startsWith("https://") -> {
                val p = runCatching { android.net.Uri.parse(path).path }.getOrNull() ?: return path
                if (p.contains("/attachments/")) rebuildAttachmentPath(p) else path
            }
            else -> BASE_URL.trimEnd('/') + (if (path.startsWith("/")) path else "/$path")
        }
    }

    /** API base origin（不含 /api/v1 路径），如 https://api.qgents.dpdns.org。
     *  新契约 uploadUrl 为以 /api/v1/ 开头的相对路径（代理上传），须拼 origin 而非 BASE_URL+path
     *  （后者会叠出双 /api/v1）。 */
    fun origin(): String {
        val u = android.net.Uri.parse(BASE_URL)
        val port = u.port
        return u.scheme + "://" + u.host + (if (port != -1) ":$port" else "")
    }

    /** 从路径提取 /projects/... 段并用本端 BASE_URL 重建（附件统一走本端后端地址） */
    private fun rebuildAttachmentPath(path: String): String {
        val segment = if (path.contains("/projects/")) path.substring(path.indexOf("/projects/")) else path
        return BASE_URL.trimEnd('/') + segment
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
