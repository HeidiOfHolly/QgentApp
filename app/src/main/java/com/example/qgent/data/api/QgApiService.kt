package com.example.qgent.data.api

import com.example.qgent.data.model.AgentDto
import com.example.qgent.data.model.AgentSkillBindingsRequest
import com.example.qgent.data.model.AddProjectMemberRequest
import com.example.qgent.data.model.ApiResponse
import com.example.qgent.data.model.AttachmentDto
import com.example.qgent.data.model.AttachmentConfirmDto
import com.example.qgent.data.model.BindProjectRepositoryRequest
import com.example.qgent.data.model.CreateAttachmentRequest
import com.example.qgent.data.model.CreateAgentRequest
import com.example.qgent.data.model.CreateGroupRequest
import com.example.qgent.data.model.CreateMemoryRequest
import com.example.qgent.data.model.CreateProjectRequest
import com.example.qgent.data.model.CreateSkillRequest
import com.example.qgent.data.model.CreateTeamRequest
import com.example.qgent.data.model.DiffDecisionRequest
import com.example.qgent.data.model.DiffFileDto
import com.example.qgent.data.model.DiffReviewBatchDto
import com.example.qgent.data.model.DiffReviewConfirmRequest
import com.example.qgent.data.model.DiffReviewRejectRequest
import com.example.qgent.data.model.GitHubInstallationDto
import com.example.qgent.data.model.GitHubInstallationUrlDto
import com.example.qgent.data.model.GitHubRepositoryDto
import com.example.qgent.data.model.GroupDto
import com.example.qgent.data.model.GroupMemberDto
import com.example.qgent.data.model.InviteTeamMemberRequest
import com.example.qgent.data.model.AuthSessionDto
import com.example.qgent.data.model.GroupMessageDto
import com.example.qgent.data.model.LoginRequest
import com.example.qgent.data.model.MemoryDto
import com.example.qgent.data.model.NotificationDto
import com.example.qgent.data.model.ProjectDto
import com.example.qgent.data.model.ProjectMemberDto
import com.example.qgent.data.model.ProjectRepositoryDto
import com.example.qgent.data.model.ReceivedInvitationDto
import com.example.qgent.data.model.RefreshRequest
import com.example.qgent.data.model.ReplaceAgentRequest
import com.example.qgent.data.model.RegisterRequest
import com.example.qgent.data.model.SendMessageRequest
import com.example.qgent.data.model.SkillDto
import com.example.qgent.data.model.TaskTriggerRequest
import com.example.qgent.data.model.TeamDto
import com.example.qgent.data.model.ActivityDto
import com.example.qgent.data.model.DiffFileResponseDto
import com.example.qgent.data.model.MergeRequestDetailDto
import com.example.qgent.data.model.MergeRequestDto
import com.example.qgent.data.model.TaskCreateRequest
import com.example.qgent.data.model.TaskDetailDto
import com.example.qgent.data.model.TaskListItemDto
import com.example.qgent.data.model.TaskRunDetailListItemDto
import com.example.qgent.data.model.TaskRunListItemDto
import com.example.qgent.data.model.TaskRunLogEntryDto
import com.example.qgent.data.model.TaskStepListItemDto
import com.example.qgent.data.model.TeamInvitationDto
import com.example.qgent.data.model.TeamMemberDto
import com.example.qgent.data.model.UpdateAgentRequest
import com.example.qgent.data.model.UpdateGroupRequest
import com.example.qgent.data.model.UpdateProjectMemberRequest
import com.example.qgent.data.model.UserProfileDto
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * QG 后端 API 接口定义。
 * 接口文档 v1.1.2 —— 仅覆盖已实现页面所需接口。
 * 未实现页面/缺失接口见 memory 或与后端对接后补充。
 * 群（Group）统一建模为 PROJECT_MAIN + REQUIREMENT，路径挂在 /projects/{id}/groups 下。
 *
 * 所有接口返回 [retrofit2.Response] 包裹的 [ApiResponse]，
 * 由 data.model 中的 toDataOrThrow() / toUnitOrThrow() 统一解析错误契约。
 *
 * 幂等性：后端对所有写操作（POST/PUT/PATCH/DELETE）强制要求 Idempotency-Key 请求头，
 * 缺失返回 400 IDEMPOTENCY_KEY_REQUIRED。该头不是鉴权（鉴权走 Authorization: Bearer），
 * 而是防重复：同一逻辑操作重试时复用同一 UUID，后端据此去重，避免连点/重试产生重复数据。
 * 因此每个写接口都带 @Header("Idempotency-Key")，由调用方生成 UUID.randomUUID() 传入。
 */
