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
    suspend fun createProject(teamId: String, name: String, description: String?, idempotencyKey: String): Result<ProjectDto>
    suspend fun addProjectMember(projectId: String, userId: String, idempotencyKey: String): Result<ProjectMemberDto>
    suspend fun getProjectMembers(projectId: String): Result<List<ProjectMemberDto>>
    suspend fun createTeam(name: String, description: String? = null, idempotencyKey: String): Result<TeamDto>
    suspend fun deleteTeam(teamId: String, idempotencyKey: String): Result<TeamDto>
    suspend fun getNotifications(): Result<List<NotificationDto>>
    suspend fun markNotificationRead(notificationId: String, idempotencyKey: String): Result<Unit>
    suspend fun markAllNotificationsRead(idempotencyKey: String): Result<Unit>
}
