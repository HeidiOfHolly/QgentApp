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
class GithubViewModel(
    private val repo: GitHubRepository
) : ViewModel() {

    /** 解除安装被 409（仍有仓库绑定）拦截后的确认请求 */
    data class UninstallConfirm(val teamId: String)

    data class GithubUiState(
        val loading: Boolean = false,
        val installationUrl: String? = null,
        val installations: List<GitHubInstallationDto> = emptyList(),
        val repositories: List<GitHubRepositoryDto> = emptyList(),
        val repoCounts: Map<String, Int> = emptyMap(),
        val installed: Boolean = false,
        val uninstallDone: Boolean = false,
        val uninstallConfirm: UninstallConfirm? = null,
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

    /**
     * 处理 GitHub 回调结果（由授权页 WebView 拦截回跳 URL 获得）：
     * 成功（installed=1）→ 置位 installed 提示成功；冲突（conflict）→ 写入 error 展示后端 message。
     * 随后统一刷新安装/仓库列表，保持页面与后端一致。
     */
    fun handleCallbackResult(teamId: String, installed: Boolean, conflictMessage: String?) {
        // 已直接拿到回调结果，清除挂起的幂等键，避免 onResume 的 ID 检测重复提示
        pendingInstallationKey = null
        _uiState.value = _uiState.value.copy(installed = installed, error = conflictMessage)
        refreshInstallations(teamId)
        loadRepositories(teamId)
    }

    /** 拉取团队已安装列表（授权回调后 onResume 调用，检测新安装） */
    fun refreshInstallations(teamId: String) {
        viewModelScope.launch {
            repo.getInstallations(teamId)
                .onSuccess { installations ->
                    val prevIds = _uiState.value.installations.map { it.id }.toSet()
                    val installedNow = pendingInstallationKey != null &&
                        installations.any { it.id !in prevIds }
                    pendingInstallationKey = null
                    _uiState.value = _uiState.value.copy(
                        installations = installations,
                        installed = installedNow
                    )
                }
                .onFailure { e -> reportRefreshError(e) }
        }
    }

    /** 拉取团队授权仓库列表（只保留 AUTHORIZED，REVOKED 是网页端已撤销授权的，不显示） */
    fun loadRepositories(teamId: String) {
        viewModelScope.launch {
            repo.getGithubRepositories(teamId)
                .onSuccess { repos ->
                    Log.d("GithubViewModel", "repos raw: ${repos.map { "${it.fullName}=${it.authorizationStatus}" }}")
                    _uiState.value = _uiState.value.copy(
                        repositories = repos.filter { it.authorizationStatus == "AUTHORIZED" }
                    )
                }
                .onFailure { e -> reportRefreshError(e) }
        }
    }

    /** 逐团队拉取授权仓库数量，供 GitHub 页团队卡片展示真实仓库数（只统计 AUTHORIZED） */
    fun loadRepositoryCounts(teamIds: List<String>) {
        teamIds.forEach { teamId ->
            viewModelScope.launch {
                repo.getGithubRepositories(teamId)
                    .onSuccess { repos ->
                        _uiState.value = _uiState.value.copy(
                            repoCounts = _uiState.value.repoCounts +
                                (teamId to repos.count { it.authorizationStatus == "AUTHORIZED" })
                        )
                    }
                    .onFailure { e ->
                        // 仓库数徽章是后台统计，失败不打扰用户，仅留日志排查
                        Log.w("GithubViewModel", "拉取团队 $teamId 仓库数量失败: ${e.message}", e)
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
            repo.getInstallations(teamId)
                .onSuccess { installations ->
                    _uiState.value = _uiState.value.copy(installations = installations)
                    installations.filter { it.status == "ACTIVE" }.forEach { inst ->
                        repo.syncInstallation(teamId, inst.id, UUID.randomUUID().toString())
                            .onFailure { e -> reportRefreshError(e) }
                    }
                    loadRepositories(teamId)
                }
                .onFailure { e -> reportRefreshError(e) }
        }
    }

    /**
     * 后台自动刷新失败统一处理：仅在当前无更紧急错误（如归属冲突 message）时展示，
     * 避免覆盖冲突提示；有更紧急错误时只留日志。
     */
    private fun reportRefreshError(e: Throwable) {
        if (_uiState.value.error != null) {
            Log.w("GithubViewModel", "刷新 GitHub 信息失败(已有更紧急错误): ${e.message}", e)
            return
        }
        Log.w("GithubViewModel", "刷新 GitHub 信息失败: ${e.message}", e)
        _uiState.value = _uiState.value.copy(
            error = if (e is ApiException) e.message else (e.message ?: "刷新 GitHub 信息失败，请稍后重试")
        )
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

    /**
     * 解除团队的全部 GitHub 安装：拉取安装列表逐个删除。
     * 后端对仍被仓库绑定的安装返回 409 GITHUB_INSTALLATION_IN_USE，但实际已解除安装：
     * 首次 409 → 置 uninstallConfirm 弹确认框提示用户仍有绑定；用户确认后 force=true 重试，
     * 此时不再收集绑定列表，直接按成功收尾（刷新安装/仓库列表 + 置 uninstallDone 一次性事件）。
     */
    fun uninstallTeam(teamId: String, force: Boolean = false) {
        if (_uiState.value.loading) return
        _uiState.value = _uiState.value.copy(loading = true, error = null, uninstallConfirm = null)
        viewModelScope.launch {
            repo.getInstallations(teamId)
                .onSuccess { installations ->
                    var failed: Throwable? = null
                    for (inst in installations) {
                        repo.deleteInstallation(teamId, inst.id, UUID.randomUUID().toString())
                            .onFailure { e ->
                                failed = e
                                return@onFailure
                            }
                        if (failed != null) break
                    }
                    failed?.let { e ->
                        if (e is ApiException && e.code == "GITHUB_INSTALLATION_IN_USE") {
                            if (force) {
                                // 用户已确认强制卸载：后端 409 但实际已解除，按成功收尾
                                finishUninstall(teamId)
                            } else {
                                // 首次拦截：弹确认框提示仍有仓库绑定
                                _uiState.value = _uiState.value.copy(
                                    loading = false,
                                    uninstallConfirm = UninstallConfirm(teamId)
                                )
                            }
                        } else {
                            _uiState.value = _uiState.value.copy(
                                loading = false,
                                error = e.message ?: "解除安装失败，请稍后重试"
                            )
                        }
                        return@launch
                    }
                    finishUninstall(teamId)
                }
                .onFailure { e ->
                    _uiState.value = _uiState.value.copy(
                        loading = false,
                        error = e.message ?: "解除安装失败，请稍后重试"
                    )
                }
        }
    }

    /** 用户确认强制卸载（仍有仓库绑定）：重试解除安装，409 时直接按成功处理 */
    fun confirmForceUninstall(confirm: UninstallConfirm) {
        if (_uiState.value.loading) return
        uninstallTeam(confirm.teamId, force = true)
    }

    /** 卸载成功收尾：置 uninstallDone 一次性事件并刷新安装/仓库列表 */
    private fun finishUninstall(teamId: String) {
        _uiState.value = _uiState.value.copy(loading = false, uninstallDone = true)
        refreshInstallations(teamId)
        loadRepositories(teamId)
    }

    /** 取消强制卸载确认：仅清空待确认状态 */
    fun cancelUninstall() {
        _uiState.value = _uiState.value.copy(uninstallConfirm = null)
    }

    fun consumeInstalled() {
        _uiState.value = _uiState.value.copy(installed = false)
    }

    fun consumeInstallationUrl() {
        _uiState.value = _uiState.value.copy(installationUrl = null)
    }

    fun consumeUninstallDone() {
        _uiState.value = _uiState.value.copy(uninstallDone = false)
    }

    fun consumeError() {
        _uiState.value = _uiState.value.copy(error = null)
    }
}