interface QgApiService {

    // ── 认证与账户（§4）──

    @POST("auth/register")
    suspend fun register(@Body body: RegisterRequest): Response<ApiResponse<AuthSessionDto>>

    @POST("auth/login")
    suspend fun login(@Body body: LoginRequest): Response<ApiResponse<AuthSessionDto>>

    @POST("auth/refresh")
    suspend fun refresh(@Body body: RefreshRequest): Response<ApiResponse<AuthSessionDto>>

    // ── 用户 ──

    @GET("me")
    suspend fun getUserProfile(): Response<ApiResponse<UserProfileDto>>

    // ── 团队 ──

    @GET("teams")
    suspend fun getTeams(): Response<ApiResponse<List<TeamDto>>>

    @DELETE("teams/{teamId}")
    suspend fun deleteTeam(
        @Path("teamId") teamId: String,
        @Header("Idempotency-Key") idempotencyKey: String
    ): Response<ApiResponse<TeamDto>>

    @POST("teams")
    suspend fun createTeam(
        @Header("Idempotency-Key") idempotencyKey: String,
        @Body body: CreateTeamRequest
    ): Response<ApiResponse<TeamDto>>

    @GET("teams/{teamId}/members")
    suspend fun getTeamMembers(
        @Path("teamId") teamId: String,
        @Query("cursor") cursor: String? = null,
        @Query("limit") limit: Int = 30
    ): Response<ApiResponse<List<TeamMemberDto>>>

    @DELETE("teams/{teamId}/members/{userId}")
    suspend fun removeTeamMember(
        @Path("teamId") teamId: String,
        @Path("userId") userId: String,
        @Header("Idempotency-Key") idempotencyKey: String
    ): Response<Unit>

    @POST("teams/{teamId}/invitations")
    suspend fun createInvitation(
        @Path("teamId") teamId: String,
        @Header("Idempotency-Key") idempotencyKey: String,
        @Body body: InviteTeamMemberRequest
    ): Response<ApiResponse<TeamInvitationDto>>

    @GET("teams/{teamId}/invitations")
    suspend fun getTeamInvitations(
        @Path("teamId") teamId: String
    ): Response<ApiResponse<List<TeamInvitationDto>>>

    @GET("team-invitations")
    suspend fun getReceivedInvitations(
        @Query("cursor") cursor: String? = null,
        @Query("limit") limit: Int = 30
    ): Response<ApiResponse<List<ReceivedInvitationDto>>>

    @POST("team-invitations/{reference}/accept")
    suspend fun acceptTeamInvitation(
        @Path("reference") reference: String,
        @Header("Idempotency-Key") idempotencyKey: String
    ): Response<ApiResponse<TeamMemberDto>>

    @DELETE("teams/{teamId}/invitations/{invitationId}")
    suspend fun revokeInvitation(
        @Path("teamId") teamId: String,
        @Path("invitationId") invitationId: String,
        @Header("Idempotency-Key") idempotencyKey: String
    ): Response<Unit>

    // ── 项目 ──

    @GET("teams/{teamId}/projects")
    suspend fun getProjects(
        @Path("teamId") teamId: String
    ): Response<ApiResponse<List<ProjectDto>>>

    @POST("teams/{teamId}/projects")
    suspend fun createProject(
        @Path("teamId") teamId: String,
        @Header("Idempotency-Key") idempotencyKey: String,
        @Body body: CreateProjectRequest
    ): Response<ApiResponse<ProjectDto>>

    @POST("projects/{projectId}/members")
    suspend fun addProjectMember(
        @Path("projectId") projectId: String,
        @Header("Idempotency-Key") idempotencyKey: String,
        @Body body: AddProjectMemberRequest
    ): Response<ApiResponse<ProjectMemberDto>>

    /** 调整项目成员角色（§5.2）：在 PROJECT_MEMBER / PROJECT_ADMIN 间切换 */
    @PATCH("projects/{projectId}/members/{userId}")
    suspend fun updateProjectMemberRole(
        @Path("projectId") projectId: String,
        @Path("userId") userId: String,
        @Header("Idempotency-Key") idempotencyKey: String,
        @Body body: UpdateProjectMemberRequest
    ): Response<ApiResponse<ProjectMemberDto>>

