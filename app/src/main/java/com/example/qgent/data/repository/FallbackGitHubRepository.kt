package com.example.qgent.data.repository

import com.example.qgent.data.model.BindProjectRepositoryRequest
import com.example.qgent.data.model.GitHubInstallationDto
import com.example.qgent.data.model.GitHubInstallationUrlDto
import com.example.qgent.data.model.GitHubRepositoryDto
import com.example.qgent.data.model.ProjectRepositoryDto

/** 真实请求失败时回退到 mock，保证演示环境可用 */
class FallbackGitHubRepository(
    private val real: GitHubRepository,
    private val mock: GitHubRepository
) : GitHubRepository {

    override suspend fun createInstallation(teamId: String, idempotencyKey: String): Result<GitHubInstallationUrlDto> =
        real.createInstallation(teamId, idempotencyKey).orFallback { mock.createInstallation(teamId, idempotencyKey) }

    override suspend fun getInstallations(teamId: String): Result<List<GitHubInstallationDto>> =
        real.getInstallations(teamId).orFallback { mock.getInstallations(teamId) }

    override suspend fun deleteInstallation(teamId: String, installationId: String, idempotencyKey: String): Result<Unit> =
        real.deleteInstallation(teamId, installationId, idempotencyKey).orFallback { mock.deleteInstallation(teamId, installationId, idempotencyKey) }

    override suspend fun syncInstallation(teamId: String, installationId: String, idempotencyKey: String): Result<GitHubInstallationDto> =
        real.syncInstallation(teamId, installationId, idempotencyKey).orFallback { mock.syncInstallation(teamId, installationId, idempotencyKey) }

    override suspend fun getGithubRepositories(teamId: String): Result<List<GitHubRepositoryDto>> =
        real.getGithubRepositories(teamId).orFallback { mock.getGithubRepositories(teamId) }

    override suspend fun getProjectRepositories(projectId: String): Result<List<ProjectRepositoryDto>> =
        real.getProjectRepositories(projectId).orFallback { mock.getProjectRepositories(projectId) }

    override suspend fun bindProjectRepository(projectId: String, idempotencyKey: String, body: BindProjectRepositoryRequest): Result<ProjectRepositoryDto> =
        real.bindProjectRepository(projectId, idempotencyKey, body).orFallback { mock.bindProjectRepository(projectId, idempotencyKey, body) }

    override suspend fun unbindProjectRepository(projectId: String, projectRepositoryId: String, idempotencyKey: String): Result<Unit> =
        real.unbindProjectRepository(projectId, projectRepositoryId, idempotencyKey).orFallback { mock.unbindProjectRepository(projectId, projectRepositoryId, idempotencyKey) }
}
