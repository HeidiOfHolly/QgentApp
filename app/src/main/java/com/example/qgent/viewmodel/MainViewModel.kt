package com.example.qgent.viewmodel

import androidx.lifecycle.LiveData
import androidx.lifecycle.asLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.qgent.data.model.TeamDto
import com.example.qgent.data.repository.ChatRepository
import com.example.qgent.data.repository.UserRepository
import com.example.qgent.model.ChatGroup
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/**
 * Activity 级共享工作区状态：当前团队、当前项目、群聊列表。
 * 个人中心抽屉（切团队/切项目）与群聊列表页（显示所在团队）共用。
 *
 * 依赖构造注入（由 AppContainer 装配），mock 回退由数据层 Fallback Repository 承担；
 * 内部用 StateFlow 管理状态，通过 asLiveData() 暴露给界面观察。
 */
class MainViewModel(
    private val userRepo: UserRepository,
    private val chatRepo: ChatRepository
) : ViewModel() {

    // ── 当前团队 / 项目（页面间共享） ──

    private val _currentTeam = MutableStateFlow("")
    val currentTeam: LiveData<String> = _currentTeam.asLiveData()

    private val _currentProject = MutableStateFlow("")
    val currentProject: LiveData<String> = _currentProject.asLiveData()

    // ── 团队列表 ──

    private val _teams = MutableStateFlow<List<String>>(emptyList())
    val teams: LiveData<List<String>> = _teams.asLiveData()

    // ── 团队详情列表（含 role，用于区分“我创建的 / 我加入的”） ──

    private val _teamDtos = MutableStateFlow<List<TeamDto>>(emptyList())
    val teamDtos: LiveData<List<TeamDto>> = _teamDtos.asLiveData()

    // ── 当前项目下的群聊列表 ──

    private val _groups = MutableStateFlow<List<ChatGroup>>(emptyList())
    val groups: LiveData<List<ChatGroup>> = _groups.asLiveData()

    // ── 项目列表（按当前团队） ──

    private val _projects = MutableStateFlow<List<String>>(emptyList())
    val projects: LiveData<List<String>> = _projects.asLiveData()

    // ── 缓存：name → id 映射（用于 API 调用时反查） ──

    private var projectNameToId = mutableMapOf<String, String>()

    // ── 已读群聊 ID 集合（跨项目切换保持） ──

    private val readGroupIds = mutableSetOf<String>()

    // ── 当前已加载 projects 对应的团队（防止串数据） ──

    private var loadedProjectsTeam: String? = null

    // ── 用户权限（当前为演示阶段默认 Project Admin，接入真实权限后替换） ──

    val isProjectAdmin: Boolean = true

    init {
        loadTeams()
    }

    fun setCurrentTeam(team: String) {
        if (_currentTeam.value == team) return
        _currentTeam.value = team
        loadedProjectsTeam = null  // 失效旧缓存，防止 projectsOf 串数据
        loadProjects(team) {
            val firstProject = projectsOf(team).firstOrNull() ?: ""
            _currentProject.value = firstProject
            if (firstProject.isNotEmpty()) loadGroups(firstProject)
        }
    }

    fun setCurrentProject(project: String) {
        if (_currentProject.value != project) {
            _currentProject.value = project
            loadGroups(project)
        }
    }

    private fun projectsOf(team: String): List<String> =
        if (loadedProjectsTeam == team) _projects.value else emptyList()

    /** 标记群聊为已读（未读数清零，并持久化到已读集合） */
    fun markAsRead(groupId: String) {
        readGroupIds.add(groupId)
        _groups.value = _groups.value.map {
            if (it.id == groupId) it.copy(unread = 0) else it
        }
    }

    /** 切换群聊置顶状态，重新排序后发出 */
    fun togglePin(groupId: String) {
        val updated = _groups.value.map {
            if (it.id == groupId) it.copy(isPinned = !it.isPinned) else it
        }
        _groups.value = sortGroups(updated)
    }

    // ── 排序：置顶优先（按 lastActiveTime 倒序），非置顶按 lastActiveTime 倒序 ──

    private fun sortGroups(groups: List<ChatGroup>): List<ChatGroup> =
        groups.sortedWith(
            compareByDescending<ChatGroup> { it.isPinned }
                .thenByDescending { it.lastActiveTime }
        )

    // ── 数据加载（Repository 已内置 mock 回退） ──

    private fun loadTeams() {
        viewModelScope.launch {
            userRepo.getTeams().onSuccess { dtos ->
                _teams.value = dtos.map { it.name }
                _teamDtos.value = dtos
                // 无当前选中时自动选第一个团队（替代原硬编码默认值）
                if (_currentTeam.value.isEmpty()) {
                    dtos.firstOrNull()?.name?.let { setCurrentTeam(it) }
                }
            }
        }
    }

    private fun loadProjects(team: String, onLoaded: (() -> Unit)? = null) {
        viewModelScope.launch {
            val teamDto = _teamDtos.value.find { it.name == team }
            if (teamDto != null) {
                userRepo.getProjects(teamDto.id).onSuccess { projectDtos ->
                    _projects.value = projectDtos.map { it.name }
                    projectDtos.forEach { projectNameToId[it.name] = it.id }
                    loadedProjectsTeam = team
                    onLoaded?.invoke()
                    return@launch
                }
            }
            _projects.value = emptyList()
            loadedProjectsTeam = team
            onLoaded?.invoke()
        }
    }

    private fun loadGroups(projectName: String) {
        viewModelScope.launch {
            // 优先按 projectId 拉取；空 / 失败时按项目名兜底（走数据层 mock），避免群列表闪空
            val pid = projectNameToId[projectName]
            val groups = pid?.let { chatRepo.getGroups(it).getOrNull() } ?: emptyList()
            val result = if (groups.isNotEmpty()) {
                groups
            } else {
                chatRepo.getGroups(projectName).getOrNull() ?: emptyList()
            }
            _groups.value = applyReadState(sortGroups(result.map { dto ->
                ChatGroup(dto.id, dto.title, dto.lastMessage ?: "", dto.updatedAt, 0)
            }))
        }
    }

    /** 对已读群聊清零未读数 */
    private fun applyReadState(groups: List<ChatGroup>): List<ChatGroup> =
        groups.map { if (it.id in readGroupIds) it.copy(unread = 0) else it }
}
