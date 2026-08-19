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

/** 消息分页结果（data + 游标，供聊天页上滑加载更早消息；新消息在前，nextCursor 拉更早） */
data class MessagePageDto(
    val messages: List<GroupMessageDto>,
    val nextCursor: String?,
    val hasMore: Boolean
)

/** API 异常：包含服务端返回的错误码、提示与 requestId（500 等内部错误时展示 requestId 便于后端排查） */
class ApiException(
    val code: String,
    override val message: String,
    val requestId: String? = null,
    val details: List<String>? = null
) : Exception(buildString {
    append("[$code] ").append(message)
    if (!details.isNullOrEmpty()) append(" | ").append(details.joinToString("; "))
})

/** 解析统一响应：优先抛服务端错误，其次要求 data 非空 */
fun <T> ApiResponse<T>.requireData(): T {
    error?.let { throw ApiException(it.code, it.message, requestId, it.details) }
    return data ?: throw ApiException("EMPTY_RESPONSE", "响应为空", requestId)
}

// ── retrofit2.Response 解析（错误契约：非 2xx / error 分支统一转 ApiException） ──

private val errorGson = Gson()

private data class ErrorEnvelope(val error: ApiError?, val requestId: String?)

private fun Response<*>.httpError(): ApiError? = try {
    errorBody()?.string()?.let { errorGson.fromJson(it, ErrorEnvelope::class.java)?.error }
} catch (_: Exception) {
    null
}

private fun Response<*>.httpErrorRequestId(): String? = try {
    errorBody()?.string()?.let { errorGson.fromJson(it, ErrorEnvelope::class.java)?.requestId }
} catch (_: Exception) {
    null
}

private fun Response<*>.throwHttpError(): Nothing {
    val e = httpError()
    throw ApiException(
        code = e?.code ?: "HTTP_${code()}",
        message = e?.message ?: "请求失败 (${code()})",
        requestId = httpErrorRequestId(),
        details = e?.details
    )
}

/** 非空 data 响应：成功且 data 非空时返回 data，否则抛 [ApiException] */
fun <T> Response<ApiResponse<T>>.toDataOrThrow(): T {
    if (!isSuccessful) throwHttpError()
    val body = body()
    body?.error?.let { throw ApiException(it.code, it.message, body.requestId, it.details) }
    return body?.data ?: throw ApiException("EMPTY_RESPONSE", "响应为空", body?.requestId)
}

/** data 可空响应：仅校验 HTTP 成功与业务错误，允许 data 为 null（如"暂无 Diff Review 批次"） */
fun <T> Response<ApiResponse<T>>.toDataOrNull(): T? {
    if (!isSuccessful) throwHttpError()
    val body = body()
    body?.error?.let { throw ApiException(it.code, it.message, body.requestId, it.details) }
    return body?.data
}

/** 空 body / 204 响应：仅校验成功，无返回值 */
fun Response<*>.toUnitOrThrow() {
    if (!isSuccessful) throwHttpError()
}

// ── 认证 DTO ──

/** 注册请求：密码需用平台 RSA 公钥加密后 Base64；v2.0.6 §11 起必填 verificationCode（6 位邮箱验证码） */
data class RegisterRequest(
    val email: String,
    @SerializedName("verificationCode") val verificationCode: String,
    @SerializedName("passwordKeyId") val passwordKeyId: String,
    val password: String,
    @SerializedName("displayName") val displayName: String
)

/** 发送注册邮箱验证码（v2.0.6 §11.1：POST /auth/register/verification-codes，匿名） */
data class SendVerificationCodeRequest(
    val email: String
)

/** 发送注册邮箱验证码响应（v2.0.6 §11.1：{"message":"验证码已发送到邮箱，10 分钟内有效"}） */
data class SendVerificationCodeResponse(
    val message: String? = null
)

