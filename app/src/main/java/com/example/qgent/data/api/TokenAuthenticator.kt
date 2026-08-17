package com.example.qgent.data.api

import com.example.qgent.data.SessionExpiryNotifier
import com.example.qgent.data.SessionStore
import com.example.qgent.data.model.RefreshRequest
import com.example.qgent.data.model.toDataOrThrow
import kotlinx.coroutines.runBlocking
import okhttp3.Authenticator
import okhttp3.Request
import okhttp3.Response
import okhttp3.Route
import java.io.IOException

/**
 * 收到 401 时用 refresh token 换新 access token 并重试原请求。
 * 刷新请求走独立的 refreshService（无鉴权拦截器 / Authenticator），避免递归死循环。
 */
class TokenAuthenticator(
    private val refreshService: QgApiService
) : Authenticator {

    private val lock = Any()

    override fun authenticate(route: Route?, response: Response): Request? {
        // 刷新接口自身的 401 不再处理，避免死循环
        if (response.request.url.encodedPath.endsWith("/auth/refresh")) return null

        val refreshToken = SessionStore.refreshToken()
        if (refreshToken.isNullOrEmpty()) return null

        synchronized(lock) {
            // 并发 401：若期间已有线程刷新成功，直接复用新 token 重试，不重复刷新
            val used = response.request.header("Authorization")?.removePrefix("Bearer ")
            val current = SessionStore.accessToken()
            if (!current.isNullOrEmpty() && current != used) {
                return response.request.newBuilder()
                    .header("Authorization", "Bearer $current")
                    .build()
            }

            val result = runBlocking {
                runCatching { refreshService.refresh(RefreshRequest(refreshToken)).toDataOrThrow() }
            }
            val newAccess = result.getOrNull()?.accessToken
            if (newAccess.isNullOrEmpty()) {
                // refresh 失败：网络异常（IO）不视为过期，静默等待下次重试；
                // 其余（401 / refresh token 失效 / 服务端拒绝）判定会话过期，触发自动退出登录
                if (result.exceptionOrNull() !is IOException) {
                    SessionExpiryNotifier.notifyExpired()
                }
                return null
            }
            SessionStore.updateTokens(newAccess, result.getOrNull()?.refreshToken)
            return response.request.newBuilder()
                .header("Authorization", "Bearer $newAccess")
                .build()
        }
    }
}