    @GET("projects/{projectId}/members")
    suspend fun getProjectMembers(
        @Path("projectId") projectId: String
    ): Response<ApiResponse<List<ProjectMemberDto>>>

    // ── 群（Group） ──

    @GET("projects/{projectId}/groups")
    suspend fun getGroups(
        @Path("projectId") projectId: String,
        @Query("cursor") cursor: String? = null,
        @Query("limit") limit: Int = 30
    ): Response<ApiResponse<List<GroupDto>>>

    @POST("projects/{projectId}/groups")
    suspend fun createGroup(
        @Path("projectId") projectId: String,
        @Header("Idempotency-Key") idempotencyKey: String,
        @Body body: CreateGroupRequest
    ): Response<ApiResponse<GroupDto>>

    @GET("projects/{projectId}/groups/{groupId}")
    suspend fun getGroup(
        @Path("projectId") projectId: String,
        @Path("groupId") groupId: String
    ): Response<ApiResponse<GroupDto>>

    @PATCH("projects/{projectId}/groups/{groupId}")
    suspend fun updateGroup(
        @Path("projectId") projectId: String,
        @Path("groupId") groupId: String,
        @Header("Idempotency-Key") idempotencyKey: String,
        @Body body: UpdateGroupRequest
    ): Response<ApiResponse<GroupDto>>

    @POST("projects/{projectId}/groups/{groupId}/archive")
    suspend fun archiveGroup(
        @Path("projectId") projectId: String,
        @Path("groupId") groupId: String,
        @Header("Idempotency-Key") idempotencyKey: String
    ): Response<ApiResponse<GroupDto>>

    // ── 群成员 ──

    @GET("projects/{projectId}/groups/{groupId}/members")
    suspend fun getGroupMembers(
        @Path("projectId") projectId: String,
        @Path("groupId") groupId: String
    ): Response<ApiResponse<List<GroupMemberDto>>>

    @POST("projects/{projectId}/groups/{groupId}/leave")
    suspend fun leaveGroup(
        @Path("projectId") projectId: String,
        @Path("groupId") groupId: String,
        @Header("Idempotency-Key") idempotencyKey: String
    ): Response<Unit>

    // ── 群聊消息 ──

    @GET("projects/{projectId}/groups/{groupId}/messages")
    suspend fun getMessages(
        @Path("projectId") projectId: String,
        @Path("groupId") groupId: String,
        @Query("cursor") cursor: String? = null,
        @Query("limit") limit: Int = 30
    ): Response<ApiResponse<List<GroupMessageDto>>>

    @POST("projects/{projectId}/groups/{groupId}/messages")
    suspend fun sendMessage(
        @Path("projectId") projectId: String,
        @Path("groupId") groupId: String,
        @Header("Idempotency-Key") idempotencyKey: String,
        @Body body: SendMessageRequest
    ): Response<ApiResponse<GroupMessageDto>>

    /** 契约 §7：从群消息显式触发 Task（data 恒为 null，成功看 HTTP 200） */
    @POST("projects/{projectId}/groups/{groupId}/messages/{messageId}/trigger-task")
    suspend fun triggerTask(
        @Path("projectId") projectId: String,
        @Path("groupId") groupId: String,
        @Path("messageId") messageId: String,
        @Header("Idempotency-Key") idempotencyKey: String,
        @Body body: TaskTriggerRequest
    ): Response<ApiResponse<Unit>>

    // ── 通知中心（§7.1）──

    @GET("notifications")
    suspend fun getNotifications(): Response<ApiResponse<List<NotificationDto>>>

    @POST("notifications/{id}/read")
    suspend fun markNotificationRead(
        @Path("id") id: String,
        @Header("Idempotency-Key") idempotencyKey: String
    ): Response<Unit>

    @POST("notifications/read-all")
    suspend fun markAllNotificationsRead(
        @Header("Idempotency-Key") idempotencyKey: String
    ): Response<Unit>

    // ── 附件（文档 §7：对象存储直传凭证）──

    @POST("projects/{projectId}/attachments")
    suspend fun createAttachment(
        @Path("projectId") projectId: String,
        @Header("Idempotency-Key") idempotencyKey: String,
        @Body body: CreateAttachmentRequest
    ): Response<ApiResponse<AttachmentDto>>

