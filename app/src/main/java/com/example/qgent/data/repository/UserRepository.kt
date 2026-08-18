package com.example.qgent.data.repository

import com.example.qgent.data.model.InviteTeamMemberRequest
import com.example.qgent.data.model.NotificationDto
import com.example.qgent.data.model.ProjectDto
import com.example.qgent.data.model.ProjectMemberDto
import com.example.qgent.data.model.ReceivedInvitationDto
import com.example.qgent.data.model.TeamDto
import com.example.qgent.data.model.TeamInvitationDto
import com.example.qgent.data.model.TeamMemberDto
import com.example.qgent.data.model.UserProfileDto

interface UserRepository {
    suspend fun getUserProfile(): Result<UserProfileDto>
    suspend fun getTeams(): Result<List<TeamDto>>
    suspend fun getTeamMembers(teamId: String, cursor: String? = null, limit: Int = 30): Result<List<TeamMemberDto>>
    suspend fun removeTeamMember(teamId: String, userId: String, idempotencyKey: String): Result<Unit>
    suspend fun createInvitation(teamId: String, request: InviteTeamMemberRequest, idempotencyKey: String): Result<TeamInvitationDto>
    suspend fun getTeamInvitations(teamId: String): Result<List<TeamInvitationDto>>
    suspend fun revokeInvitation(teamId: String, invitationId: String, idempotencyKey: String): Result<Unit>
    suspend fun acceptTeamInvitation(reference: String, idempotencyKey: String): Result<TeamMemberDto>
    suspend fun getReceivedInvitations(): Result<List<ReceivedInvitationDto>>
    suspend fun getProjects(teamId: String): Result<List<ProjectDto>>
    /** 项目详情：含当前用户有效项目角色 role（权限判断统一以此为准，不用成员列表猜） */
    suspend fun getProject(projectId: String): Result<ProjectDto>
    suspend fun createProject(teamId: String, name: String, description: String?, newRepository: com.example.qgent.data.model.NewRepositoryRequest? = null, idempotencyKey: String): Result<ProjectDto>
    suspend fun addProjectMember(projectId: String, userId: String, idempotencyKey: String): Result<ProjectMemberDto>
    /** 调整项目成员角色（§5.2）：PROJECT_MEMBER / PROJECT_ADMIN */
    suspend fun updateProjectMemberRole(projectId: String, userId: String, role: String, idempotencyKey: String): Result<ProjectMemberDto>
    /** 项目成员全量列表（内部循环消费分页至 hasMore=false，对外始终返回完整 List） */
    suspend fun getProjectMembers(projectId: String): Result<List<ProjectMemberDto>>
    suspend fun createTeam(name: String, description: String? = null, idempotencyKey: String): Result<TeamDto>
    suspend fun deleteTeam(teamId: String, idempotencyKey: String): Result<TeamDto>
    suspend fun getNotifications(): Result<List<NotificationDto>>
    suspend fun markNotificationRead(notificationId: String, idempotencyKey: String): Result<Unit>
    suspend fun markAllNotificationsRead(idempotencyKey: String): Result<Unit>
}
