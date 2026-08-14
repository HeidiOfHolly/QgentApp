package com.example.qgent.data.repository

import com.example.qgent.data.api.QgApiService
import com.example.qgent.data.model.BindProjectRepositoryRequest
import com.example.qgent.data.model.GitHubInstallationDto
import com.example.qgent.data.model.GitHubInstallationUrlDto
import com.example.qgent.data.model.GitHubRepositoryDto
import com.example.qgent.data.model.ProjectRepositoryDto
import com.example.qgent.data.model.toDataOrThrow
import com.example.qgent.data.model.toUnitOrThrow

class GitHubRepositoryImpl(private val service: QgApiService) : GitHubRepository {

    override suspend fun createInstallation(teamId: String, idempotencyKey: String): Result<GitHubInstallationUrlDto> =
        runCatching { service.createInstallation(teamId, idempotencyKey, "MOBILE").toDataOrThrow() }

    override suspend fun getInstallations(teamId: String): Result<List<GitHubInstallationDto>> =
        runCatching { service.getInstallations(teamId).toDataOrThrow() }

    override suspend fun deleteInstallation(teamId: String, installationId: String, idempotencyKey: String): Result<Unit> =
        runCatching { service.deleteInstallation(teamId, installationId, idempotencyKey).toUnitOrThrow() }

    override suspend fun syncInstallation(teamId: String, installationId: String, idempotencyKey: String): Result<GitHubInstallationDto> =
        runCatching { service.syncInstallation(teamId, installationId, idempotencyKey).toDataOrThrow() }

    override suspend fun getGithubRepositories(teamId: String): Result<List<GitHubRepositoryDto>> =
        runCatching { service.getGithubRepositories(teamId).toDataOrThrow() }

    override suspend fun getProjectRepositories(projectId: String): Result<List<ProjectRepositoryDto>> =
        runCatching { service.getProjectRepositories(projectId).toDataOrThrow() }

    override suspend fun bindProjectRepository(projectId: String, idempotencyKey: String, body: BindProjectRepositoryRequest): Result<ProjectRepositoryDto> =
        runCatching { service.bindProjectRepository(projectId, idempotencyKey, body).toDataOrThrow() }

    override suspend fun unbindProjectRepository(projectId: String, projectRepositoryId: String, idempotencyKey: String): Result<Unit> =
        runCatching { service.unbindProjectRepository(projectId, projectRepositoryId, idempotencyKey).toUnitOrThrow() }
}
