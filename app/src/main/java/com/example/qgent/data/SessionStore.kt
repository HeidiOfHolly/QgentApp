package com.example.qgent.data

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import com.example.qgent.data.model.AuthSessionDto
import com.example.qgent.data.model.AuthUserDto
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * 会话持久化：登录成功后保存 accessToken / refreshToken / 用户信息，
 * 退出登录时清空。token 单一数据源，AuthInterceptor 每次请求直接读取。
 * 记住密码：密码经 Android Keystore 保护的 AES/GCM 密钥加密后存储（避免明文落盘），
 * 仅用于登录页回填，不参与请求鉴权。
 */
object SessionStore {

    private const val PREFS_NAME = "qgent_session"
    private const val KEY_ACCESS_TOKEN = "access_token"
    private const val KEY_REFRESH_TOKEN = "refresh_token"
    private const val KEY_USER_ID = "user_id"
    private const val KEY_USER_EMAIL = "user_email"
    private const val KEY_USER_DISPLAY_NAME = "user_display_name"
    private const val KEY_USER_AVATAR = "user_avatar"
    private const val KEY_REMEMBERED_EMAIL = "remembered_email"
    private const val KEY_REMEMBERED_PASSWORD = "remembered_password"
    private const val KEYSTORE_ALIAS = "qgent_remembered_password"
    private const val GCM_IV_LENGTH = 12

    private var prefs: SharedPreferences? = null

    fun init(context: Context) {
        prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    private fun requirePrefs(): SharedPreferences =
        prefs ?: throw IllegalStateException("SessionStore 未初始化，请先在 Application.onCreate 调用 init")

    /** 保存登录会话（拦截器直接读 SharedPreferences，无需手工同步）；新会话复位过期通知 */
    fun saveSession(session: AuthSessionDto) {
        requirePrefs().edit()
            .putString(KEY_ACCESS_TOKEN, session.accessToken)
            .putString(KEY_REFRESH_TOKEN, session.refreshToken)
            .putString(KEY_USER_ID, session.user.id)
            .putString(KEY_USER_EMAIL, session.user.email)
            .putString(KEY_USER_DISPLAY_NAME, session.user.displayName)
            .putString(KEY_USER_AVATAR, session.user.avatarUrl)
            .apply()
        SessionExpiryNotifier.reset()
    }

    /** 头像上传成功后更新本地头像地址 */
    fun updateAvatar(avatarUrl: String?) {
        requirePrefs().edit().putString(KEY_USER_AVATAR, avatarUrl).apply()
    }

    fun clear() {
        requirePrefs().edit().clear().apply()
    }

    /** 刷新后仅更新 token（不动用户信息，刷新响应可能不含 user 字段） */
    fun updateTokens(accessToken: String, refreshToken: String?) {
        val editor = requirePrefs().edit().putString(KEY_ACCESS_TOKEN, accessToken)
        if (refreshToken != null) editor.putString(KEY_REFRESH_TOKEN, refreshToken)
        editor.apply()
    }

    fun accessToken(): String? = requirePrefs().getString(KEY_ACCESS_TOKEN, null)
    fun refreshToken(): String? = requirePrefs().getString(KEY_REFRESH_TOKEN, null)

    fun user(): AuthUserDto? {
        val id = requirePrefs().getString(KEY_USER_ID, null) ?: return null
        val email = requirePrefs().getString(KEY_USER_EMAIL, "") ?: ""
        val displayName = requirePrefs().getString(KEY_USER_DISPLAY_NAME, "") ?: ""
        val avatarUrl = requirePrefs().getString(KEY_USER_AVATAR, null)
        return AuthUserDto(id, email, displayName, avatarUrl)
    }

    fun isLoggedIn(): Boolean = !accessToken().isNullOrEmpty()

    fun rememberedEmail(): String? = requirePrefs().getString(KEY_REMEMBERED_EMAIL, null)

    fun saveRememberedEmail(email: String) {
        requirePrefs().edit().putString(KEY_REMEMBERED_EMAIL, email).apply()
    }

    /** 记住密码（仅勾选"记住密码"时调用）：AES/GCM 加密后存储，避免明文落盘 */
    fun saveRememberedPassword(password: String) {
        val encrypted = encryptRemembered(password)
        requirePrefs().edit().putString(KEY_REMEMBERED_PASSWORD, encrypted).apply()
    }

    /** 取回记住的密码明文（登录页回填用）；未记住或解密失败返回 null */
    fun rememberedPassword(): String? {
        val stored = requirePrefs().getString(KEY_REMEMBERED_PASSWORD, null) ?: return null
        return decryptRemembered(stored)
    }

    /** 清除记住的密码（取消勾选 / 退出登录时） */
    fun clearRememberedPassword() {
        requirePrefs().edit().remove(KEY_REMEMBERED_PASSWORD).apply()
    }

    // ── Keystore AES/GCM：密码加密存储 ──

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(KEYSTORE_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                KEYSTORE_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build()
        )
        return generator.generateKey()
    }

    private fun encryptRemembered(plain: String): String? {
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
            val cipherBytes = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
            // 密文前缀 12 字节 IV，解密时截取
            val iv = cipher.iv
            Base64.encodeToString(iv + cipherBytes, Base64.NO_WRAP)
        } catch (e: Exception) {
            Log.w("SessionStore", "加密记住密码失败: ${e.message}")
            null
        }
    }

    private fun decryptRemembered(encoded: String): String? {
        return try {
            val raw = Base64.decode(encoded, Base64.NO_WRAP)
            if (raw.size <= GCM_IV_LENGTH) return null
            val iv = raw.copyOfRange(0, GCM_IV_LENGTH)
            val cipherBytes = raw.copyOfRange(GCM_IV_LENGTH, raw.size)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(128, iv))
            cipher.doFinal(cipherBytes).toString(Charsets.UTF_8)
        } catch (e: Exception) {
            // 密钥失效（如系统备份恢复后 Keystore 丢失）等解密失败：清除残留密文
            Log.w("SessionStore", "解密记住密码失败: ${e.message}")
            clearRememberedPassword()
            null
        }
    }
}
