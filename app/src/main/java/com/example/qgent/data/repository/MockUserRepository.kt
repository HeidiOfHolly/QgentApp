package com.example.qgent.data.repository

import com.example.qgent.data.datasource.MockDataSource
import com.example.qgent.data.model.ProjectDto
import com.example.qgent.data.model.ProjectMemberDto
import com.example.qgent.data.model.TeamDto
import com.example.qgent.data.model.TeamMemberDto
import com.example.qgent.data.model.UserProfileDto

class MockUserRepository : UserRepository {

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

    override suspend fun getProjects(teamId: String): Result<List<ProjectDto>> =
        Result.success(MockDataSource.projectsOf(teamId))

    override suspend fun createProject(
        teamId: String,
        name: String,
        description: String?,
        idempotencyKey: String
    ): Result<ProjectDto> = Result.success(
        ProjectDto("mock-project-${System.currentTimeMillis()}", teamId, name, description, "ACTIVE")
    )

    override suspend fun addProjectMember(
        projectId: String,
        userId: String,
        idempotencyKey: String
    ): Result<ProjectMemberDto> = Result.success(ProjectMemberDto(userId, "PROJECT_MEMBER"))

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
