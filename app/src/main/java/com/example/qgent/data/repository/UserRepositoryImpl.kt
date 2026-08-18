package com.example.qgent.data.repository

import com.example.qgent.data.api.QgApiService
import com.example.qgent.data.model.AddProjectMemberRequest
import com.example.qgent.data.model.CreateProjectRequest
import com.example.qgent.data.model.CreateTeamRequest
import com.example.qgent.data.model.InviteTeamMemberRequest
import com.example.qgent.data.model.NotificationDto
import com.example.qgent.data.model.ProjectDto
import com.example.qgent.data.model.ProjectMemberDto
import com.example.qgent.data.model.ReceivedInvitationDto
import com.example.qgent.data.model.TeamDto
import com.example.qgent.data.model.TeamInvitationDto
import com.example.qgent.data.model.TeamMemberDto
import com.example.qgent.data.model.UpdateProjectMemberRequest
import com.example.qgent.data.model.UserProfileDto
import com.example.qgent.data.model.toDataOrThrow
import com.example.qgent.data.model.toUnitOrThrow

class UserRepositoryImpl(private val service: QgApiService) : UserRepository {

    override suspend fun getUserProfile(): Result<UserProfileDto> = apiCall {
        service.getUserProfile().toDataOrThrow()
    }

    override suspend fun getTeams(): Result<List<TeamDto>> = apiCall {
        service.getTeams().toDataOrThrow()
    }

    override suspend fun getTeamsByLastActivity(): Result<List<TeamDto>> = apiCall {
        service.getTeamsByLastActivity().toDataOrThrow()
    }

    override suspend fun getTeamMembers(teamId: String, cursor: String?, limit: Int): Result<List<TeamMemberDto>> = apiCall {
        service.getTeamMembers(teamId, cursor, limit).toDataOrThrow()
    }

    override suspend fun removeTeamMember(teamId: String, userId: String, idempotencyKey: String): Result<Unit> =
        apiCall {
            service.removeTeamMember(teamId, userId, idempotencyKey).toUnitOrThrow()
        }

    override suspend fun createInvitation(
        teamId: String,
        request: InviteTeamMemberRequest,
        idempotencyKey: String
    ): Result<TeamInvitationDto> = apiCall {
        service.createInvitation(teamId, idempotencyKey, request).toDataOrThrow()
    }

    override suspend fun getTeamInvitations(teamId: String): Result<List<TeamInvitationDto>> = apiCall {
        service.getTeamInvitations(teamId).toDataOrThrow()
    }

    override suspend fun revokeInvitation(teamId: String, invitationId: String, idempotencyKey: String): Result<Unit> =
        apiCall {
            service.revokeInvitation(teamId, invitationId, idempotencyKey).toUnitOrThrow()
        }

    override suspend fun acceptTeamInvitation(reference: String, idempotencyKey: String): Result<TeamMemberDto> =
        apiCall {
            service.acceptTeamInvitation(reference, idempotencyKey).toDataOrThrow()
        }

    override suspend fun getReceivedInvitations(): Result<List<ReceivedInvitationDto>> = apiCall {
        service.getReceivedInvitations().toDataOrThrow()
    }

    override suspend fun getProjects(teamId: String): Result<List<ProjectDto>> = apiCall {
        service.getProjects(teamId).toDataOrThrow()
    }

    override suspend fun getProjectsByLastActivity(teamId: String): Result<List<ProjectDto>> = apiCall {
        service.getProjectsByLastActivity(teamId).toDataOrThrow()
    }

    override suspend fun getProject(projectId: String): Result<ProjectDto> = apiCall {
        service.getProject(projectId).toDataOrThrow()
    }

    override suspend fun createProject(
        teamId: String,
        name: String,
        description: String?,
        newRepository: com.example.qgent.data.model.NewRepositoryRequest?,
        idempotencyKey: String
    ): Result<ProjectDto> = apiCall {
        service.createProject(
            teamId, idempotencyKey,
            CreateProjectRequest(name, description, newRepository)
        ).toDataOrThrow()
    }

    override suspend fun addProjectMember(
        projectId: String,
        userId: String,
        idempotencyKey: String
    ): Result<ProjectMemberDto> = apiCall {
        service.addProjectMember(projectId, idempotencyKey, AddProjectMemberRequest(userId)).toDataOrThrow()
    }

    override suspend fun updateProjectMemberRole(
        projectId: String,
        userId: String,
        role: String,
        idempotencyKey: String
    ): Result<ProjectMemberDto> = apiCall {
        service.updateProjectMemberRole(projectId, userId, idempotencyKey, UpdateProjectMemberRequest(role)).toDataOrThrow()
    }

    override suspend fun getProjectMembers(projectId: String): Result<List<ProjectMemberDto>> = apiCall {
        // 循环消费分页（cursor + limit=100），直到 hasMore=false，对外返回完整列表；
        // 任一分页请求失败整体失败（不返回部分结果，避免基于不完整成员做批量操作）
        val all = mutableListOf<ProjectMemberDto>()
        var cursor: String? = null
        var pages = 0
        do {
            if (++pages > MAX_MEMBER_PAGES) throw IllegalStateException("项目成员分页异常：超过 $MAX_MEMBER_PAGES 页")
            val resp = service.getProjectMembers(projectId, cursor, MEMBER_PAGE_SIZE)
            val page = resp.body()?.page
            val data = resp.toDataOrThrow()
            all += data
            cursor = if (page?.hasMore == true) page.nextCursor else null
        } while (cursor != null)
        all
    }

    override suspend fun removeProjectMember(projectId: String, userId: String, idempotencyKey: String): Result<Unit> =
        apiCall {
            service.removeProjectMember(projectId, userId, idempotencyKey).toUnitOrThrow()
        }

    override suspend fun createTeam(name: String, description: String?, idempotencyKey: String): Result<TeamDto> =
        apiCall {
            service.createTeam(idempotencyKey, CreateTeamRequest(name, description)).toDataOrThrow()
        }

    override suspend fun deleteTeam(teamId: String, idempotencyKey: String): Result<TeamDto> = apiCall {
        service.deleteTeam(teamId, idempotencyKey).toDataOrThrow()
    }

    override suspend fun getNotifications(): Result<List<NotificationDto>> = apiCall {
        service.getNotifications().toDataOrThrow()
    }

    override suspend fun markNotificationRead(notificationId: String, idempotencyKey: String): Result<Unit> =
        apiCall {
            service.markNotificationRead(notificationId, idempotencyKey).toUnitOrThrow()
        }

    override suspend fun markAllNotificationsRead(idempotencyKey: String): Result<Unit> =
        apiCall {
            service.markAllNotificationsRead(idempotencyKey).toUnitOrThrow()
        }

    companion object {
        private const val MEMBER_PAGE_SIZE = 100
        private const val MAX_MEMBER_PAGES = 100
    }
}
