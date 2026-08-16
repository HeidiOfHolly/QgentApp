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
import com.example.qgent.data.model.CreateProjectRequest
import com.example.qgent.data.model.CreateTeamRequest
import com.example.qgent.data.model.GitHubInstallationDto
import com.example.qgent.data.model.GitHubInstallationUrlDto
import com.example.qgent.data.model.GitHubRepositoryDto
import com.example.qgent.data.model.GroupDto
import com.example.qgent.data.model.GroupMemberDto
import com.example.qgent.data.model.InviteTeamMemberRequest
import com.example.qgent.data.model.AuthSessionDto
import com.example.qgent.data.model.GroupMessageDto
import com.example.qgent.data.model.LoginRequest
import com.example.qgent.data.model.NotificationDto
import com.example.qgent.data.model.ProjectDto
import com.example.qgent.data.model.ProjectMemberDto
import com.example.qgent.data.model.ProjectRepositoryDto
import com.example.qgent.data.model.RefreshRequest
import com.example.qgent.data.model.RegisterRequest
import com.example.qgent.data.model.SendMessageRequest
import com.example.qgent.data.model.TeamDto
import com.example.qgent.data.model.TeamInvitationDto
import com.example.qgent.data.model.TeamMemberDto
import com.example.qgent.data.model.UpdateAgentRequest
import com.example.qgent.data.model.UpdateGroupRequest
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
}
