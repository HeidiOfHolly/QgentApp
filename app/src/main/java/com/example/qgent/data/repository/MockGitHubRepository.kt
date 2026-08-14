package com.example.qgent.data.repository

import com.example.qgent.data.datasource.MockDataSource
import com.example.qgent.data.model.BindProjectRepositoryRequest
import com.example.qgent.data.model.GitHubInstallationDto
import com.example.qgent.data.model.GitHubInstallationUrlDto
import com.example.qgent.data.model.GitHubRepositoryDto
import com.example.qgent.data.model.ProjectRepositoryDto

/** mock GitHub：返回文档示例的 Installation / Repository 数据，写操作不支持 */
class MockGitHubRepository : GitHubRepository {

    override suspend fun createInstallation(teamId: String, idempotencyKey: String): Result<GitHubInstallationUrlDto> =
        Result.success(
            GitHubInstallationUrlDto(
                installationUrl = "https://github.com/apps/qgents/installations/new",
                expiresAt = "2026-08-13T11:00:00Z"
            )
        )

    override suspend fun getInstallations(teamId: String): Result<List<GitHubInstallationDto>> =
        Result.success(MockDataSource.githubInstallations)

    override suspend fun deleteInstallation(teamId: String, installationId: String, idempotencyKey: String): Result<Unit> =
        Result.failure(UnsupportedOperationException("mock 不支持解除安装"))

    override suspend fun syncInstallation(teamId: String, installationId: String, idempotencyKey: String): Result<GitHubInstallationDto> =
        Result.success(MockDataSource.githubInstallations.first())

    override suspend fun getGithubRepositories(teamId: String): Result<List<GitHubRepositoryDto>> =
        Result.success(MockDataSource.githubRepositories)

    override suspend fun getProjectRepositories(projectId: String): Result<List<ProjectRepositoryDto>> =
        Result.success(MockDataSource.projectRepositories)

    override suspend fun bindProjectRepository(projectId: String, idempotencyKey: String, body: BindProjectRepositoryRequest): Result<ProjectRepositoryDto> =
        Result.failure(UnsupportedOperationException("mock 不支持绑定仓库"))

    override suspend fun unbindProjectRepository(projectId: String, projectRepositoryId: String, idempotencyKey: String): Result<Unit> =
        Result.failure(UnsupportedOperationException("mock 不支持解绑仓库"))
}
