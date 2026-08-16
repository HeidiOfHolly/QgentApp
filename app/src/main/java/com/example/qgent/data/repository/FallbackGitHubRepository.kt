package com.example.qgent.data.repository

import com.example.qgent.data.model.BindProjectRepositoryRequest

/** 真实请求失败时回退到 mock，保证演示环境可用 */
class FallbackGitHubRepository(
    real: GitHubRepository,
    mock: GitHubRepository
) : GitHubRepository {
    private val fb = FallbackDelegate(real, mock)

    override suspend fun createInstallation(teamId: String, idempotencyKey: String) =
        fb.call { createInstallation(teamId, idempotencyKey) }

    override suspend fun getInstallations(teamId: String) = fb.call { getInstallations(teamId) }

    override suspend fun deleteInstallation(teamId: String, installationId: String, idempotencyKey: String) =
        fb.call { deleteInstallation(teamId, installationId, idempotencyKey) }

    override suspend fun syncInstallation(teamId: String, installationId: String, idempotencyKey: String) =
        fb.call { syncInstallation(teamId, installationId, idempotencyKey) }

    override suspend fun revokeGithubRepository(teamId: String, repositoryId: String, idempotencyKey: String) =
        fb.call { revokeGithubRepository(teamId, repositoryId, idempotencyKey) }

    override suspend fun getGithubRepositories(teamId: String) = fb.call { getGithubRepositories(teamId) }

    override suspend fun getProjectRepositories(projectId: String) = fb.call { getProjectRepositories(projectId) }

    override suspend fun bindProjectRepository(projectId: String, idempotencyKey: String, body: BindProjectRepositoryRequest) =
        fb.call { bindProjectRepository(projectId, idempotencyKey, body) }

    override suspend fun unbindProjectRepository(projectId: String, projectRepositoryId: String, idempotencyKey: String) =
        fb.call { unbindProjectRepository(projectId, projectRepositoryId, idempotencyKey) }
}
