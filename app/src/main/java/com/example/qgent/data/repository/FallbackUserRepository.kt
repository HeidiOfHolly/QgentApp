package com.example.qgent.data.repository

/** 真实请求失败时回退到 mock，保证演示环境可用 */
class FallbackUserRepository(
    real: UserRepository,
    mock: UserRepository
) : UserRepository {
    private val fb = FallbackDelegate(real, mock)

    override suspend fun getUserProfile() = fb.call { getUserProfile() }

    override suspend fun getTeams() = fb.call { getTeams() }

    override suspend fun getTeamMembers(teamId: String, cursor: String?, limit: Int) = fb.call { getTeamMembers(teamId, cursor, limit) }

    override suspend fun getProjects(teamId: String) = fb.call { getProjects(teamId) }

    override suspend fun createProject(teamId: String, name: String, description: String?, idempotencyKey: String) =
        fb.call { createProject(teamId, name, description, idempotencyKey) }

    override suspend fun addProjectMember(projectId: String, userId: String, idempotencyKey: String) =
        fb.call { addProjectMember(projectId, userId, idempotencyKey) }

    override suspend fun createTeam(name: String, description: String?, idempotencyKey: String) =
        fb.call { createTeam(name, description, idempotencyKey) }
}
