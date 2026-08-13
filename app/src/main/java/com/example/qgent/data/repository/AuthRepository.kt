package com.example.qgent.data.repository

import com.example.qgent.data.api.RetrofitClient
import com.example.qgent.data.crypto.PasswordEncryptor
import com.example.qgent.data.model.AuthSessionDto
import com.example.qgent.data.model.LoginRequest
import com.example.qgent.data.model.RegisterRequest
import com.example.qgent.data.model.requireData

/**
 * 认证仓库：使用硬编码的固定 RSA 公钥加密密码后提交。
 * keyId 固定为 "rsa-2026-08"，不再调用 /auth/password-public-key。
 */
class AuthRepository {

    private val service = RetrofitClient.service

    suspend fun login(email: String, password: String): Result<AuthSessionDto> = runCatching {
        val encrypted = PasswordEncryptor.encrypt(password)
        service.login(LoginRequest(email, PasswordEncryptor.KEY_ID, encrypted)).requireData()
    }

    suspend fun register(email: String, displayName: String, password: String): Result<AuthSessionDto> =
        runCatching {
            val encrypted = PasswordEncryptor.encrypt(password)
            service.register(
                RegisterRequest(email, PasswordEncryptor.KEY_ID, encrypted, displayName)
            ).requireData()
        }
}
