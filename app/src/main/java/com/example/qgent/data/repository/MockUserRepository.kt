package com.example.qgent.data.repository

import com.example.qgent.data.datasource.MockDataSource
import com.example.qgent.data.model.ProjectDto
import com.example.qgent.data.model.TeamDto
import com.example.qgent.data.model.UserProfileDto

class MockUserRepository : UserRepository {

    override suspend fun getUserProfile(): Result<UserProfileDto> = Result.success(
        UserProfileDto("mock-user", "演示用户", "demo@example.com", null, githubLinked = false)
    )

    override suspend fun getTeams(): Result<List<TeamDto>> = Result.success(MockDataSource.teams)

    override suspend fun getProjects(teamId: String): Result<List<ProjectDto>> =
        Result.success(MockDataSource.projectsOf(teamId))

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
}
