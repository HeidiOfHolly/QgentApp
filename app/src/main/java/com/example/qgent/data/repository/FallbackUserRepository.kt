package com.example.qgent.data.repository

import com.example.qgent.data.model.InviteTeamMemberRequest

/** 真实请求失败时回退到 mock，保证演示环境可用 */
class FallbackUserRepository(
    real: UserRepository,
    mock: UserRepository
) : UserRepository {
    private val fb = FallbackDelegate(real, mock)

    override suspend fun getUserProfile() = fb.call { getUserProfile() }

    override suspend fun getTeams() = fb.call { getTeams() }

    override suspend fun getTeamMembers(teamId: String, cursor: String?, limit: Int) = fb.call { getTeamMembers(teamId, cursor, limit) }

    override suspend fun removeTeamMember(teamId: String, userId: String, idempotencyKey: String) =
        fb.call { removeTeamMember(teamId, userId, idempotencyKey) }

    override suspend fun createInvitation(teamId: String, request: InviteTeamMemberRequest, idempotencyKey: String) =
        fb.call { createInvitation(teamId, request, idempotencyKey) }

    override suspend fun getTeamInvitations(teamId: String) = fb.call { getTeamInvitations(teamId) }

    override suspend fun revokeInvitation(teamId: String, invitationId: String, idempotencyKey: String) =
        fb.call { revokeInvitation(teamId, invitationId, idempotencyKey) }

    override suspend fun acceptTeamInvitation(reference: String, idempotencyKey: String) =
        fb.call { acceptTeamInvitation(reference, idempotencyKey) }

    override suspend fun getReceivedInvitations() = fb.call { getReceivedInvitations() }

    override suspend fun getProjects(teamId: String) = fb.call { getProjects(teamId) }

    override suspend fun createProject(teamId: String, name: String, description: String?, idempotencyKey: String) =
        fb.call { createProject(teamId, name, description, idempotencyKey) }

    override suspend fun addProjectMember(projectId: String, userId: String, idempotencyKey: String) =
        fb.call { addProjectMember(projectId, userId, idempotencyKey) }

    override suspend fun updateProjectMemberRole(projectId: String, userId: String, role: String, idempotencyKey: String) =
        fb.call { updateProjectMemberRole(projectId, userId, role, idempotencyKey) }

    override suspend fun getProjectMembers(projectId: String) =
        fb.call { getProjectMembers(projectId) }

    override suspend fun createTeam(name: String, description: String?, idempotencyKey: String) =
        fb.call { createTeam(name, description, idempotencyKey) }

    override suspend fun deleteTeam(teamId: String, idempotencyKey: String) =
        fb.call { deleteTeam(teamId, idempotencyKey) }

    override suspend fun getNotifications() = fb.call { getNotifications() }

    override suspend fun markNotificationRead(notificationId: String, idempotencyKey: String) =
        fb.call { markNotificationRead(notificationId, idempotencyKey) }

    override suspend fun markAllNotificationsRead(idempotencyKey: String) =
        fb.call { markAllNotificationsRead(idempotencyKey) }
}
