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
                runCatching { refreshService.refresh(RefreshRequest(refreshToken)) }
            }
            val refreshResponse = result.getOrNull() ?: return null
            if (!refreshResponse.isSuccessful) {
                if (refreshResponse.code() == 401) {
                    SessionExpiryNotifier.notifyExpired()
                }
                return null
            }
            val refreshed = runCatching { refreshResponse.toDataOrThrow() }.getOrNull()
            val newAccess = refreshed?.accessToken
            if (newAccess.isNullOrEmpty()) {
                // A malformed successful refresh response is retriable; retain local user data.
                return null
            }
            SessionStore.updateTokens(newAccess, refreshed?.refreshToken)
            return response.request.newBuilder()
                .header("Authorization", "Bearer $newAccess")
                .build()
        }
    }
}
