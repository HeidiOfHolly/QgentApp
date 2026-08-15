package com.example.qgent.viewmodel

import androidx.lifecycle.LiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.asLiveData
import androidx.lifecycle.viewModelScope
import com.example.qgent.data.model.GitHubRepositoryDto
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
        val selectedRepos: List<GitHubRepositoryDto> = emptyList()
    )

    private val _draft = MutableStateFlow(Draft())
    val draft: LiveData<Draft> = _draft.asLiveData()

    private val _members = MutableStateFlow<List<TeamMemberDto>>(emptyList())
    val members: LiveData<List<TeamMemberDto>> = _members.asLiveData()

    private val _repos = MutableStateFlow<List<GitHubRepositoryDto>>(emptyList())
    val repos: LiveData<List<GitHubRepositoryDto>> = _repos.asLiveData()

    private var teamId = ""

    fun init(teamId: String) {
        if (this.teamId == teamId) return
        this.teamId = teamId
        loadMembers()
        loadRepos()
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

    private fun loadMembers() {
        viewModelScope.launch {
            userRepo.getTeamMembers(teamId).onSuccess { _members.value = it }
        }
    }

    private fun loadRepos() {
        viewModelScope.launch {
            githubRepo.getGithubRepositories(teamId).onSuccess { _repos.value = it }
        }
    }
}
