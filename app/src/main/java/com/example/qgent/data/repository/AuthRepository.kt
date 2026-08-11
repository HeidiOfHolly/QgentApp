package com.example.qgent.data.repository

import com.example.qgent.data.api.RetrofitClient
import com.example.qgent.data.crypto.PasswordEncryptor
import com.example.qgent.data.model.AuthSessionDto
import com.example.qgent.data.model.LoginRequest
import com.example.qgent.data.model.PasswordPublicKeyDto
import com.example.qgent.data.model.RegisterRequest
import com.example.qgent.data.model.requireData

/**
 * 认证仓库：登录 / 注册前先获取平台 RSA 公钥，加密密码后再提交，避免明文出现在请求体。
 */
class AuthRepository {

    private val service = RetrofitClient.service

    suspend fun getPublicKey(): Result<PasswordPublicKeyDto> = runCatching {
        service.getPasswordPublicKey().requireData()
    }

    suspend fun login(email: String, password: String): Result<AuthSessionDto> =
        callWithEncryptedPassword(email, password) { encrypted, keyId ->
            service.login(LoginRequest(email, keyId, encrypted)).requireData()
        }

    suspend fun register(email: String, displayName: String, password: String): Result<AuthSessionDto> =
        callWithEncryptedPassword(email, password) { encrypted, keyId ->
            service.register(RegisterRequest(email, keyId, encrypted, displayName)).requireData()
        }

    private suspend fun <T> callWithEncryptedPassword(
        email: String,
        password: String,
        request: suspend (encrypted: String, keyId: String) -> T
    ): Result<T> {
        val key = getPublicKey().getOrElse { return Result.failure(it) }
        val encrypted = PasswordEncryptor.encrypt(key.publicKeyPem, password)
        return runCatching { request(encrypted, key.keyId) }
    }
}
