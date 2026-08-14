package com.example.qgent.data.repository

import com.example.qgent.data.model.BindProjectRepositoryRequest
import com.example.qgent.data.model.GitHubInstallationDto
import com.example.qgent.data.model.GitHubInstallationUrlDto
import com.example.qgent.data.model.GitHubRepositoryDto
import com.example.qgent.data.model.ProjectRepositoryDto

/** mock GitHub：返回文档示例的 Installation / Repository 数据，写操作不支持 */
class MockGitHubRepository : GitHubRepository {

    private val mockInstallations = listOf(
        GitHubInstallationDto(
            id = "installation-1",
            providerInstallationId = 12345678,
            accountLogin = "Yjingwen-svg",
            accountType = "ORGANIZATION",
            status = "ACTIVE",
            installedAt = "2026-08-01T08:00:00Z",
            metadataSyncedAt = "2026-08-13T10:00:00Z"
        )
    )

    private val mockRepositories = listOf(
        GitHubRepositoryDto(
            id = "repository-1",
            installationId = "installation-1",
            providerRepositoryId = 987654321,
            fullName = "Yjingwen-svg/qgents-web",
            githubUrl = "https://github.com/Yjingwen-svg/qgents-web",
            defaultBranch = "main",
            visibility = "PRIVATE",
            archived = false,
            authorizationStatus = "AUTHORIZED",
            metadataSyncedAt = "2026-08-13T10:00:00Z"
        ),
        GitHubRepositoryDto(
            id = "repository-2",
            installationId = "installation-1",
            providerRepositoryId = 987654322,
            fullName = "Yjingwen-svg/qgents-mobile",
            githubUrl = "https://github.com/Yjingwen-svg/qgents-mobile",
            defaultBranch = "main",
            visibility = "PRIVATE",
            archived = false,
            authorizationStatus = "AUTHORIZED",
            metadataSyncedAt = "2026-08-13T10:00:00Z"
        )
    )

    override suspend fun createInstallation(teamId: String, idempotencyKey: String): Result<GitHubInstallationUrlDto> =
        Result.success(
            GitHubInstallationUrlDto(
                installationUrl = "https://github.com/apps/qgents/installations/new",
                expiresAt = "2026-08-13T11:00:00Z"
            )
        )

    override suspend fun getInstallations(teamId: String): Result<List<GitHubInstallationDto>> =
        Result.success(mockInstallations)

    override suspend fun deleteInstallation(teamId: String, installationId: String, idempotencyKey: String): Result<Unit> =
        Result.failure(UnsupportedOperationException("mock 不支持解除安装"))

    override suspend fun syncInstallation(teamId: String, installationId: String, idempotencyKey: String): Result<GitHubInstallationDto> =
        Result.success(mockInstallations.first())

    override suspend fun getGithubRepositories(teamId: String): Result<List<GitHubRepositoryDto>> =
        Result.success(mockRepositories)

    override suspend fun getProjectRepositories(projectId: String): Result<List<ProjectRepositoryDto>> =
        Result.success(
            listOf(
                ProjectRepositoryDto(
                    id = "project-binding-1",
                    repositoryId = "repository-1",
                    installationId = "installation-1",
                    providerRepositoryId = 987654321,
                    fullName = "Yjingwen-svg/qgents-web",
                    githubUrl = "https://github.com/Yjingwen-svg/qgents-web",
                    defaultBranch = "main",
                    displayName = "qgents-web",
                    authorizationStatus = "AUTHORIZED",
                    metadataSyncedAt = "2026-08-13T10:00:00Z",
                    boundAt = "2026-08-13T10:00:00Z"
                )
            )
        )

    override suspend fun bindProjectRepository(projectId: String, idempotencyKey: String, body: BindProjectRepositoryRequest): Result<ProjectRepositoryDto> =
        Result.failure(UnsupportedOperationException("mock 不支持绑定仓库"))

    override suspend fun unbindProjectRepository(projectId: String, projectRepositoryId: String, idempotencyKey: String): Result<Unit> =
        Result.failure(UnsupportedOperationException("mock 不支持解绑仓库"))
}
