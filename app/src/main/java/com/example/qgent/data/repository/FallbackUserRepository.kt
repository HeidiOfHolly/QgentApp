package com.example.qgent.data.repository

import com.example.qgent.data.model.ProjectDto
import com.example.qgent.data.model.TeamDto
import com.example.qgent.data.model.UserProfileDto

/** 真实请求失败时回退到 mock，保证演示环境可用 */
class FallbackUserRepository(
    private val real: UserRepository,
    private val mock: UserRepository
) : UserRepository {

    override suspend fun getUserProfile(): Result<UserProfileDto> =
        real.getUserProfile().orFallback { mock.getUserProfile() }

    override suspend fun getTeams(): Result<List<TeamDto>> =
        real.getTeams().orFallback { mock.getTeams() }

    override suspend fun getProjects(teamId: String): Result<List<ProjectDto>> =
        real.getProjects(teamId).orFallback { mock.getProjects(teamId) }

    override suspend fun createTeam(name: String, description: String?, idempotencyKey: String): Result<TeamDto> =
        real.createTeam(name, description, idempotencyKey)
            .orFallback { mock.createTeam(name, description, idempotencyKey) }
}
