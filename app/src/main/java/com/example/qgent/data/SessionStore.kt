package com.example.qgent.data

import android.content.Context
import android.content.SharedPreferences
import com.example.qgent.data.api.AuthInterceptor
import com.example.qgent.data.model.AuthSessionDto
import com.example.qgent.data.model.AuthUserDto

/**
 * 会话持久化：登录成功后保存 accessToken / refreshToken / 用户信息，
 * 应用启动时恢复并注入 [AuthInterceptor]，退出登录时清空。
 */
object SessionStore {

    private const val PREFS_NAME = "qgent_session"
    private const val KEY_ACCESS_TOKEN = "access_token"
    private const val KEY_REFRESH_TOKEN = "refresh_token"
    private const val KEY_USER_ID = "user_id"
    private const val KEY_USER_EMAIL = "user_email"
    private const val KEY_USER_DISPLAY_NAME = "user_display_name"
    private const val KEY_REMEMBERED_EMAIL = "remembered_email"

    private var prefs: SharedPreferences? = null

    fun init(context: Context) {
        prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    private fun requirePrefs(): SharedPreferences =
        prefs ?: throw IllegalStateException("SessionStore 未初始化，请先在 Application.onCreate 调用 init")

    /** 保存登录会话并注入拦截器 */
    fun saveSession(session: AuthSessionDto) {
        requirePrefs().edit()
            .putString(KEY_ACCESS_TOKEN, session.accessToken)
            .putString(KEY_REFRESH_TOKEN, session.refreshToken)
            .putString(KEY_USER_ID, session.user.id)
            .putString(KEY_USER_EMAIL, session.user.email)
            .putString(KEY_USER_DISPLAY_NAME, session.user.displayName)
            .apply()
        AuthInterceptor.setToken(session.accessToken)
    }

    /** 应用启动时恢复 token 到拦截器 */
    fun restore() {
        accessToken()?.let { AuthInterceptor.setToken(it) }
    }

    fun clear() {
        requirePrefs().edit().clear().apply()
        AuthInterceptor.setToken(null)
    }

    /** 刷新后仅更新 token（不动用户信息，刷新响应可能不含 user 字段） */
    fun updateTokens(accessToken: String, refreshToken: String?) {
        val editor = requirePrefs().edit().putString(KEY_ACCESS_TOKEN, accessToken)
        if (refreshToken != null) editor.putString(KEY_REFRESH_TOKEN, refreshToken)
        editor.apply()
        AuthInterceptor.setToken(accessToken)
    }

    fun accessToken(): String? = requirePrefs().getString(KEY_ACCESS_TOKEN, null)
    fun refreshToken(): String? = requirePrefs().getString(KEY_REFRESH_TOKEN, null)

    fun user(): AuthUserDto? {
        val id = requirePrefs().getString(KEY_USER_ID, null) ?: return null
        val email = requirePrefs().getString(KEY_USER_EMAIL, "") ?: ""
        val displayName = requirePrefs().getString(KEY_USER_DISPLAY_NAME, "") ?: ""
        return AuthUserDto(id, email, displayName)
    }

    fun isLoggedIn(): Boolean = !accessToken().isNullOrEmpty()

    fun rememberedEmail(): String? = requirePrefs().getString(KEY_REMEMBERED_EMAIL, null)

    fun saveRememberedEmail(email: String) {
        requirePrefs().edit().putString(KEY_REMEMBERED_EMAIL, email).apply()
    }
}
