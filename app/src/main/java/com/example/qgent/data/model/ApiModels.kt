package com.example.qgent.data.model

import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import retrofit2.Response

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

// ── retrofit2.Response 解析（错误契约：非 2xx / error 分支统一转 ApiException） ──

private val errorGson = Gson()

private data class ErrorEnvelope(val error: ApiError?)

private fun Response<*>.httpError(): ApiError? = try {
    errorBody()?.string()?.let { errorGson.fromJson(it, ErrorEnvelope::class.java)?.error }
} catch (_: Exception) {
    null
}

private fun Response<*>.throwHttpError(): Nothing {
    val e = httpError()
    throw ApiException(e?.code ?: "HTTP_${code()}", e?.message ?: "请求失败 (${code()})")
}

/** 非空 data 响应：成功且 data 非空时返回 data，否则抛 [ApiException] */
fun <T> Response<ApiResponse<T>>.toDataOrThrow(): T {
    if (!isSuccessful) throwHttpError()
    val body = body()
    body?.error?.let { throw ApiException(it.code, it.message) }
    return body?.data ?: throw ApiException("EMPTY_RESPONSE", "响应为空")
}

/** 空 body / 204 响应：仅校验成功，无返回值 */
fun Response<*>.toUnitOrThrow() {
    if (!isSuccessful) throwHttpError()
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

// ── 群（Group）— 统一建模：PROJECT_MAIN + REQUIREMENT ──

data class GroupDto(
    val id: String,
    @SerializedName("projectId") val projectId: String,
    val title: String,
    val description: String?,
    val type: String,               // PROJECT_MAIN / REQUIREMENT
    val status: String,             // ACTIVE / ARCHIVED
    @SerializedName("memberCount") val memberCount: Int,
    @SerializedName("repositoryIds") val repositoryIds: List<String>?,
    @SerializedName("lastMessage") val lastMessage: String?,
    @SerializedName("lastMessageSender") val lastMessageSender: String?,
    @SerializedName("updatedAt") val updatedAt: String
)

data class GroupMemberDto(
    val id: String,
    val nickname: String,
    val avatar: String?
)

// ── 消息 ──

data class GroupMessageDto(
    val id: String,
    @SerializedName("groupId") val groupId: String,
    @SerializedName("senderId") val senderId: String,
    @SerializedName("senderName") val senderName: String,
    val type: String,               // TEXT / CODE / IMAGE / FILE / SYSTEM / QUOTE
    val content: MessageContentDto?,
    val mentions: List<String>?,
    @SerializedName("replyToId") val replyToId: String?,
    @SerializedName("clientMessageId") val clientMessageId: String?,
    val sequence: Long,
    @SerializedName("createdAt") val createdAt: String
)

data class MessageContentDto(
    val text: String?
)

// ── 请求体 ──

data class CreateGroupRequest(
    val title: String,
    val description: String? = null,
    @SerializedName("repositoryIds") val repositoryIds: List<String>? = null,
    val type: String = "REQUIREMENT"
)

data class UpdateGroupRequest(
    val title: String? = null,
    val description: String? = null,
    @SerializedName("repositoryIds") val repositoryIds: List<String>? = null
)

data class SendMessageRequest(
    val type: String,
    val content: MessageContentDto,
    val mentions: List<String>? = null,
    @SerializedName("replyToId") val replyToId: String? = null,
    @SerializedName("clientMessageId") val clientMessageId: String? = null
)

// ── Agent（§11）──

/** Agent 身份卡（GET/PATCH /teams/{teamId}/agents/{agentId} 返回） */
data class AgentDto(
    val id: String,
    val name: String,
    val avatar: String?,
    val role: String,                   // ORCHESTRATOR / PLANNER / DEVELOPER / TESTER / REVIEWER / GENERAL
    val capabilities: List<String>?,
    val prompt: String?,                // 私有提示词，仅创建者可见
    val visibility: String,             // PRIVATE / TEAM_SHARED
    val status: String,                 // ACTIVE / ARCHIVED
    @SerializedName("createdBy") val createdBy: String
)

/** 创建 Agent（POST /teams/{teamId}/agents） */
data class CreateAgentRequest(
    val name: String,
    val avatar: String? = null,
    val role: String,
    val capabilities: List<String>? = null,
    val prompt: String? = null
)

/** 更新 Agent（PATCH /teams/{teamId}/agents/{agentId}） */
data class UpdateAgentRequest(
    val name: String? = null,
    val avatar: String? = null,
    val capabilities: List<String>? = null,
    val prompt: String? = null
)

/** 为 Agent 绑定当前项目 Skill（PUT /projects/{projectId}/agent-skill-bindings/{agentId}） */
data class AgentSkillBindingsRequest(
    @SerializedName("skillIds") val skillIds: List<String>
)
