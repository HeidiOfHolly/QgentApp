package com.example.qgent.data.repository

import com.example.qgent.data.api.QgApiService
import com.example.qgent.data.crypto.PasswordEncryptor
import com.example.qgent.data.model.AuthSessionDto
import com.example.qgent.data.model.LoginRequest
import com.example.qgent.data.model.PasswordResetSubmitRequest
import com.example.qgent.data.model.RegisterRequest
import com.example.qgent.data.model.SendVerificationCodeRequest
import com.example.qgent.data.model.SendVerificationCodeResponse
import com.example.qgent.data.model.toDataOrThrow
import com.example.qgent.data.model.toUnitOrThrow

/**
 * 认证仓库：使用硬编码的固定 RSA 公钥加密密码后提交。
 * keyId 固定为 "rsa-2026-08"，不再调用 /auth/password-public-key。
 * 注册为两步流程（v2.0.6 §11）：先 [sendRegisterVerificationCode] 发邮箱验证码，再 [register] 带码注册。
 */
class AuthRepository(private val service: QgApiService) {

    suspend fun login(email: String, password: String): Result<AuthSessionDto> = apiCall {
        val encrypted = PasswordEncryptor.encrypt(password)
        service.login(LoginRequest(email, PasswordEncryptor.KEY_ID, encrypted)).toDataOrThrow()
    }

    /** 发送注册邮箱验证码（v2.0.6 §11.1）：6 位数字、10 分钟有效、一次性使用 */
    suspend fun sendRegisterVerificationCode(email: String): Result<SendVerificationCodeResponse> = apiCall {
        service.sendRegisterVerificationCode(SendVerificationCodeRequest(email.trim())).toDataOrThrow()
    }

    /** 带邮箱验证码注册（v2.0.6 §11.2）：verificationCode 为必填 6 位码，注册失败重试需重新获取验证码 */
    suspend fun register(email: String, displayName: String, password: String, verificationCode: String): Result<AuthSessionDto> =
        apiCall {
            val encrypted = PasswordEncryptor.encrypt(password)
            service.register(
                RegisterRequest(email, verificationCode, PasswordEncryptor.KEY_ID, encrypted, displayName)
            ).toDataOrThrow()
        }

    /** 发起密码重置：向邮箱发送 6 位验证码（§11.3：匿名，未注册邮箱同样返回 202，规避枚举） */
    suspend fun sendPasswordResetCode(email: String): Result<SendVerificationCodeResponse> = apiCall {
        service.sendPasswordResetCode(SendVerificationCodeRequest(email.trim())).toDataOrThrow()
    }

    /** 用邮箱验证码设置新密码（§11.3：token 即验证码，30 分钟有效、一次性；校验失败 422 INVALID_RESET_TOKEN） */
    suspend fun resetPassword(token: String, newPassword: String): Result<Unit> = apiCall {
        val encrypted = PasswordEncryptor.encrypt(newPassword)
        service.resetPassword(PasswordResetSubmitRequest(token, encrypted, PasswordEncryptor.KEY_ID)).toUnitOrThrow()
    }
}
