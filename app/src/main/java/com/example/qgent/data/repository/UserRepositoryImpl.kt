package com.example.qgent.data.repository

import com.example.qgent.data.api.QgApiService
import com.example.qgent.data.model.CreateTeamRequest
import com.example.qgent.data.model.ProjectDto
import com.example.qgent.data.model.TeamDto
import com.example.qgent.data.model.UserProfileDto
import com.example.qgent.data.model.toDataOrThrow

class UserRepositoryImpl(private val service: QgApiService) : UserRepository {

    override suspend fun getUserProfile(): Result<UserProfileDto> = apiCall {
        service.getUserProfile().toDataOrThrow()
    }

    override suspend fun getTeams(): Result<List<TeamDto>> = apiCall {
        service.getTeams().toDataOrThrow()
    }

    override suspend fun getProjects(teamId: String): Result<List<ProjectDto>> = apiCall {
        service.getProjects(teamId).toDataOrThrow()
    }

    override suspend fun createTeam(name: String, description: String?, idempotencyKey: String): Result<TeamDto> =
        apiCall {
            service.createTeam(idempotencyKey, CreateTeamRequest(name, description)).toDataOrThrow()
        }
}