    @POST("projects/{projectId}/attachments/{attachmentId}/confirm")
    suspend fun confirmAttachment(
        @Path("projectId") projectId: String,
        @Path("attachmentId") attachmentId: String,
        @Header("Idempotency-Key") idempotencyKey: String
    ): Response<ApiResponse<AttachmentConfirmDto>>

    // ── Agent（§11）──

    @GET("teams/{teamId}/agents")
    suspend fun getAgents(
        @Path("teamId") teamId: String
    ): Response<ApiResponse<List<AgentDto>>>

    @POST("teams/{teamId}/agents")
    suspend fun createAgent(
        @Path("teamId") teamId: String,
        @Header("Idempotency-Key") idempotencyKey: String,
        @Body body: CreateAgentRequest
    ): Response<ApiResponse<AgentDto>>

    @GET("teams/{teamId}/agents/{agentId}")
    suspend fun getAgent(
        @Path("teamId") teamId: String,
        @Path("agentId") agentId: String
    ): Response<ApiResponse<AgentDto>>

    @PATCH("teams/{teamId}/agents/{agentId}")
    suspend fun updateAgent(
        @Path("teamId") teamId: String,
        @Path("agentId") agentId: String,
        @Header("Idempotency-Key") idempotencyKey: String,
        @Body body: UpdateAgentRequest
    ): Response<ApiResponse<AgentDto>>

    @POST("teams/{teamId}/agents/{agentId}/publish")
    suspend fun publishAgent(
        @Path("teamId") teamId: String,
        @Path("agentId") agentId: String,
        @Header("Idempotency-Key") idempotencyKey: String
    ): Response<ApiResponse<AgentDto>>

    @POST("teams/{teamId}/agents/{agentId}/unpublish")
    suspend fun unpublishAgent(
        @Path("teamId") teamId: String,
        @Path("agentId") agentId: String,
        @Header("Idempotency-Key") idempotencyKey: String
    ): Response<ApiResponse<AgentDto>>

    @POST("teams/{teamId}/agents/{agentId}/archive")
    suspend fun archiveAgent(
        @Path("teamId") teamId: String,
        @Path("agentId") agentId: String,
        @Header("Idempotency-Key") idempotencyKey: String
    ): Response<ApiResponse<AgentDto>>

    @PUT("projects/{projectId}/agent-skill-bindings/{agentId}")
    suspend fun bindAgentSkills(
        @Path("projectId") projectId: String,
        @Path("agentId") agentId: String,
        @Header("Idempotency-Key") idempotencyKey: String,
        @Body body: AgentSkillBindingsRequest
    ): Response<ApiResponse<AgentDto>>

    // ── GitHub 集成（§6）──

    @POST("teams/{teamId}/integrations/github/installations")
    suspend fun createInstallation(
        @Path("teamId") teamId: String,
        @Header("Idempotency-Key") idempotencyKey: String,
        @Query("client") client: String
    ): Response<ApiResponse<GitHubInstallationUrlDto>>

    @GET("teams/{teamId}/integrations/github/installations")
    suspend fun getInstallations(
        @Path("teamId") teamId: String
    ): Response<ApiResponse<List<GitHubInstallationDto>>>

    @DELETE("teams/{teamId}/integrations/github/installations/{installationId}")
    suspend fun deleteInstallation(
        @Path("teamId") teamId: String,
        @Path("installationId") installationId: String,
        @Header("Idempotency-Key") idempotencyKey: String
    ): Response<Unit>

    @POST("teams/{teamId}/integrations/github/installations/{installationId}/sync")
    suspend fun syncInstallation(
        @Path("teamId") teamId: String,
        @Path("installationId") installationId: String,
        @Header("Idempotency-Key") idempotencyKey: String
    ): Response<ApiResponse<GitHubInstallationDto>>

    @GET("teams/{teamId}/integrations/github/repositories")
    suspend fun getGithubRepositories(
        @Path("teamId") teamId: String,
        @Query("cursor") cursor: String? = null,
        @Query("limit") limit: Int = 100
    ): Response<ApiResponse<List<GitHubRepositoryDto>>>

    @DELETE("teams/{teamId}/integrations/github/repositories/{repositoryId}")
    suspend fun revokeGithubRepository(
        @Path("teamId") teamId: String,
        @Path("repositoryId") repositoryId: String,
        @Header("Idempotency-Key") idempotencyKey: String
    ): Response<Unit>

