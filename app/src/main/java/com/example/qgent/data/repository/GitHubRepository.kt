package com.example.qgent.data.repository

import com.example.qgent.data.model.BindProjectRepositoryRequest
import com.example.qgent.data.model.GitHubInstallationDto
import com.example.qgent.data.model.GitHubInstallationUrlDto
import com.example.qgent.data.model.GitHubRepositoryDto
import com.example.qgent.data.model.ProjectRepositoryDto

/** GitHub 集成仓库：团队级安装授权 + 项目仓库绑定 */
interface GitHubRepository {
    /** 生成 GitHub App 安装跳转链接（POST installations，需幂等键） */
    suspend fun createInstallation(teamId: String, idempotencyKey: String): Result<GitHubInstallationUrlDto>

    /** 团队已安装的 GitHub App 列表 */
    suspend fun getInstallations(teamId: String): Result<List<GitHubInstallationDto>>

    /** 解除团队安装记录（204） */
    suspend fun deleteInstallation(teamId: String, installationId: String, idempotencyKey: String): Result<Unit>

    /** 手动刷新 Installation 与授权仓库元数据 */
    suspend fun syncInstallation(teamId: String, installationId: String, idempotencyKey: String): Result<GitHubInstallationDto>

    /** 团队被授权的 GitHub 仓库列表 */
    suspend fun getGithubRepositories(teamId: String): Result<List<GitHubRepositoryDto>>

    /** 撤销单个仓库授权（团队级，repositoryId = 授权仓本地 UUID） */
    suspend fun revokeGithubRepository(teamId: String, repositoryId: String, idempotencyKey: String): Result<Unit>

    /** 项目已绑定的仓库列表 */
    suspend fun getProjectRepositories(projectId: String): Result<List<ProjectRepositoryDto>>

    /** 绑定团队已授权仓库到项目 */
    suspend fun bindProjectRepository(projectId: String, idempotencyKey: String, body: BindProjectRepositoryRequest): Result<ProjectRepositoryDto>

    /** 解绑项目仓库（204） */
    suspend fun unbindProjectRepository(projectId: String, projectRepositoryId: String, idempotencyKey: String): Result<Unit>
}
