package com.example.qgent.viewmodel

import androidx.lifecycle.LiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.asLiveData
import androidx.lifecycle.viewModelScope
import com.example.qgent.data.model.GitHubRepositoryDto
import com.example.qgent.data.model.PersonalGithubOAuthDto
import com.example.qgent.data.model.TeamMemberDto
import com.example.qgent.data.repository.GitHubRepository
import com.example.qgent.data.repository.UserRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/**
 * 新建项目向导的 activity 级共享态：表单草稿（名称/简介/已选成员/已选仓库）
 * 与候选列表（团队成员、已授权仓库），供表单页与共享选择页共用。
 */
class NewProjectViewModel(
    private val userRepo: UserRepository,
    private val githubRepo: GitHubRepository
) : ViewModel() {

    data class Draft(
        val name: String = "",
        val description: String = "",
        val selectedMembers: List<TeamMemberDto> = emptyList(),
        val selectedRepos: List<GitHubRepositoryDto> = emptyList(),
        // 自动新建的仓库名列表（与 selectedRepos 二选一，清单一）
        val newRepoNames: List<String> = emptyList(),
        // 自动建仓仓库可见性（§50：个人 OAuth 能力字段控制，默认私有）
        val isPrivate: Boolean = true
    )

    private val _draft = MutableStateFlow(Draft())
    val draft: LiveData<Draft> = _draft.asLiveData()

    private val _members = MutableStateFlow<List<TeamMemberDto>>(emptyList())
    val members: LiveData<List<TeamMemberDto>> = _members.asLiveData()

    private val _repos = MutableStateFlow<List<GitHubRepositoryDto>>(emptyList())
    val repos: LiveData<List<GitHubRepositoryDto>> = _repos.asLiveData()

    private val _loadError = MutableStateFlow<String?>(null)
    val loadError: LiveData<String?> = _loadError.asLiveData()

    /** 个人 GitHub OAuth 授权状态（§50.4：控制自动建仓可用性与可见性）；null = 尚未加载 */
    private val _oauthStatus = MutableStateFlow<PersonalGithubOAuthDto?>(null)
    val oauthStatus: LiveData<PersonalGithubOAuthDto?> = _oauthStatus.asLiveData()

    private var teamId = ""

    fun init(teamId: String) {
        if (this.teamId == teamId) return
        this.teamId = teamId
        loadMembers()
        loadRepos()
        refreshOAuthStatus()
    }

    /** 重新查询个人 GitHub OAuth 状态（进入仓库选择页 / 从绑定页返回时调用） */
    fun refreshOAuthStatus() {
        viewModelScope.launch {
            githubRepo.getPersonalOAuthStatus()
                .onSuccess { _oauthStatus.value = it }
                .onFailure {
                    // 查询失败保持原状态（null=未加载），自动建仓区按未授权置灰处理
                }
        }
    }

    /** 自动建仓是否可用：已授权且 READY（§50.4，仅作前端交互控制，后端仍会强校验） */
    fun canAutoCreateRepo(): Boolean {
        val s = _oauthStatus.value ?: return false
        return s.authorized && s.personalRepositorySetup == "READY"
    }

    /** 候选列表加载失败提示一次性事件，UI 观察后消费 */
    fun consumeLoadError() {
        _loadError.value = null
    }

    fun updateName(name: String) {
        _draft.value = _draft.value.copy(name = name)
    }

    fun updateDescription(description: String) {
        _draft.value = _draft.value.copy(description = description)
    }

    fun setSelectedMembers(list: List<TeamMemberDto>) {
        _draft.value = _draft.value.copy(selectedMembers = list)
    }

    fun setSelectedRepos(list: List<GitHubRepositoryDto>) {
        _draft.value = _draft.value.copy(selectedRepos = list)
    }

    fun setNewRepoNames(list: List<String>) {
        _draft.value = _draft.value.copy(newRepoNames = list)
    }

    fun setRepoVisibility(isPrivate: Boolean) {
        _draft.value = _draft.value.copy(isPrivate = isPrivate)
    }

    private fun loadMembers() {
        viewModelScope.launch {
            userRepo.getTeamMembers(teamId)
                .onSuccess { _members.value = it }
                .onFailure { e -> _loadError.value = e.message ?: "加载团队成员失败，请稍后重试" }
        }
    }

    private fun loadRepos() {
        viewModelScope.launch {
            // 文档 §6：只允许绑定 AUTHORIZED、未归档、默认分支非空且对应 Installation ACTIVE 的仓库
            val installations = githubRepo.getInstallations(teamId)
            if (installations.isFailure) {
                _loadError.value = installations.exceptionOrNull()?.message ?: "加载 GitHub 安装信息失败，请稍后重试"
                return@launch
            }
            val repos = githubRepo.getGithubRepositories(teamId)
            if (repos.isFailure) {
                _loadError.value = repos.exceptionOrNull()?.message ?: "加载授权仓库失败，请稍后重试"
                return@launch
            }
            // 排除已绑定到本团队任一项目的仓库：只展示已授权且未绑定的
            val projects = userRepo.getProjects(teamId)
            if (projects.isFailure) {
                _loadError.value = projects.exceptionOrNull()?.message ?: "加载项目失败，请稍后重试"
                return@launch
            }
            val activeInstallationIds = installations.getOrThrow()
                .filter { it.status == "ACTIVE" }
                .map { it.id }
                .toSet()
            val authorized = repos.getOrThrow()
                .filter {
                    it.authorizationStatus == "AUTHORIZED" &&
                        !it.archived &&
                        !it.defaultBranch.isNullOrBlank() &&
                        it.installationId in activeInstallationIds
                }
            val boundIds = projects.getOrThrow()
                .mapNotNull { githubRepo.getProjectRepositories(it.id).getOrNull() }
                .flatten()
                .map { it.repositoryId }
                .toSet()
            _repos.value = authorized.filter { it.id !in boundIds }
        }
    }
}
