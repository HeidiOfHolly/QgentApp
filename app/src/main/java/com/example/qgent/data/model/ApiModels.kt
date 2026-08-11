package com.example.qgent.data.model

import com.google.gson.annotations.SerializedName

/** API 统一成功响应：{ data, requestId, page } */
data class ApiResponse<T>(
    val data: T?,
    val requestId: String?,
    val error: ApiError?,
    val page: PageInfo?
)

/** API 错误：{ code, message, details } */
data class ApiError(
    val code: String,
    val message: String,
    val details: List<String>?
)

/** 游标分页信息 */
data class PageInfo(
    val nextCursor: String?,
    val hasMore: Boolean
)

/** API 异常：包含服务端返回的错误码与提示 */
class ApiException(
    val code: String,
    override val message: String
) : Exception("[$code] $message")

/** 解析统一响应：优先抛服务端错误，其次要求 data 非空 */
fun <T> ApiResponse<T>.requireData(): T {
    error?.let { throw ApiException(it.code, it.message) }
    return data ?: throw ApiException("EMPTY_RESPONSE", "响应为空")
}

// ── 认证 DTO ──

/** 注册/登录前获取的 RSA 公钥 */
data class PasswordPublicKeyDto(
    @SerializedName("keyId") val keyId: String,
    val algorithm: String,
    @SerializedName("publicKeyPem") val publicKeyPem: String
)

/** 注册请求：密码需用平台 RSA 公钥加密后 Base64 */
data class RegisterRequest(
    val email: String,
    @SerializedName("passwordKeyId") val passwordKeyId: String,
    val password: String,
    @SerializedName("displayName") val displayName: String
)

/** 登录请求：密码需用平台 RSA 公钥加密后 Base64 */
data class LoginRequest(
    val email: String,
    @SerializedName("passwordKeyId") val passwordKeyId: String,
    val password: String
)

/** 登录/注册成功后的会话 */
data class AuthSessionDto(
    @SerializedName("accessToken") val accessToken: String,
    @SerializedName("accessTokenExpiresIn") val accessTokenExpiresIn: Long,
    @SerializedName("refreshToken") val refreshToken: String,
    @SerializedName("refreshTokenExpiresIn") val refreshTokenExpiresIn: Long,
    val user: AuthUserDto
)

data class AuthUserDto(
    val id: String,
    val email: String,
    @SerializedName("displayName") val displayName: String
)

// ── 业务 DTO ──

data class UserProfileDto(
    val id: String,
    val nickname: String,
    val email: String,
    val avatar: String?,
    @SerializedName("githubLinked") val githubLinked: Boolean
)

data class TeamDto(
    val id: String,
    val name: String,
    val role: String,           // TEAM_OWNER / TEAM_MEMBER
    @SerializedName("memberCount") val memberCount: Int,
    @SerializedName("createdAt") val createdAt: String
)

data class ProjectDto(
    val id: String,
    @SerializedName("teamId") val teamId: String,
    val name: String,
    val description: String?,
    val status: String
)

data class RequirementGroupDto(
    val id: String,
    @SerializedName("projectId") val projectId: String,
    val name: String,
    @SerializedName("memberCount") val memberCount: Int,
    @SerializedName("lastMessage") val lastMessage: String?,
    @SerializedName("lastMessageSender") val lastMessageSender: String?,
    @SerializedName("updatedAt") val updatedAt: String
)

data class GroupMessageDto(
    val id: String,
    @SerializedName("groupId") val groupId: String,
    @SerializedName("senderId") val senderId: String,
    @SerializedName("senderName") val senderName: String,
    val content: String,
    val type: String,           // TEXT / IMAGE / FILE
    @SerializedName("createdAt") val createdAt: String
)