    @GET("projects/{projectId}/repositories")
    suspend fun getProjectRepositories(
        @Path("projectId") projectId: String
    ): Response<ApiResponse<List<ProjectRepositoryDto>>>

    @POST("projects/{projectId}/repositories")
    suspend fun bindProjectRepository(
        @Path("projectId") projectId: String,
        @Header("Idempotency-Key") idempotencyKey: String,
        @Body body: BindProjectRepositoryRequest
    ): Response<ApiResponse<ProjectRepositoryDto>>

    @DELETE("projects/{projectId}/repositories/{projectRepositoryId}")
    suspend fun unbindProjectRepository(
        @Path("projectId") projectId: String,
        @Path("projectRepositoryId") projectRepositoryId: String,
        @Header("Idempotency-Key") idempotencyKey: String
    ): Response<Unit>

    // ── 共享 Skill（§8） ──

    @GET("projects/{projectId}/skills")
    suspend fun getSkills(
        @Path("projectId") projectId: String,
        @Query("status") status: String? = null,
        @Query("tag") tag: String? = null
    ): Response<ApiResponse<List<SkillDto>>>

    @POST("projects/{projectId}/skills")
    suspend fun createSkill(
        @Path("projectId") projectId: String,
        @Header("Idempotency-Key") idempotencyKey: String,
        @Body body: CreateSkillRequest
    ): Response<ApiResponse<SkillDto>>

    @GET("projects/{projectId}/skills/{skillId}")
    suspend fun getSkill(
        @Path("projectId") projectId: String,
        @Path("skillId") skillId: String
    ): Response<ApiResponse<SkillDto>>

    @POST("projects/{projectId}/skills/{skillId}/submit-review")
    suspend fun submitSkillReview(
        @Path("projectId") projectId: String,
        @Path("skillId") skillId: String,
        @Header("Idempotency-Key") idempotencyKey: String
    ): Response<ApiResponse<SkillDto>>

    @POST("projects/{projectId}/skills/{skillId}/approve")
    suspend fun approveSkill(
        @Path("projectId") projectId: String,
        @Path("skillId") skillId: String,
        @Header("Idempotency-Key") idempotencyKey: String
    ): Response<ApiResponse<SkillDto>>

    @POST("projects/{projectId}/skills/{skillId}/reject")
    suspend fun rejectSkill(
        @Path("projectId") projectId: String,
        @Path("skillId") skillId: String,
        @Header("Idempotency-Key") idempotencyKey: String
    ): Response<ApiResponse<SkillDto>>

    @POST("projects/{projectId}/skills/{skillId}/archive")
    suspend fun archiveSkill(
        @Path("projectId") projectId: String,
        @Path("skillId") skillId: String,
        @Header("Idempotency-Key") idempotencyKey: String
    ): Response<ApiResponse<SkillDto>>

    // ── 共享 Memory（§9） ──

    @GET("projects/{projectId}/memories")
    suspend fun getMemories(
        @Path("projectId") projectId: String,
        @Query("status") status: String? = null,
        @Query("tag") tag: String? = null
    ): Response<ApiResponse<List<MemoryDto>>>

    @POST("projects/{projectId}/memories")
    suspend fun createMemory(
        @Path("projectId") projectId: String,
        @Header("Idempotency-Key") idempotencyKey: String,
        @Body body: CreateMemoryRequest
    ): Response<ApiResponse<MemoryDto>>

    @POST("projects/{projectId}/memories/drafts")
    suspend fun createMemoryAiDraft(
        @Path("projectId") projectId: String,
        @Header("Idempotency-Key") idempotencyKey: String,
        @Body body: CreateMemoryRequest
    ): Response<ApiResponse<MemoryDto>>

    @GET("projects/{projectId}/memories/{memoryId}")
    suspend fun getMemory(
        @Path("projectId") projectId: String,
        @Path("memoryId") memoryId: String
    ): Response<ApiResponse<MemoryDto>>

    @POST("projects/{projectId}/memories/{memoryId}/submit-review")
    suspend fun submitMemoryReview(
        @Path("projectId") projectId: String,
        @Path("memoryId") memoryId: String,
        @Header("Idempotency-Key") idempotencyKey: String
    ): Response<ApiResponse<MemoryDto>>

    @POST("projects/{projectId}/memories/{memoryId}/approve")
    suspend fun approveMemory(
        @Path("projectId") projectId: String,
        @Path("memoryId") memoryId: String,
        @Header("Idempotency-Key") idempotencyKey: String
    ): Response<ApiResponse<MemoryDto>>

