package com.example.qgent.data.repository

/** 真实请求失败时回退到 mock，保证演示环境可用 */
class FallbackUserRepository(
    real: UserRepository,
    mock: UserRepository
) : UserRepository {
    private val fb = FallbackDelegate(real, mock)

    override suspend fun getUserProfile() = fb.call { getUserProfile() }

    override suspend fun getTeams() = fb.call { getTeams() }

    override suspend fun getProjects(teamId: String) = fb.call { getProjects(teamId) }

    override suspend fun createTeam(name: String, description: String?, idempotencyKey: String) =
        fb.call { createTeam(name, description, idempotencyKey) }
}
