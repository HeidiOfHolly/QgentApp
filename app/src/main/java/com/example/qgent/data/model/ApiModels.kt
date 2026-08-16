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

/** 刷新令牌请求（POST /auth/refresh） */
data class RefreshRequest(
    @SerializedName("refreshToken") val refreshToken: String
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

/** 创建项目（POST /teams/{teamId}/projects）；成员通过 API-069 逐个加入 */
data class CreateProjectRequest(
    val name: String,
    val description: String? = null
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
    val status: String
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
    @SerializedName("latestMessage") val latestMessage: GroupLatestMessageDto?
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
    // ── TASK_STATUS 卡片（文档 §7）：content 至少含 taskId、status ──
    @SerializedName("taskId") val taskId: String? = null,
    @SerializedName("status") val status: String? = null,
    @SerializedName("node") val node: String? = null,
    @SerializedName("message") val message: String? = null,
    // ── DIFF 卡片（文档 §7）：content 至少含 diffId ──
    @SerializedName("diffId") val diffId: String? = null,
    @SerializedName("title") val title: String? = null,
    @SerializedName("additions") val additions: Int? = null,
    @SerializedName("deletions") val deletions: Int? = null
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
    val mentions: List<MentionDto>? = null,
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
data class TaskListItemDto(
    val id: String,
    @SerializedName("displayCode") val displayCode: String,
    @SerializedName("projectId") val projectId: String,
    val title: String,
    @SerializedName("requirementSummary") val requirementSummary: String?,
    val status: String,
    val priority: String? = null,
    @SerializedName("deliveryMode") val deliveryMode: String,
    @SerializedName("requirementGroup") val requirementGroup: TaskRequirementGroupDto?,
    @SerializedName("createdByUser") val createdByUser: TaskUserSummaryDto?,
    val repositories: List<TaskRepositoryDto>?,
    @SerializedName("executionSummary") val executionSummary: TaskExecutionSummaryDto?,
    val attention: String?,
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
    @SerializedName("deliveryMode") val deliveryMode: String,
    @SerializedName("requirementGroup") val requirementGroup: TaskRequirementGroupDto?,
    @SerializedName("createdByUser") val createdByUser: TaskUserSummaryDto?,
    val repositories: List<TaskRepositoryDto>?,
    @SerializedName("executionSummary") val executionSummary: TaskExecutionSummaryDto?,
    @SerializedName("createdAt") val createdAt: String,
    @SerializedName("updatedAt") val updatedAt: String
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
    @SerializedName("startedAt") val startedAt: String?,
    @SerializedName("finishedAt") val finishedAt: String?,
    @SerializedName("durationMs") val durationMs: Long?,
    @SerializedName("createdAt") val createdAt: String,
    @SerializedName("updatedAt") val updatedAt: String
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
