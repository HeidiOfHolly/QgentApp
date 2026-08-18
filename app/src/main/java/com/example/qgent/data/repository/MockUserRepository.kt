package com.example.qgent.data.repository

import com.example.qgent.data.datasource.MockDataSource
import com.example.qgent.data.model.InviteTeamMemberRequest
import com.example.qgent.data.model.NotificationDto
import com.example.qgent.data.model.ProjectDto
import com.example.qgent.data.model.ProjectMemberDto
import com.example.qgent.data.model.ReceivedInvitationDto
import com.example.qgent.data.model.TeamDto
import com.example.qgent.data.model.TeamInvitationDto
import com.example.qgent.data.model.TeamMemberDto
import com.example.qgent.data.model.UserProfileDto

class MockUserRepository : UserRepository {

    // 按团队维护的可变邀请列表：撤销后从列表移除，弹窗刷新可看到记录消失（演示环境行为）
    private val invitationsByTeam = mutableMapOf<String, MutableList<TeamInvitationDto>>()

    private fun invitationsOf(teamId: String): MutableList<TeamInvitationDto> =
        invitationsByTeam.getOrPut(teamId) {
            mutableListOf(
                TeamInvitationDto("inv-1-$teamId", "a@example.com", "PENDING", "2026-08-20T00:00:00Z"),
                TeamInvitationDto("inv-2-$teamId", "b@example.com", "ACCEPTED", "2026-08-14T00:00:00Z"),
                TeamInvitationDto("inv-3-$teamId", "c@example.com", "REVOKED", "2026-08-12T00:00:00Z")
            )
        }

    override suspend fun getUserProfile(): Result<UserProfileDto> = Result.success(
        UserProfileDto("mock-user", "演示用户", "demo@example.com", null, githubLinked = false)
    )

    override suspend fun getTeams(): Result<List<TeamDto>> = Result.success(MockDataSource.teams)

    override suspend fun getTeamMembers(teamId: String, cursor: String?, limit: Int): Result<List<TeamMemberDto>> =
        Result.success(
            listOf(
                TeamMemberDto("mock-user", "TEAM_OWNER", "演示用户", "demo@example.com"),
                TeamMemberDto("mock-user-2", "TEAM_MEMBER", "李四", "lisi@example.com"),
                TeamMemberDto("mock-user-3", "TEAM_MEMBER", "王五", "wangwu@example.com")
            )
        )

    override suspend fun removeTeamMember(teamId: String, userId: String, idempotencyKey: String): Result<Unit> =
        Result.success(Unit)

    override suspend fun getTeamInvitations(teamId: String): Result<List<TeamInvitationDto>> =
        Result.success(invitationsOf(teamId).toList())

    override suspend fun createInvitation(
        teamId: String,
        request: InviteTeamMemberRequest,
        idempotencyKey: String
    ): Result<TeamInvitationDto> {
        val invitation = TeamInvitationDto(
            id = "inv-${System.currentTimeMillis()}-$teamId",
            email = request.email,
            status = "PENDING",
            expiresAt = "2026-08-${(15 + request.expiresInDays).coerceAtMost(31).toString().padStart(2, '0')}T00:00:00Z"
        )
        invitationsOf(teamId).add(invitation)
        return Result.success(invitation)
    }

    override suspend fun revokeInvitation(teamId: String, invitationId: String, idempotencyKey: String): Result<Unit> {
        invitationsOf(teamId).removeAll { it.id == invitationId }
        return Result.success(Unit)
    }

    override suspend fun acceptTeamInvitation(reference: String, idempotencyKey: String): Result<TeamMemberDto> =
        Result.success(
            TeamMemberDto(
                userId = "mock-user-joined",
                role = "TEAM_MEMBER",
                displayName = "演示用户",
                email = "demo@example.com"
            )
        )

    override suspend fun getReceivedInvitations(): Result<List<ReceivedInvitationDto>> =
        Result.success(emptyList())

    override suspend fun getProjects(teamId: String): Result<List<ProjectDto>> =
        Result.success(MockDataSource.projectsOf(teamId))

    override suspend fun getProject(projectId: String): Result<ProjectDto> = Result.success(
        ProjectDto(
            id = projectId,
            teamId = "mock-team",
            name = "演示项目",
            description = null,
            status = "ACTIVE",
            role = "PROJECT_ADMIN"
        )
    )

    override suspend fun createProject(
        teamId: String,
        name: String,
        description: String?,
        newRepository: com.example.qgent.data.model.NewRepositoryRequest?,
        idempotencyKey: String
    ): Result<ProjectDto> = Result.success(
        ProjectDto("mock-project-${System.currentTimeMillis()}", teamId, name, description, "ACTIVE")
    )

    override suspend fun addProjectMember(
        projectId: String,
        userId: String,
        idempotencyKey: String
    ): Result<ProjectMemberDto> = Result.success(ProjectMemberDto(userId, "PROJECT_MEMBER"))

    override suspend fun updateProjectMemberRole(
        projectId: String,
        userId: String,
        role: String,
        idempotencyKey: String
    ): Result<ProjectMemberDto> = Result.success(ProjectMemberDto(userId, role))

    override suspend fun getProjectMembers(projectId: String): Result<List<ProjectMemberDto>> =
        Result.success(listOf(ProjectMemberDto("mock-user", "PROJECT_ADMIN")))

    override suspend fun createTeam(name: String, description: String?, idempotencyKey: String): Result<TeamDto> =
        Result.success(
            TeamDto(
                id = "mock-team-${System.currentTimeMillis()}",
                name = name,
                role = "TEAM_OWNER",
                memberCount = 1,
                createdAt = "2026-08-14"
            )
        )

    override suspend fun deleteTeam(teamId: String, idempotencyKey: String): Result<TeamDto> =
        Result.failure(UnsupportedOperationException("mock 不支持解散团队"))

    override suspend fun getNotifications(): Result<List<NotificationDto>> =
        Result.success(
            listOf(
                NotificationDto(
                    id = "notif-1",
                    kind = "INVITED",
                    title = "你被邀请加入团队 团队A",
                    description = null,
                    isRead = false,
                    createdAt = "2026-08-15T02:00:00Z",
                    projectId = null,
                    groupId = null,
                    resourceId = null
                )
            )
        )

    override suspend fun markNotificationRead(notificationId: String, idempotencyKey: String): Result<Unit> =
        Result.success(Unit)

    override suspend fun markAllNotificationsRead(idempotencyKey: String): Result<Unit> =
        Result.success(Unit)
}