    @POST("projects/{projectId}/memories/{memoryId}/reject")
    suspend fun rejectMemory(
        @Path("projectId") projectId: String,
        @Path("memoryId") memoryId: String,
        @Header("Idempotency-Key") idempotencyKey: String
    ): Response<ApiResponse<MemoryDto>>

    @POST("projects/{projectId}/memories/{memoryId}/archive")
    suspend fun archiveMemory(
        @Path("projectId") projectId: String,
        @Path("memoryId") memoryId: String,
        @Header("Idempotency-Key") idempotencyKey: String
    ): Response<ApiResponse<MemoryDto>>

    // ── Diff（§12.3） ──

    /** Diff 文件列表（DIFF 消息卡片内容） */
    @GET("projects/{projectId}/diffs/{diffId}/files")
    suspend fun getDiffFiles(
        @Path("projectId") projectId: String,
        @Path("diffId") diffId: String,
        @Query("cursor") cursor: String? = null,
        @Query("limit") limit: Int = 20
    ): Response<ApiResponse<List<DiffFileDto>>>
    // ── 任务（§16 任务列表） ──

    /** 创建任务（§11.3：从需求群创建，可创建新 Workspace） */
    @POST("projects/{projectId}/tasks")
    suspend fun createTask(
        @Path("projectId") projectId: String,
        @Header("Idempotency-Key") idempotencyKey: String,
        @Body body: TaskCreateRequest
    ): Response<ApiResponse<TaskListItemDto>>

    @GET("projects/{projectId}/tasks")
    suspend fun getTasks(
        @Path("projectId") projectId: String,
        @Query("groupId") groupId: String? = null,
        @Query("status") status: String? = null,
        @Query("createdBy") createdBy: String? = null,
        @Query("repositoryId") repositoryId: String? = null,
        @Query("cursor") cursor: String? = null,
        @Query("limit") limit: Int = 20
    ): Response<ApiResponse<List<TaskListItemDto>>>

    // ── 团队最近动态（§19.4） ──

    @GET("teams/{teamId}/activities")
    suspend fun getActivities(
        @Path("teamId") teamId: String,
        @Query("cursor") cursor: String? = null,
        @Query("limit") limit: Int = 20
    ): Response<ApiResponse<List<ActivityDto>>>

    // ── MR（§13） ──

    @GET("projects/{projectId}/merge-requests")
    suspend fun getMergeRequests(
        @Path("projectId") projectId: String,
        @Query("cursor") cursor: String? = null,
        @Query("limit") limit: Int = 20
    ): Response<ApiResponse<List<MergeRequestDto>>>

    @GET("projects/{projectId}/merge-requests/{mergeRequestId}")
    suspend fun getMergeRequestDetail(
        @Path("projectId") projectId: String,
        @Path("mergeRequestId") mergeRequestId: String
    ): Response<ApiResponse<MergeRequestDetailDto>>

    @GET("projects/{projectId}/diffs/{diffId}/files")
    suspend fun getDiffFiles(
        @Path("projectId") projectId: String,
        @Path("diffId") diffId: String
    ): Response<ApiResponse<List<DiffFileResponseDto>>>

    /** 确认 Diff（§15.3） */
    @POST("projects/{projectId}/diffs/{diffId}/accept")
    suspend fun acceptDiff(
        @Path("projectId") projectId: String,
        @Path("diffId") diffId: String,
        @Header("Idempotency-Key") idempotencyKey: String,
        @Body body: DiffDecisionRequest?
    ): Response<ApiResponse<Unit>>

    /** 拒绝 Diff（§15.3） */
    @POST("projects/{projectId}/diffs/{diffId}/reject")
    suspend fun rejectDiff(
        @Path("projectId") projectId: String,
        @Path("diffId") diffId: String,
        @Header("Idempotency-Key") idempotencyKey: String,
        @Body body: DiffDecisionRequest?
    ): Response<ApiResponse<Unit>>

    // ── Task 级 Diff Review 批次（§12.3） ──
    // 批次内 Diff 禁止用单 Diff 的 accept/reject（409 DIFF_BATCH_REVIEW_REQUIRED），
    // 必须用下列 Task 级接口确认/拒绝整个批次；三个写接口均要求 Idempotency-Key。