/** 密码重置提交（§11.3：token 即 6 位邮箱验证码，30 分钟有效、一次性；校验失败返回 422 INVALID_RESET_TOKEN） */
data class PasswordResetSubmitRequest(
    val token: String,
    @SerializedName("newPassword") val newPassword: String,
    @SerializedName("passwordKeyId") val passwordKeyId: String
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

/** 刷新令牌请求（POST /auth/refresh） */
data class RefreshRequest(
    @SerializedName("refreshToken") val refreshToken: String
)

data class AuthUserDto(
    val id: String,
    val email: String,
    @SerializedName("displayName") val displayName: String,
    @SerializedName("avatarUrl") val avatarUrl: String? = null
)

// ── 头像上传（§7.0 /me/avatar）：credential 签发直传凭证 → OSS PUT → confirm 确认并返回公共读 URL ──

/** 头像直传凭证请求：mediaType 必须为图片 MIME（image 类型），sizeBytes ≤ 5MB；OSS 未启用返回 501 AVATAR_STORAGE_NOT_CONFIGURED */
data class AvatarCredentialRequest(
    @SerializedName("mediaType") val mediaType: String,
    @SerializedName("sizeBytes") val sizeBytes: Long
)

/** 头像直传凭证响应：uploadUrl 为预签名/代理直传地址，objectKey confirm 时原样回传 */
data class AvatarCredentialResponse(
    @SerializedName("uploadUrl") val uploadUrl: String?,
    @SerializedName("objectKey") val objectKey: String?,
    val headers: Map<String, String>? = null
)

/** 头像确认请求（POST /me/avatar/confirm）：objectKey 原样回传，校验对象属于当前用户且已真实上传 */
data class AvatarConfirmRequest(
    @SerializedName("objectKey") val objectKey: String
)

/** 头像确认响应：长期稳定、公共可读的头像 URL */
data class AvatarConfirmResponse(
    @SerializedName("avatarUrl") val avatarUrl: String?
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
    @SerializedName("createdAt") val createdAt: String,
    /** 最后活跃时间（GET /teams/by-last-activity 返回，ISO8601 UTC；其余接口可能不带） */
    @SerializedName("lastActivityAt") val lastActivityAt: String? = null
)

/** 团队成员（GET /teams/{teamId}/members，API-105）：userId + role + displayName + email */
data class TeamMemberDto(
    @SerializedName("userId") val userId: String,
    val role: String,                       // TEAM_OWNER / TEAM_MEMBER
    @SerializedName("displayName") val displayName: String,
    val email: String
)

/** 团队邀请（GET /teams/{teamId}/invitations，§5.1）：email + status + expiresAt */
data class TeamInvitationDto(
    val id: String,
    val email: String,
    val status: String,                 // PENDING / ACCEPTED / REVOKED / EXPIRED
    @SerializedName("expiresAt") val expiresAt: String
)

/** 我收到的团队邀请（GET /team-invitations，§19.2）：id 为邀请记录 UUID，接受时用此值 */
data class ReceivedInvitationDto(
    val id: String,
    @SerializedName("teamId") val teamId: String,
    @SerializedName("teamName") val teamName: String,
    val role: String,                   // 恒为 TEAM_MEMBER
    @SerializedName("inviterDisplayName") val inviterDisplayName: String,
    val status: String,                 // PENDING / EXPIRED
    @SerializedName("expiresAt") val expiresAt: String,
    @SerializedName("createdAt") val createdAt: String
)

/** 创建团队邀请（POST /teams/{teamId}/invitations，§5.1）：按邮箱邀请，role + 有效期必填 */
data class InviteTeamMemberRequest(
    val email: String,
    val role: String,                   // TEAM_MEMBER（当前仅按普通成员邀请）
    @SerializedName("expiresInDays") val expiresInDays: Int
)

/** 创建团队（POST /teams） */
data class CreateTeamRequest(
    val name: String,
    val description: String? = null
)

/** 创建项目（POST /teams/{teamId}/projects）；成员通过 API-069 逐个加入。
 *  newRepository 与 repositoryIds 互斥：传 newRepository 时后端自动建仓并绑定。 */
data class CreateProjectRequest(
    val name: String,
    val description: String? = null,
    @SerializedName("newRepository") val newRepository: NewRepositoryRequest? = null
)

/** 创建项目时自动新建 GitHub 仓库（§22.5 前端清单一） */
data class NewRepositoryRequest(
    val name: String,
    val description: String? = null,
    @SerializedName("isPrivate") val isPrivate: Boolean = true,
    @SerializedName("installationId") val installationId: String? = null,
    @SerializedName("displayName") val displayName: String? = null
)

/** 将团队现有成员加入项目（POST /projects/{projectId}/members），初始 PROJECT_MEMBER */
data class AddProjectMemberRequest(
    @SerializedName("userId") val userId: String
)

/** 调整项目成员角色（PATCH /projects/{projectId}/members/{userId}）：PROJECT_MEMBER / PROJECT_ADMIN */
data class UpdateProjectMemberRequest(
    val role: String
)

data class ProjectDto(
    val id: String,
    @SerializedName("teamId") val teamId: String,
    val name: String,
    val description: String?,
    val status: String,
    /** 当前用户有效项目角色（GET /projects/{id} 返回，§权限方案）：PROJECT_ADMIN / PROJECT_MEMBER；
     *  Team Owner 即使无 project_members 记录也返回 PROJECT_ADMIN；列表接口可能不带此字段 */
    val role: String? = null,
    /** 生效（ACTIVE）仓库绑定数（v2.0.6 §24.1，项目卡/详情展示，避免逐卡 N+1 查询） */
    @SerializedName("repositoryCount") val repositoryCount: Int? = null,
    /** 最后活跃时间（GET /teams/{teamId}/projects/by-last-activity 返回，ISO8601 UTC；其余接口可能不带） */
    @SerializedName("lastActivityAt") val lastActivityAt: String? = null
)

/** 项目成员（加成员响应）：userId + role */
data class ProjectMemberDto(
    @SerializedName("userId") val userId: String,
    val role: String
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
    @SerializedName("latestActivityAt") val latestActivityAt: String?,
    @SerializedName("latestMessage") val latestMessage: GroupLatestMessageDto?,
    /** v2.0.6 §1.1：未读消息数（后端权威，前端直接用） */
    @SerializedName("unreadCount") val unreadCount: Int? = 0,
    /** v2.0.6 §1.1：未读「@我」消息数（后端权威；>0 显示「有人@你」角标） */
    @SerializedName("mentionedUnread") val mentionedUnread: Int? = 0,
    /** 群创建者 userId（PROJECT_MAIN 总群为 null）；成员管理仅创建者或 Project Admin，见 §7/§24.4 */
    @SerializedName("createdBy") val createdBy: String? = null
)

/** v2.0.6 §1.2：标记群已读响应（POST .../groups/{groupId}/read） */
data class GroupReadResponse(
    @SerializedName("groupId") val groupId: String,
    @SerializedName("lastReadSequenceNo") val lastReadSequenceNo: Long = 0,
    @SerializedName("unreadCount") val unreadCount: Int = 0
)

/** 邀请项目成员入群（v2.0.6 §9：POST .../groups/{groupId}/members，仅 REQUIREMENT 群，body {userId}） */
data class AddGroupMemberRequest(
    @SerializedName("userId") val userId: String
)

/** 群列表摘要（文档 §7 群列表 DTO 补充）：{ senderName, text }；SYSTEM 消息 senderName 为空 */
data class GroupLatestMessageDto(
    @SerializedName("senderName") val senderName: String?,
    val text: String?,
    val type: String? = null
)

data class GroupMemberDto(
    val id: String,
    val nickname: String?,
    @SerializedName("displayName") val displayName: String? = null,
    /** 头像字段名后端可能为 avatar / avatarUrl（文档未冻结，兼容两者） */
    @SerializedName(value = "avatar", alternate = ["avatarUrl", "headUrl"])
    val avatar: String?,
    /** 成员类型：USER / AGENT（文档 §7：群成员 = 项目成员 + 参与群聊的 Agent） */
    @SerializedName("memberType") val memberType: String? = null
) {
    /** 后端用户表用 display_name，群成员昵称可能是 displayName；兼容 nickname，兜底「成员」 */
    val resolvedName: String get() = displayName ?: nickname ?: "成员"

    /** 是否 Agent：memberType=AGENT；后端未返回时按昵称启发式兜底 */
    val isAgent: Boolean get() = memberType == "AGENT" || resolvedName.startsWith("Agent", ignoreCase = true)
}

// ── 消息 ──

/** 消息 @ 提及目标：type=USER/AGENT，id=被提及者 userId/agentId */
data class MentionDto(
    val type: String,
    val id: String
)

data class GroupMessageDto(
    val id: String,
    @SerializedName("groupId") val groupId: String,
    @SerializedName("senderId") val senderId: String,
    @SerializedName("senderName") val senderName: String?,
    /** 发送者类型：USER / AGENT / SYSTEM（文档 §7）；USER=userId，AGENT=agentId */
    @SerializedName("senderType") val senderType: String? = null,
    val type: String,               // TEXT / CODE / IMAGE / FILE / SYSTEM / QUOTE / TASK_STATUS
    val content: MessageContentDto?,
    /** v2.0.6 §1.4：QUOTE 消息回复正文可能回显在顶层（兼容 content.replyText） */
    @SerializedName("replyText") val replyText: String? = null,
    val mentions: List<MentionDto>?,
    @SerializedName("replyToId") val replyToId: String?,
    @SerializedName("clientMessageId") val clientMessageId: String?,
    val sequence: Long,
    @SerializedName("createdAt") val createdAt: String
)

data class MessageContentDto(
    val text: String?,
    @SerializedName("url") val url: String? = null,
    @SerializedName("name") val name: String? = null,
    @SerializedName("size") val size: Long? = null,
    @SerializedName("mimeType") val mimeType: String? = null,
    // ── QUOTE 引用消息（v2.0.4）：content 含 replyText(回复正文) + quotedText/quotedMessageId/quotedSenderName(被引用信息) ──
    @SerializedName("replyText") val replyText: String? = null,
    @SerializedName("quotedText") val quotedText: String? = null,
    @SerializedName("quotedMessageId") val quotedMessageId: String? = null,
    @SerializedName("quotedSenderName") val quotedSenderName: String? = null,
    // ── TASK_STATUS 卡片（v23 §23.3）：阶段 / 交付模式 / 计划快照 ──
    @SerializedName("taskId") val taskId: String? = null,
    @SerializedName("status") val status: String? = null,
    @SerializedName("phase") val phase: String? = null,
    @SerializedName("deliveryMode") val deliveryMode: String? = null,
    @SerializedName("deliveryReason") val deliveryReason: String? = null,
    @SerializedName("node") val node: String? = null,
    @SerializedName("message") val message: String? = null,
    @SerializedName("currentStepId") val currentStepId: String? = null,
    @SerializedName("plan") val plan: PlanSnapshotDto? = null,
    // ── DIFF 卡片（v23 §23.4）：content 至少含 diffId，另带 reviewBatchId/reviewStatus/deliveryStatus ──
    @SerializedName(value = "diffId", alternate = ["reviewId", "resourceId"])
    val diffId: String? = null,
    @SerializedName("reviewBatchId") val reviewBatchId: String? = null,
    @SerializedName("title") val title: String? = null,
    @SerializedName("additions") val additions: Int? = null,
    @SerializedName("deletions") val deletions: Int? = null,
    @SerializedName("reviewStatus") val reviewStatus: String? = null,
    @SerializedName("deliveryStatus") val deliveryStatus: String? = null
)

/** TASK_STATUS 卡 plan 快照（v23 §23.3）：Planner 计划摘要 + TaskStep 快照列表 */
data class PlanSnapshotDto(
    val summary: String? = null,
    val steps: List<TaskStepSnapshotDto>? = null
)

/** TASK_STATUS 卡 plan.steps 单步快照（v23 §23.3；stepId 为数据库 TaskStepEntity.id） */
data class TaskStepSnapshotDto(
    @SerializedName("stepId") val stepId: String? = null,
    val sequence: Int? = null,
    val title: String? = null,
    val role: String? = null,
    val status: String? = null,
    val message: String? = null
)

/** 创建对象存储直传凭证（§18.1：POST /projects/{projectId}/attachments） */
data class CreateAttachmentRequest(
    @SerializedName("fileName") val fileName: String,
    @SerializedName("contentType") val contentType: String?,
    @SerializedName("sizeBytes") val sizeBytes: Long
)

/** 直传凭证响应（§18.1）：uploadUrl 为预签名 PUT 地址，method 恒 PUT */
data class AttachmentDto(
    @SerializedName("attachmentId") val attachmentId: String,
    @SerializedName("uploadUrl") val uploadUrl: String?,
    @SerializedName("method") val method: String?,
    @SerializedName("expiresAt") val expiresAt: String?,
    @SerializedName("headers") val headers: Map<String, String>?
)

/** 上传完成确认（§18.2）：confirm 后 status = READY */
data class AttachmentConfirmDto(
    @SerializedName("attachmentId") val attachmentId: String,
    val status: String
)

// ── 请求体 ──

data class CreateGroupRequest(
    val title: String,
    val description: String? = null,
    @SerializedName("repositoryIds") val repositoryIds: List<String>? = null,
    /** 创建需求群时选中的成员（userId 列表）；为空则由后端默认（文档契约以 memberIds 为准，已与后端确认） */
    @SerializedName("memberIds") val memberIds: List<String>? = null,
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
    // v2.0.6 §1.4：QUOTE 消息的回复正文放顶层（content 只含 quoted* 三字段）
    @SerializedName("replyText") val replyText: String? = null,
    // v2.0.6 §1：mentions 恢复进请求体（type=USER/AGENT 数组，@Agent 自动触发任务、@用户通知）
    val mentions: List<MentionDto>? = null,
    @SerializedName("replyToId") val replyToId: String? = null,
    @SerializedName("clientMessageId") val clientMessageId: String? = null
)

/** 契约 §7：从群消息显式触发 Task（POST .../messages/{messageId}/trigger-task）。
 *  title 必填；repositoryIds 缺省用群关联仓库；baseRef 可选公共基线分支。
 *  引用 DIFF 卡续作时不得传 repositoryIds（服务端复用源 Workspace，否则 409
 *  WORKSPACE_CONTINUATION_REPOSITORIES_FORBIDDEN）；deliveryMode 可选，不传由后端判定。 */
data class TaskTriggerRequest(
    val title: String,
    val requirement: String? = null,
    @SerializedName("repositoryIds") val repositoryIds: List<String>? = null,
    @SerializedName("baseRef") val baseRef: String? = null,
    @SerializedName("deliveryMode") val deliveryMode: String? = null
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
    @SerializedName("description") val description: String? = null,
    val visibility: String,             // PRIVATE / TEAM_SHARED
    val status: String,                 // ACTIVE / ARCHIVED
    /** v2.0.6 §5.1：系统预置 Agent=true（不可编辑），自定义=false */
    @SerializedName("isDefault") val isDefault: Boolean? = null,
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

/** Agent 在当前项目的 Skill 绑定集（GET /projects/{projectId}/agent-skill-bindings/{agentId}） */
data class AgentSkillBindingsResponse(
    @SerializedName("agentId") val agentId: String,
    @SerializedName("skillIds") val skillIds: List<String>? = null,
    val skills: List<AgentSkillSummaryDto>? = null
)

/** 绑定集内 Skill 摘要（{id, name, visibility, status}） */
data class AgentSkillSummaryDto(
    val id: String,
    val name: String? = null,
    val visibility: String? = null,
    val status: String? = null
)

// ── GitHub 集成（§6）──

/**
 * 发起安装返回的跳转链接（POST /teams/{teamId}/integrations/github/installations）
 */
data class GitHubInstallationUrlDto(
    @SerializedName("installationUrl") val installationUrl: String,
    @SerializedName("expiresAt") val expiresAt: String?
)

/**
 * 团队已安装的 GitHub App 记录（Installation 层）。
 * id = 本地 Installation UUID，仅作展示或排查用 providerInstallationId 不参与写请求。
 */
data class GitHubInstallationDto(
    val id: String,
    @SerializedName("providerInstallationId") val providerInstallationId: Long,
    @SerializedName("accountLogin") val accountLogin: String,
    @SerializedName("accountType") val accountType: String,   // USER / ORGANIZATION
    val status: String,                                        // ACTIVE / SUSPENDED / DELETED
    @SerializedName("installedAt") val installedAt: String?,
    @SerializedName("metadataSyncedAt") val metadataSyncedAt: String?
)

/**
 * 团队被授权的 GitHub 仓库镜像（Repository 层）。
 * id = 本地 Repository UUID；providerRepositoryId 仅展示或排查。
 */
data class GitHubRepositoryDto(
    val id: String,
    @SerializedName("installationId") val installationId: String,
    @SerializedName("providerRepositoryId") val providerRepositoryId: Long,
    @SerializedName("fullName") val fullName: String,
    @SerializedName("githubUrl") val githubUrl: String?,
    @SerializedName("defaultBranch") val defaultBranch: String?,
    val visibility: String,              // PUBLIC / PRIVATE / INTERNAL
    val archived: Boolean,
    @SerializedName("authorizationStatus") val authorizationStatus: String,   // AUTHORIZED / REVOKED
    @SerializedName("metadataSyncedAt") val metadataSyncedAt: String?
)

/**
 * 项目仓库绑定（ProjectRepository 层）。
 * id = project_repositories.id，后续 PATCH/DELETE 与 Task 创建的 repositoryIds 均用此 id。
 */
data class ProjectRepositoryDto(
    val id: String,
    @SerializedName("repositoryId") val repositoryId: String,
    @SerializedName("installationId") val installationId: String,
    @SerializedName("providerRepositoryId") val providerRepositoryId: Long,
    @SerializedName("fullName") val fullName: String,
    @SerializedName("githubUrl") val githubUrl: String?,
    @SerializedName("defaultBranch") val defaultBranch: String,
    @SerializedName("displayName") val displayName: String,
    @SerializedName("authorizationStatus") val authorizationStatus: String,
    @SerializedName("metadataSyncedAt") val metadataSyncedAt: String?,
    @SerializedName("boundAt") val boundAt: String?
)

/**
 * 绑定项目仓库请求（POST /projects/{projectId}/repositories）。
 * 只传 Installation.id 与 Repository.id，不传 provider 数字 ID。
 */
data class BindProjectRepositoryRequest(
    @SerializedName("installationId") val installationId: String,
    @SerializedName("repositoryId") val repositoryId: String,
    @SerializedName("displayName") val displayName: String
)

/**
 * 通知（§7.1 通知中心，GET /notifications）。
 * kind: TASK_COMPLETED / TASK_FAILED / AGENT_INPUT_REQUIRED / DELIVERABLE_PENDING /
 *       MR_PENDING / INVITED / TEAM_JOINED / PROJECT_ADDED
 * projectId/groupId/resourceId 仅定位用，点击跳转的关联资源 id（taskId/mrId/diffId）。
 */
data class NotificationDto(
    val id: String,
    val kind: String,
    val title: String,
    val description: String?,
    @SerializedName("isRead") val isRead: Boolean,
    @SerializedName("createdAt") val createdAt: String,
    @SerializedName("projectId") val projectId: String?,
    @SerializedName("groupId") val groupId: String?,
    @SerializedName("resourceId") val resourceId: String?
)

// ── Skill / Memory（§8 / §9） ──

/** 共享 Skill（§8）。status: DRAFT / PENDING_REVIEW / PUBLISHED / REJECTED / ARCHIVED */
data class SkillDto(
    val id: String,
    @SerializedName("projectId") val projectId: String,
    val name: String,
    val content: String?,
    val tags: List<String>?,
    val visibility: String?,           // PRIVATE / PROJECT_SHARED
    val status: String,
    val creator: UserSummaryDto?,
    val reviewer: UserSummaryDto?,
    @SerializedName("rejectionReason") val rejectionReason: String?,
    @SerializedName("reviewedAt") val reviewedAt: String?,
    @SerializedName("createdAt") val createdAt: String?,
    @SerializedName("updatedAt") val updatedAt: String?
)

/** 共享 Memory（§9）。status: DRAFT / PENDING_REVIEW / APPROVED / REJECTED / ARCHIVED */
data class MemoryDto(
    val id: String,
    @SerializedName("projectId") val projectId: String,
    val title: String,
    val content: String?,
    val category: String?,
    val tags: List<String>?,
    val status: String,
    val creator: UserSummaryDto?,
    val reviewer: UserSummaryDto?,
    @SerializedName("rejectionReason") val rejectionReason: String?,
    @SerializedName("reviewedAt") val reviewedAt: String?,
    @SerializedName("createdAt") val createdAt: String?
)

/** 用户摘要（Skill/Memory 的创建者/审查者） */
data class UserSummaryDto(
    val id: String,
    val name: String?
)

/** 创建 Skill 草稿（POST /projects/{projectId}/skills） */
data class CreateSkillRequest(
    val name: String,
    val content: String? = null,
    val tags: List<String>? = null,
    val visibility: String? = null
)

/** 创建 Memory 草稿（POST /projects/{projectId}/memories） */
data class CreateMemoryRequest(
    val title: String,
    val content: String? = null,
    val category: String? = null,
    val tags: List<String>? = null
)

// ── Diff（§12.3，GET /projects/{projectId}/diffs/{diffId}/files） ──

/** Diff 文件项（DIFF 消息卡片内容）。
 *  后端两种返回形态兼容：`hunks`（分块）或 `lines`（平铺行，与 DiffLineResponseDto 同构）；
 *  路径字段兼容 `path` / `fileName`。 */
data class DiffFileDto(
    val id: String,
    val sequence: Long? = null,
    val path: String? = null,
    @SerializedName("fileName") val fileName: String? = null,
    @SerializedName("changeType") val changeType: String? = null,
    val additions: Int = 0,
    val deletions: Int = 0,
    val binary: Boolean? = null,
    val hunks: List<DiffHunkDto>? = null,
    /** 平铺行形态（后端 lines 返回；与 hunks 二选一，优先 hunks） */
    val lines: List<DiffHunkLineDto>? = null
)

/** Diff hunks 块：统一以行文本表示（前端解析为 DiffLine） */
data class DiffHunkDto(
    @SerializedName("newStart") val newStart: Int? = null,
    @SerializedName("oldStart") val oldStart: Int? = null,
    @SerializedName("lines") val lines: List<DiffHunkLineDto>? = null
)

/** Diff hunks 单行 */
data class DiffHunkLineDto(
    val type: String? = null,       // ADD / DELETE / CONTEXT
    @SerializedName("oldLineNo") val oldLineNo: Int? = null,
    @SerializedName("newLineNo") val newLineNo: Int? = null,
    // 行代码字段名后端可能为 text/content/line/code（文档 files 接口 hunk/line 结构待定）；
    // 后端可能缺省/返回 null（Gson 绕过 Kotlin 空安全），映射时兜底为空串
    @SerializedName(value = "text", alternate = ["content", "line", "code"])
    val text: String? = null
)

/** Diff 确认/拒绝请求体（§15.3：POST /diffs/{diffId}/accept|reject，reason 可选） */
data class DiffDecisionRequest(
    val reason: String? = null
)

// ── Task 级 Diff Review 批次（§12.3） ──

/**
 * Task 级最终 Diff Review 批次（GET /projects/{projectId}/tasks/{taskId}/diff-review）。
 * 对应任务详情里的 diffReviewSummary：批次可跨多个仓库，确认/拒绝必须走批次接口
 * （POST .../diff-review/confirm|reject），单 Diff 的 accept/reject 对批次内 Diff 会返回
 * 409 DIFF_BATCH_REVIEW_REQUIRED。
 *
 * MR_FIRST（B 方案）扩展：confirmationSource（USER/SYSTEM）+ repositoryDeliveries 逐仓库交付进度。
 */
data class DiffReviewBatchDto(
    val id: String? = null,
    @SerializedName("taskId") val taskId: String? = null,
    /** PENDING_CONFIRMATION / ACCEPTED / REJECTED（校准后枚举，§v1.10.0） */
    @SerializedName("reviewStatus") val reviewStatus: String? = null,
    /** NOT_STARTED / DELIVERING / DELIVERED / PARTIALLY_DELIVERED / FAILED（校准后枚举，§v1.10.0） */
    @SerializedName("deliveryStatus") val deliveryStatus: String? = null,

    /**
     * 确认来源：USER（用户确认）/ SYSTEM（后端自动判定交付）。
     * 只读字段，仅服务端返回，客户端不得提交或修改。
     */
    /** 交付授权来源（§15.2）：USER=用户确认 / SYSTEM=MR_FIRST 自动授权；前端不得展示为"用户已确认" */
    @SerializedName("confirmationSource") val confirmationSource: String? = null,
    @SerializedName("repositoryCount") val repositoryCount: Int = 0,
    @SerializedName("filesChanged") val filesChanged: Int = 0,
    val additions: Int = 0,
    val deletions: Int = 0,
    /** 批次内各仓库 Diff 列表（按 project_repository_id 升序，与发送 Diff 卡片顺序一致） */
    val diffs: List<DiffListItemResponse>? = null,
    /** 逐仓库交付进度（MR_FIRST B 方案扩展）：每个目标仓库的交付状态、MR、失败原因 */
    @SerializedName("repositoryDeliveries") val repositoryDeliveries: List<RepositoryDeliveryDto>? = null
)

/**
 * 逐仓库交付进度（MR_FIRST B 方案，DiffReviewBatch.repositoryDeliveries[]）。
 * deliveryStatus：NOT_STARTED / COMMITTED / MR_CREATED / FAILED。
 * failureCode / failureReason 可空；失败原因仅展示后端返回的脱敏文本。
 */
data class RepositoryDeliveryDto(
    @SerializedName("repositoryId") val repositoryId: String,
    @SerializedName("repositoryName") val repositoryName: String? = null,
    @SerializedName("deliveryStatus") val deliveryStatus: String? = null,
    /** MR 已创建时返回真实链接/编号/标题；webUrl 为空时前端不渲染 MR 链接 */
    @SerializedName("mergeRequest") val mergeRequest: RepositoryDeliveryMergeRequestDto? = null,
    @SerializedName("failureCode") val failureCode: String? = null,
    @SerializedName("failureReason") val failureReason: String? = null,
    @SerializedName("updatedAt") val updatedAt: String? = null
)

/** 逐仓库交付产生的合并请求（MR_CREATED 时返回；webUrl 为空不渲染链接） */
data class RepositoryDeliveryMergeRequestDto(
    @SerializedName("webUrl") val webUrl: String? = null,
    val number: Int? = null,
    val title: String? = null
)

/** 批次内单个 Diff 列表项（§12.3 DiffListItemResponse） */
data class DiffListItemResponse(
    val id: String? = null,
    @SerializedName("projectId") val projectId: String? = null,
    @SerializedName("taskId") val taskId: String? = null,
    @SerializedName("taskRunId") val taskRunId: String? = null,
    @SerializedName("taskStepId") val taskStepId: String? = null,
    @SerializedName("requirementGroupId") val requirementGroupId: String? = null,
    @SerializedName("workspaceId") val workspaceId: String? = null,
    @SerializedName("repositoryId") val repositoryId: String? = null,
    @SerializedName("repositoryName") val repositoryName: String? = null,
    @SerializedName("baseCommit") val baseCommit: String? = null,
    @SerializedName("sourceBranch") val sourceBranch: String? = null,
    @SerializedName("headCommit") val headCommit: String? = null,
    val status: String? = null,
    @SerializedName("changeStats") val changeStats: DiffChangeStatsDto? = null,
    @SerializedName("createdAt") val createdAt: String? = null
)

/** Diff 变更统计（changeStats） */
data class DiffChangeStatsDto(
    val files: Int = 0,
    val additions: Int = 0,
    val deletions: Int = 0
)

/** 确认整个最终 Diff 批次（POST .../tasks/{taskId}/diff-review/confirm，§12.3；body 传空对象 {}） */
class DiffReviewConfirmRequest

/** 拒绝整个最终 Diff 批次（POST .../tasks/{taskId}/diff-review/reject，§12.3；body {"reason":"..."}） */
data class DiffReviewRejectRequest(
    val reason: String? = null
)

// ── 任务（§16 任务列表 / 任务卡片） ──

/** 需求群摘要（任务列表项的 requirementGroup 字段） */
data class TaskRequirementGroupDto(
    val id: String,
    val name: String,
    val status: String
)

/** 用户摘要（任务列表项的 createdByUser 字段） */
data class TaskUserSummaryDto(
    val id: String,
    @SerializedName("displayName") val displayName: String,
    @SerializedName("avatarUrl") val avatarUrl: String?
)

/** 任务仓库摘要（任务列表项的 repositories 字段） */
data class TaskRepositoryDto(
    @SerializedName("repositoryId") val repositoryId: String,
    val name: String,
    @SerializedName("fullName") val fullName: String,
    val provider: String,
    @SerializedName("defaultBranch") val defaultBranch: String,
    @SerializedName("baseRef") val baseRef: String,
    @SerializedName("baseCommit") val baseCommit: String?,
    @SerializedName("sourceBranch") val sourceBranch: String?,
    @SerializedName("headCommit") val headCommit: String?
)

/** 执行进度摘要（任务列表项的 executionSummary 字段，§16.1） */
data class TaskExecutionSummaryDto(
    @SerializedName("totalSteps") val totalSteps: Int,
    @SerializedName("pendingSteps") val pendingSteps: Int,
    @SerializedName("runningSteps") val runningSteps: Int,
    @SerializedName("waitingSteps") val waitingSteps: Int,
    @SerializedName("blockedSteps") val blockedSteps: Int,
    @SerializedName("succeededSteps") val succeededSteps: Int,
    @SerializedName("failedSteps") val failedSteps: Int,
    @SerializedName("currentStage") val currentStage: String?,
    @SerializedName("currentStageTitle") val currentStageTitle: String?,
    @SerializedName("requiresUserAction") val requiresUserAction: Boolean
)

/** 任务列表项（GET /projects/{projectId}/tasks，§16.1）。priority 后端恒为 null，不展示。 */
data class TaskCreateRequest(
    @SerializedName("requirementGroupId") val requirementGroupId: String,
    @SerializedName("triggerMessageId") val triggerMessageId: String? = null,
    val title: String,
    val requirement: String,
    @SerializedName("repositoryIds") val repositoryIds: List<String>,
    @SerializedName("baseRef") val baseRef: String? = null,
    /**
     * 可选交付模式（DIFF_FIRST / MR_FIRST，MR_FIRST 后端实现已合入 develop）。
     * 不传时前端不做任何判定，由后端 Planner/规则决定。
     * 注意：confirmationSource 只能由服务端返回，客户端不得提交或修改该字段。
     */
    @SerializedName("deliveryMode") val deliveryMode: String? = null
)
data class TaskListItemDto(
    val id: String,
    @SerializedName("displayCode") val displayCode: String,
    @SerializedName("projectId") val projectId: String,
    val title: String,
    @SerializedName("requirementSummary") val requirementSummary: String?,
    val status: String,
    val priority: String? = null,
    /** 交付模式：DIFF_FIRST / MR_FIRST（MR_FIRST = 自动交付，Reviewer 通过后不等待人工确认） */
    @SerializedName("deliveryMode") val deliveryMode: String,
    /** 服务端判定的交付模式理由（MR_FIRST 时展示，如"规则命中自动交付"） */
    @SerializedName("deliveryReason") val deliveryReason: String? = null,
    @SerializedName("requirementGroup") val requirementGroup: TaskRequirementGroupDto?,
    @SerializedName("createdByUser") val createdByUser: TaskUserSummaryDto?,
    val repositories: List<TaskRepositoryDto>?,
    @SerializedName("executionSummary") val executionSummary: TaskExecutionSummaryDto?,
    /** attention 后端返回对象（B07 扩展，非字符串）；当前 App 不展示，用 JsonElement 兼容任意结构 */
    val attention: com.google.gson.JsonElement?,
    @SerializedName("createdAt") val createdAt: String,
    @SerializedName("updatedAt") val updatedAt: String
)

/** 任务详情（GET /projects/{projectId}/tasks/{taskId}，§16.2）：列表项字段 + 完整需求 */
data class TaskDetailDto(
    val id: String,
    @SerializedName("displayCode") val displayCode: String,
    @SerializedName("projectId") val projectId: String,
    val title: String,
    val requirement: String?,
    @SerializedName("requirementSummary") val requirementSummary: String?,
    val status: String,
    /** 交付模式：DIFF_FIRST / MR_FIRST（MR_FIRST = 自动交付，Reviewer 通过后不等待人工确认） */
    @SerializedName("deliveryMode") val deliveryMode: String,
    /** 服务端判定的交付模式理由（MR_FIRST 时展示，如"规则命中自动交付"） */
    @SerializedName("deliveryReason") val deliveryReason: String? = null,
    @SerializedName("requirementGroup") val requirementGroup: TaskRequirementGroupDto?,
    @SerializedName("createdByUser") val createdByUser: TaskUserSummaryDto?,
    val repositories: List<TaskRepositoryDto>?,
    @SerializedName("executionSummary") val executionSummary: TaskExecutionSummaryDto?,
    /** diffReviewSummary 后端结构可能变化，用 JsonElement 兼容（解析见 ChatDetailFragment） */
    @SerializedName("diffReviewSummary") val diffReviewSummary: com.google.gson.JsonElement?,
    val capabilities: TaskCapabilitiesDto?,
    /** Task 启动失败原因（§34.1，Sandbox/Worker 初始化失败且未创建 TaskRun 时返回；成功/进行中为 null） */
    @SerializedName("statusReason") val statusReason: TaskStatusReasonDto? = null,
    @SerializedName("createdAt") val createdAt: String,
    @SerializedName("updatedAt") val updatedAt: String
)

/** Task 启动失败原因（§34.1）：failureCode 为稳定错误码（SANDBOX_WORKER_ERROR / GIT_BASE_REF_NOT_FOUND 等），summary 为脱敏文案 */
data class TaskStatusReasonDto(
    @SerializedName("code") val code: String?,
    @SerializedName("failureCode") val failureCode: String?,
    @SerializedName("title") val title: String?,
    @SerializedName("summary") val summary: String?,
    @SerializedName("retryable") val retryable: Boolean = false,
    @SerializedName("occurredAt") val occurredAt: String?
)

/** Diff 审查摘要（任务详情 §16.2 / §20.4）：待确认 Diff 时 available=true。
 *  diffId 字段名后端可能变化（diffId/reviewId/resourceId），由 ChatDetailFragment 从 JsonElement 解析。 */
data class TaskDiffReviewSummaryDto(
    val available: Boolean = false,
    @SerializedName("reviewStatus") val reviewStatus: String?,
    @SerializedName("deliveryStatus") val deliveryStatus: String?,
    @SerializedName("repositoryCount") val repositoryCount: Int = 0,
    @SerializedName("filesChanged") val filesChanged: Int = 0,
    val additions: Int = 0,
    val deletions: Int = 0,
    /** 待确认 Diff 的 diffId（字段名可能为 diffId/reviewId/resourceId，解析见 ChatDetailFragment） */
    val diffId: String?
)

/** 任务能力位（§15.6/§16.2）：canConfirmDiffReview 等控制 App 端按钮可用性 */
data class TaskCapabilitiesDto(
    @SerializedName("canCancel") val canCancel: Boolean = false,
    @SerializedName("cancelDisabledReason") val cancelDisabledReason: String?,
    @SerializedName("canReplacePendingStepAgent") val canReplacePendingStepAgent: Boolean = false,
    @SerializedName("replacePendingStepAgentDisabledReason") val replacePendingStepAgentDisabledReason: String?,
    @SerializedName("canConfirmDiffReview") val canConfirmDiffReview: Boolean = false,
    @SerializedName("confirmDiffReviewDisabledReason") val confirmDiffReviewDisabledReason: String?,
    @SerializedName("canRejectDiffReview") val canRejectDiffReview: Boolean = false,
    @SerializedName("rejectDiffReviewDisabledReason") val rejectDiffReviewDisabledReason: String?,
    @SerializedName("canRetryDelivery") val canRetryDelivery: Boolean = false,
    @SerializedName("retryDeliveryDisabledReason") val retryDeliveryDisabledReason: String?
)

// ── 任务步骤 / 任务运行（§16.3 / §16.4） ──

/** 步骤执行 Agent 摘要（TaskStep 的 agent 字段） */
data class TaskStepAgentDto(
    val id: String,
    val name: String,
    val role: String,
    @SerializedName("avatarUrl") val avatarUrl: String?,
    val status: String
)

/** 步骤仓库摘要（TaskStep 的 repository 字段） */
data class TaskStepRepositoryDto(
    @SerializedName("repositoryId") val repositoryId: String,
    val name: String,
    @SerializedName("sourceBranch") val sourceBranch: String?
)

/** 步骤最新一次 TaskRun 摘要（TaskStep 的 latestRun 字段） */
data class TaskStepLatestRunDto(
    val id: String,
    val status: String,
    @SerializedName("startedAt") val startedAt: String?,
    @SerializedName("finishedAt") val finishedAt: String?,
    @SerializedName("durationMs") val durationMs: Long?
)

/** 替换步骤执行 Agent 请求（POST tasks/{taskId}/steps/{stepId}/replace-agent，§11.3） */
data class ReplaceAgentRequest(
    @SerializedName("agentId") val agentId: String
)

/** 任务步骤列表项（GET tasks/{taskId}/steps，§16.3） */
data class TaskStepListItemDto(
    val id: String,
    @SerializedName("taskId") val taskId: String,
    @SerializedName("sequenceNo") val sequenceNo: Int,
    val title: String,
    val description: String?,
    val role: String,
    val agent: TaskStepAgentDto?,
    val repository: TaskStepRepositoryDto?,
    val dependencies: List<String>?,
    val status: String,                 // PENDING/RUNNING/SUCCEEDED/FAILED/SKIPPED
    @SerializedName("acceptanceNotes") val acceptanceNotes: String?,
    @SerializedName("latestRun") val latestRun: TaskStepLatestRunDto?,
    @SerializedName("runCount") val runCount: Int,
    @SerializedName("startedAt") val startedAt: String?,
    @SerializedName("finishedAt") val finishedAt: String?,
    @SerializedName("createdAt") val createdAt: String,
    @SerializedName("updatedAt") val updatedAt: String
)

/** 运行 Agent 摘要（任务运行列表项的 agent 字段） */
data class TaskRunAgentDto(
    val id: String,
    val name: String,
    val role: String,
    @SerializedName("avatarUrl") val avatarUrl: String?
)

/** 任务运行失败/等待原因（TaskRun.statusReason，§16.4）：code/title/summary，无等待或失败时为 null */
data class TaskRunStatusReasonDto(
    @SerializedName("code") val code: String?,
    @SerializedName("title") val title: String?,
    @SerializedName("summary") val summary: String?,
    @SerializedName("retryable") val retryable: Boolean = false,
    @SerializedName("occurredAt") val occurredAt: String?
)

/** 任务运行列表项（GET tasks/{taskId}/task-runs，§16.4） */
data class TaskRunDetailListItemDto(
    val id: String,
    @SerializedName("projectId") val projectId: String,
    @SerializedName("taskId") val taskId: String,
    @SerializedName("taskStepId") val taskStepId: String,
    @SerializedName("taskStepTitle") val taskStepTitle: String?,
    @SerializedName("agentId") val agentId: String,
    val role: String,
    val agent: TaskRunAgentDto?,
    val status: String,                 // QUEUED/RUNNING/SUCCEEDED/FAILED/WAITING_INPUT/...
    @SerializedName("statusSummary") val statusSummary: String?,
    @SerializedName("statusReason") val statusReason: TaskRunStatusReasonDto?,
    @SerializedName("startedAt") val startedAt: String?,
    @SerializedName("finishedAt") val finishedAt: String?,
    @SerializedName("durationMs") val durationMs: Long?,
    @SerializedName("createdAt") val createdAt: String,
    @SerializedName("updatedAt") val updatedAt: String
)

/** 任务运行日志条目（GET /task-runs/{taskRunId}/logs，§12.2） */
data class TaskRunLogEntryDto(
    val id: String,
    val sequence: Long,
    val content: String,
    val timestamp: String
)

// ── 团队最近动态（§19.4） ──

/** 动态 actor（§19.4：{id, displayName, avatar}，无可靠来源为 null，avatar 恒为 null） */
data class ActivityActorDto(
    val id: String,
    @SerializedName("displayName") val displayName: String,
    val avatar: String? = null
)

/** 动态 target（§19.4：{type, id, title}，type ∈ TASK/DIFF/MR/PROJECT） */
data class ActivityTargetDto(
    val type: String,
    val id: String,
    val title: String
)

/** 团队最近动态（GET /teams/{teamId}/activities，§19.4），按 createdAt 倒序 */
data class ActivityDto(
    val id: String,
    val type: String,
    val title: String,
    val summary: String? = null,
    val actor: ActivityActorDto? = null,
    val target: ActivityTargetDto,
    val link: String? = null,
    @SerializedName("createdAt") val createdAt: String
)

// ── MR（§13） ──

/** 质量门禁摘要（MR 的 qualityGate 字段） */
data class MergeRequestQualityGateDto(
    val status: String,
    @SerializedName("requiredChecks") val requiredChecks: List<String>?
)

/** MR 列表项（GET /projects/{projectId}/merge-requests，§13/§21.2） */
data class MergeRequestDto(
    val id: String,
    @SerializedName("repositoryId") val repositoryId: String,
    @SerializedName("groupIds") val groupIds: List<String>?,
    val provider: String,
    val number: Int,
    val title: String?,
    @SerializedName("sourceBranch") val sourceBranch: String,
    @SerializedName("targetBranch") val targetBranch: String,
    val status: String,                 // OPEN / MERGED / CLOSED
    @SerializedName("headCommit") val headCommit: String?,
    @SerializedName("qualityGate") val qualityGate: MergeRequestQualityGateDto?,
    @SerializedName("createdAt") val createdAt: String?
)

/**
 * MR 详情（GET /projects/{projectId}/merge-requests/{mergeRequestId}，§13 + §21.2 Q2 扩展）。
 * 列表字段基础上补充 diffId（后端已支持返回该仓库该任务已 ACCEPTED 的 Diff id，无则 null）。
 */
data class MergeRequestDetailDto(
    val id: String,
    @SerializedName("repositoryId") val repositoryId: String,
    @SerializedName("groupIds") val groupIds: List<String>?,
    val provider: String,
    val number: Int,
    val title: String?,
    @SerializedName("sourceBranch") val sourceBranch: String,
    @SerializedName("targetBranch") val targetBranch: String,
    val status: String,                 // OPEN / MERGED / CLOSED
    @SerializedName("headCommit") val headCommit: String?,
    @SerializedName("qualityGate") val qualityGate: MergeRequestQualityGateDto?,
    @SerializedName("diffId") val diffId: String?,
    @SerializedName("createdAt") val createdAt: String?
)

/** Diff 文件行（GET /diffs/{diffId}/files 的 lines 项） */
data class DiffLineResponseDto(
    val type: String,                   // ADD / DELETE / CONTEXT
    @SerializedName("oldLineNo") val oldLineNo: Int?,
    @SerializedName("newLineNo") val newLineNo: Int?,
    val text: String
)

/** Diff 文件（GET /diffs/{diffId}/files，§12.3 / §21.2） */
data class DiffFileResponseDto(
    val id: String?,
    val sequence: Int?,
    val path: String,
    @SerializedName("changeType") val changeType: String,   // ADDED / MODIFIED / DELETED
    val additions: Int,
    val deletions: Int,
    val binary: Boolean?,
    val lines: List<DiffLineResponseDto>? = null,
    @SerializedName("fileName") val fileName: String? = null
)

// ── 项目级 TaskRun（§20.6） ──

/**
 * TaskRun 列表项（GET /projects/{projectId}/task-runs，§20.6）。
 * §12.2 摘要字段 + taskDisplayCode/taskTitle/taskStepRole。
 */
data class TaskRunListItemDto(
    val id: String,
    @SerializedName("projectId") val projectId: String,
    @SerializedName("taskId") val taskId: String,
    @SerializedName("taskStepId") val taskStepId: String,
    @SerializedName("agentId") val agentId: String,
    val role: String,
    val status: String,
    @SerializedName("taskDisplayCode") val taskDisplayCode: String?,
    @SerializedName("taskTitle") val taskTitle: String?,
    @SerializedName("taskStepRole") val taskStepRole: String?,
    @SerializedName("createdAt") val createdAt: String,
    @SerializedName("updatedAt") val updatedAt: String
)
