package com.example.qgent.data.repository

import com.example.qgent.data.model.BindProjectRepositoryRequest
import com.example.qgent.data.model.CreateProjectRepositoryRequest
import com.example.qgent.data.model.GitHubInstallationDto
import com.example.qgent.data.model.GitHubInstallationUrlDto
import com.example.qgent.data.model.GitHubOAuthStartResponse
import com.example.qgent.data.model.GitHubRepositoryDto
import com.example.qgent.data.model.PersonalGithubOAuthDto
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

    /** Team Owner only: creates a GitHub repository and binds it to this project in one operation. */
    suspend fun createAndBindProjectRepository(projectId: String, idempotencyKey: String, body: CreateProjectRepositoryRequest): Result<ProjectRepositoryDto>

    /** 解绑项目仓库（204） */
    suspend fun unbindProjectRepository(projectId: String, projectRepositoryId: String, idempotencyKey: String): Result<Unit>

    // ── 个人 GitHub OAuth（§50：个人建仓授权链路，与团队 App Installation 相互独立） ──

    /** 生成个人 GitHub OAuth 授权地址（§50.2，client=MOBILE，body 空 {}） */
    suspend fun startPersonalOAuth(idempotencyKey: String): Result<GitHubOAuthStartResponse>

    /** 查询当前用户个人 GitHub 授权状态（§50.4，不返回 Token） */
    suspend fun getPersonalOAuthStatus(): Result<PersonalGithubOAuthDto>

    /** 撤销当前用户个人 GitHub 授权（§50.5，成功 204；幂等） */
    suspend fun revokePersonalOAuth(idempotencyKey: String): Result<Unit>
}
