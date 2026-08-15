package com.example.qgent.data.repository

import com.example.qgent.data.api.QgApiService
import com.example.qgent.data.model.AddProjectMemberRequest
import com.example.qgent.data.model.CreateProjectRequest
import com.example.qgent.data.model.CreateTeamRequest
import com.example.qgent.data.model.InviteTeamMemberRequest
import com.example.qgent.data.model.ProjectDto
import com.example.qgent.data.model.ProjectMemberDto
import com.example.qgent.data.model.TeamDto
import com.example.qgent.data.model.TeamInvitationDto
import com.example.qgent.data.model.TeamMemberDto
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

    override suspend fun getProjects(teamId: String): Result<List<ProjectDto>> = apiCall {
        service.getProjects(teamId).toDataOrThrow()
    }

    override suspend fun createProject(
        teamId: String,
        name: String,
        description: String?,
        idempotencyKey: String
    ): Result<ProjectDto> = apiCall {
        service.createProject(teamId, idempotencyKey, CreateProjectRequest(name, description)).toDataOrThrow()
    }

    override suspend fun addProjectMember(
        projectId: String,
        userId: String,
        idempotencyKey: String
    ): Result<ProjectMemberDto> = apiCall {
        service.addProjectMember(projectId, idempotencyKey, AddProjectMemberRequest(userId)).toDataOrThrow()
    }

    override suspend fun createTeam(name: String, description: String?, idempotencyKey: String): Result<TeamDto> =
        apiCall {
            service.createTeam(idempotencyKey, CreateTeamRequest(name, description)).toDataOrThrow()
        }
}