    /** 查询 Task 级最终 Diff Review 批次（可能为 null：任务暂无 Diff 待确认） */
    @GET("projects/{projectId}/tasks/{taskId}/diff-review")
    suspend fun getTaskDiffReview(
        @Path("projectId") projectId: String,
        @Path("taskId") taskId: String
    ): Response<ApiResponse<DiffReviewBatchDto>>

    /** 读取批次内单个 Diff 的不可变 patch 内容 */
    @GET("projects/{projectId}/tasks/{taskId}/diff-review/diffs/{diffId}/patch")
    suspend fun getDiffReviewPatch(
        @Path("projectId") projectId: String,
        @Path("taskId") taskId: String,
        @Path("diffId") diffId: String
    ): Response<ApiResponse<com.google.gson.JsonElement>>

    /** 确认整个最终 Diff 批次，开始逐仓库交付 */
    @POST("projects/{projectId}/tasks/{taskId}/diff-review/confirm")
    suspend fun confirmDiffReview(
        @Path("projectId") projectId: String,
        @Path("taskId") taskId: String,
        @Header("Idempotency-Key") idempotencyKey: String,
        @Body body: DiffReviewConfirmRequest
    ): Response<ApiResponse<Unit>>

    /** 拒绝整个最终 Diff 批次（body 带 reason） */
    @POST("projects/{projectId}/tasks/{taskId}/diff-review/reject")
    suspend fun rejectDiffReview(
        @Path("projectId") projectId: String,
        @Path("taskId") taskId: String,
        @Header("Idempotency-Key") idempotencyKey: String,
        @Body body: DiffReviewRejectRequest
    ): Response<ApiResponse<Unit>>

    /** 重试逐仓库交付（交付失败后可重试） */
    @POST("projects/{projectId}/tasks/{taskId}/diff-review/retry-delivery")
    suspend fun retryDiffDelivery(
        @Path("projectId") projectId: String,
        @Path("taskId") taskId: String,
        @Header("Idempotency-Key") idempotencyKey: String
    ): Response<ApiResponse<Unit>>

    @GET("projects/{projectId}/tasks/{taskId}")
    suspend fun getTaskDetail(
        @Path("projectId") projectId: String,
        @Path("taskId") taskId: String
    ): Response<ApiResponse<TaskDetailDto>>

    // ── 任务步骤 / 任务运行（§16.3 / §16.4） ──

    @GET("projects/{projectId}/tasks/{taskId}/steps")
    suspend fun getTaskSteps(
        @Path("projectId") projectId: String,
        @Path("taskId") taskId: String
    ): Response<ApiResponse<List<TaskStepListItemDto>>>

    @POST("projects/{projectId}/tasks/{taskId}/cancel")
    suspend fun cancelTask(
        @Path("projectId") projectId: String,
        @Path("taskId") taskId: String,
        @Header("Idempotency-Key") idempotencyKey: String
    ): Response<Unit>

    @POST("projects/{projectId}/tasks/{taskId}/steps/{stepId}/replace-agent")
    suspend fun replaceAgent(
        @Path("projectId") projectId: String,
        @Path("taskId") taskId: String,
        @Path("stepId") stepId: String,
        @Header("Idempotency-Key") idempotencyKey: String,
        @Body body: ReplaceAgentRequest
    ): Response<ApiResponse<TaskStepListItemDto>>

    @GET("projects/{projectId}/tasks/{taskId}/task-runs")
    suspend fun getTaskRunsOfTask(
        @Path("projectId") projectId: String,
        @Path("taskId") taskId: String
    ): Response<ApiResponse<List<TaskRunDetailListItemDto>>>

    /** 任务运行执行日志（§12.2） */
    @GET("projects/{projectId}/task-runs/{taskRunId}/logs")
    suspend fun getTaskRunLogs(
        @Path("projectId") projectId: String,
        @Path("taskRunId") taskRunId: String,
        @Query("cursor") cursor: String? = null,
        @Query("limit") limit: Int = 100
    ): Response<ApiResponse<List<TaskRunLogEntryDto>>>

    // ── 项目级按 Agent 查询 TaskRun（§20.6） ──

    @GET("projects/{projectId}/task-runs")
    suspend fun getTaskRuns(
        @Path("projectId") projectId: String,
        @Query("agentId") agentId: String,
        @Query("status") status: String? = null,
        @Query("cursor") cursor: String? = null,
        @Query("limit") limit: Int = 20
    ): Response<ApiResponse<List<TaskRunListItemDto>>>
}
