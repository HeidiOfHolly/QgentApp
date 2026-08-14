package com.example.qgent.viewmodel

import androidx.lifecycle.LiveData
import androidx.lifecycle.asLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.qgent.data.model.TeamDto
import com.example.qgent.data.model.formatGroupTime
import com.example.qgent.data.model.parseRfc3339
import com.example.qgent.data.model.toAgent
import com.example.qgent.data.model.toSummary
import com.example.qgent.data.repository.AgentRepository
import com.example.qgent.data.repository.ChatRepository
import com.example.qgent.data.repository.UserRepository
import com.example.qgent.model.Agent
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
    private val chatRepo: ChatRepository,
    private val agentRepo: AgentRepository
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

    // ── 当前团队下的 Agent 列表 ──

    private val _agents = MutableStateFlow<List<Agent>>(emptyList())
    val agents: LiveData<List<Agent>> = _agents.asLiveData()

    // ── 项目列表（按当前团队） ──

    private val _projects = MutableStateFlow<List<String>>(emptyList())
    val projects: LiveData<List<String>> = _projects.asLiveData()

    // ── 缓存：id 反查映射。团队名全局唯一，teamNameToId 全局共享；
    //    项目名只在其所属团队内唯一，projectIdsByTeam 按 teamId 分桶，
    //    避免两个团队同名项目时跨团队串 id ──

    private val teamNameToId = mutableMapOf<String, String>()
    private val projectIdsByTeam = mutableMapOf<String, Map<String, String>>()

    // ── 已读群聊 ID 集合（跨项目切换保持） ──

    private val readGroupIds = mutableSetOf<String>()

    // ── 当前已加载 projects 对应的团队（防止串数据） ──

    private var loadedProjectsTeam: String? = null

    // ── 冷启动初始数据就绪信号：teams + 首个团队 projects 加载完成后置 true，供 MainActivity 路由 ──

    private val _initialDataLoaded = MutableStateFlow(false)
    val initialDataLoaded: LiveData<Boolean> = _initialDataLoaded.asLiveData()

    // ── 用户权限（当前为演示阶段默认 Project Admin，接入真实权限后替换） ──

    val isProjectAdmin: Boolean = true

    init {
        loadTeams()
    }

    fun setCurrentTeam(team: String, onProjectsLoaded: ((Boolean) -> Unit)? = null) {
        if (_currentTeam.value == team) return
        _currentTeam.value = team
        loadedProjectsTeam = null  // 失效旧缓存，防止 projectsOf 串数据
        loadAgents(team)
        loadProjects(team) {
            val firstProject = projectsOf(team).firstOrNull() ?: ""
            _currentProject.value = firstProject
            if (firstProject.isNotEmpty()) loadGroups(firstProject)
            onProjectsLoaded?.invoke(firstProject.isNotEmpty())
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

    /** 当前团队 / 项目的真实 id（API 成功时才有值；mock 回退时返回 null） */
    fun currentTeamId(): String? = teamNameToId[_currentTeam.value]

    /** 当前项目的真实 id，从「当前团队」的映射桶中反查，避免跨团队串 id */
    fun currentProjectId(): String? {
        val teamId = teamNameToId[_currentTeam.value] ?: return null
        return projectIdsByTeam[teamId]?.get(_currentProject.value)
    }

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
            userRepo.getTeams()
                .onSuccess { dtos ->
                    dtos.forEach { teamNameToId[it.name] = it.id }
                    _teams.value = dtos.map { it.name }
                    _teamDtos.value = dtos
                    // 无团队 → 初始就绪（启动页引导创建）；有团队 → 等首个团队项目加载完成再就绪
                    if (dtos.isEmpty()) {
                        _initialDataLoaded.value = true
                    } else if (_currentTeam.value.isEmpty()) {
                        dtos.firstOrNull()?.name?.let {
                            setCurrentTeam(it) { _initialDataLoaded.value = true }
                        }
                    }
                }
                .onFailure { _initialDataLoaded.value = true }
        }
    }

    private fun loadProjects(team: String, onLoaded: (() -> Unit)? = null) {
        viewModelScope.launch {
            val teamDto = _teamDtos.value.find { it.name == team }
            if (teamDto != null) {
                userRepo.getProjects(teamDto.id).onSuccess { projectDtos ->
                    _projects.value = projectDtos.map { it.name }
                    // 每次全量刷新该团队的项目映射，覆盖旧桶，防止跨团队残留
                    projectIdsByTeam[teamDto.id] = projectDtos.associate { it.name to it.id }
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

    /** 拉取当前团队 / 项目下的群聊。只用真实 projectId，失败由数据层 mock 回退兜底 */
    private fun loadGroups(projectName: String) {
        val projectId = currentProjectId() ?: run {
            _groups.value = emptyList()
            return
        }
        viewModelScope.launch {
            val groups = chatRepo.getGroups(projectId).getOrNull() ?: emptyList()
            _groups.value = applyReadState(sortGroups(groups.map { dto ->
                val lastActive = parseRfc3339(dto.latestActivityAt.orEmpty())
                ChatGroup(
                    id = dto.id,
                    name = dto.title,
                    lastMessage = dto.latestMessage.toSummary(),
                    time = formatGroupTime(lastActive),
                    unread = 0,
                    lastActiveTime = lastActive
                )
            }))
        }
    }

    private fun loadAgents(teamName: String) {
        viewModelScope.launch {
            val teamId = teamNameToId[teamName] ?: return@launch
            agentRepo.getAgents(teamId).onSuccess { dtos ->
                _agents.value = dtos.map { it.toAgent() }
            }
        }
    }

    /** 对已读群聊清零未读数 */
    private fun applyReadState(groups: List<ChatGroup>): List<ChatGroup> =
        groups.map { if (it.id in readGroupIds) it.copy(unread = 0) else it }
}
