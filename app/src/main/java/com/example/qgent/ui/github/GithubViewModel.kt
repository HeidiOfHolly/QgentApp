package com.example.qgent.ui.github

import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.asLiveData
import androidx.lifecycle.viewModelScope
import com.example.qgent.data.model.ApiException
import com.example.qgent.data.model.GitHubInstallationDto
import com.example.qgent.data.model.GitHubRepositoryDto
import com.example.qgent.data.repository.GitHubRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * GitHub 集成 ViewModel：发起安装链接、刷新 Installation/Repository 列表。
 * 幂等键由每次用户操作生成一次 UUID，同次操作重试复用、重新点击生成新键（符合接口契约）。
 */
class GithubViewModel(private val repo: GitHubRepository) : ViewModel() {

    data class GithubUiState(
        val loading: Boolean = false,
        val installationUrl: String? = null,
        val installations: List<GitHubInstallationDto> = emptyList(),
        val repositories: List<GitHubRepositoryDto> = emptyList(),
        val repoCounts: Map<String, Int> = emptyMap(),
        val installed: Boolean = false,
        val error: String? = null
    )

    private val _uiState = MutableStateFlow(GithubUiState())
    val uiState: LiveData<GithubUiState> = _uiState.asLiveData()

    /** 当前正在等待授权的安装链接对应的幂等键（回调刷新时复用，仅一次） */
    private var pendingInstallationKey: String? = null

    /** 发起安装：生成链接并打开 GitHub 授权页 */
    fun createInstallationUrl(teamId: String) {
        if (_uiState.value.loading) return
        val key = UUID.randomUUID().toString()
        pendingInstallationKey = key
        _uiState.value = _uiState.value.copy(loading = true, error = null)
        viewModelScope.launch {
            repo.createInstallation(teamId, key)
                .onSuccess { url ->
                    _uiState.value = _uiState.value.copy(
                        loading = false,
                        installationUrl = url.installationUrl
                    )
                }
                .onFailure { e ->
                    _uiState.value = _uiState.value.copy(
                        loading = false,
                        error = if (e is ApiException && e.code == "GITHUB_INSTALLATION_TEAM_CONFLICT") {
                            "该 GitHub 账号已绑定其他团队，无法重复授权"
                        } else {
                            e.message ?: "获取安装链接失败，请稍后重试"
                        }
                    )
                }
        }
    }

    /** 拉取团队已安装列表（授权回调后 onResume 调用，检测新安装） */
    fun refreshInstallations(teamId: String) {
        viewModelScope.launch {
            repo.getInstallations(teamId).onSuccess { installations ->
                val prevIds = _uiState.value.installations.map { it.id }.toSet()
                val installedNow = pendingInstallationKey != null &&
                    installations.any { it.id !in prevIds }
                pendingInstallationKey = null
                _uiState.value = _uiState.value.copy(
                    installations = installations,
                    installed = installedNow
                )
            }
        }
    }

    /** 拉取团队授权仓库列表（只保留 AUTHORIZED，REVOKED 是网页端已撤销授权的，不显示） */
    fun loadRepositories(teamId: String) {
        viewModelScope.launch {
            repo.getGithubRepositories(teamId).onSuccess { repos ->
                Log.d("GithubViewModel", "repos raw: ${repos.map { "${it.fullName}=${it.authorizationStatus}" }}")
                _uiState.value = _uiState.value.copy(
                    repositories = repos.filter { it.authorizationStatus == "AUTHORIZED" }
                )
            }
        }
    }

    /** 逐团队拉取授权仓库数量，供 GitHub 页团队卡片展示真实仓库数（只统计 AUTHORIZED） */
    fun loadRepositoryCounts(teamIds: List<String>) {
        teamIds.forEach { teamId ->
            viewModelScope.launch {
                repo.getGithubRepositories(teamId).onSuccess { repos ->
                    _uiState.value = _uiState.value.copy(
                        repoCounts = _uiState.value.repoCounts +
                            (teamId to repos.count { it.authorizationStatus == "AUTHORIZED" })
                    )
                }
            }
        }
    }

    /**
     * 从 GitHub 网页返回后强制重同步所有 ACTIVE 安装的仓库元数据。
     * 网页端「增加/删除仓库」不会自动同步到后端，必须靠 syncInstallation 重拉，
     * 否则删除的仓库在后端始终停留在 AUTHORIZED，不会消失。
     */
    fun syncInstallations(teamId: String) {
        viewModelScope.launch {
            repo.getInstallations(teamId).onSuccess { installations ->
                _uiState.value = _uiState.value.copy(installations = installations)
                installations.filter { it.status == "ACTIVE" }.forEach { inst ->
                    repo.syncInstallation(teamId, inst.id, UUID.randomUUID().toString())
                }
                loadRepositories(teamId)
            }
        }
    }

    /** 手动刷新授权仓库元数据，成功后重拉 Installation 与 Repository 列表 */
    fun syncInstallation(teamId: String, installationId: String) {
        if (_uiState.value.loading) return
        _uiState.value = _uiState.value.copy(loading = true, error = null)
        viewModelScope.launch {
            repo.syncInstallation(teamId, installationId, UUID.randomUUID().toString())
                .onSuccess {
                    _uiState.value = _uiState.value.copy(loading = false)
                    refreshInstallations(teamId)
                    loadRepositories(teamId)
                }
                .onFailure { e ->
                    _uiState.value = _uiState.value.copy(
                        loading = false,
                        error = e.message ?: "刷新失败，请稍后重试"
                    )
                }
        }
    }

    fun consumeInstalled() {
        _uiState.value = _uiState.value.copy(installed = false)
    }

    fun consumeInstallationUrl() {
        _uiState.value = _uiState.value.copy(installationUrl = null)
    }

    fun consumeError() {
        _uiState.value = _uiState.value.copy(error = null)
    }
}
