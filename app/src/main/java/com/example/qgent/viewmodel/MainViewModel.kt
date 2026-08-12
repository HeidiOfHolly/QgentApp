package com.example.qgent.viewmodel

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.qgent.data.repository.ChatRepository
import com.example.qgent.data.repository.UserRepository
import com.example.qgent.model.ChatGroup
import kotlinx.coroutines.launch

/**
 * Activity 级共享状态：当前团队、当前项目、群聊列表。
 * 个人中心抽屉（切团队/切项目）与群聊列表页（显示所在团队）共用。
 *
 * 数据源策略：优先 API（需登录 token），失败或无 token 时回退 mock。
 */
class MainViewModel : ViewModel() {

    private val userRepo = UserRepository()
    private val chatRepo = ChatRepository()

    // ── Mock 数据（API 不可用时回退） ──

    private val mockTeams = listOf("团队A", "团队B", "团队C", "团队D")

    private val mockProjectsByTeam = mapOf(
        "团队A" to listOf("Qgents Web", "Qgents Mobile"),
        "团队B" to listOf("认证服务", "网关"),
        "团队C" to listOf("数据平台"),
        "团队D" to listOf("运营后台")
    )

    private val now = System.currentTimeMillis()

    private val mockGroupsByProject = mapOf(
        "Qgents Web" to listOf(
            ChatGroup("1", "登录功能", "李四：好的没问题", "14:05", 3,
                isPinned = true, lastActiveTime = now - 1000 * 60 * 30),
            ChatGroup("2", "认证安全", "我：RSA 公钥我看一下", "13:40", 0,
                lastActiveTime = now - 1000 * 60 * 90)
        ),
        "Qgents Mobile" to listOf(
            ChatGroup("3", "任务编排", "王五：Planner 已完成", "昨天", 5,
                isPinned = true, lastActiveTime = now - 1000 * 60 * 60),
            ChatGroup("4", "移动端 UI", "赵六：设置页改好了", "昨天", 0,
                lastActiveTime = now - 1000 * 60 * 120)
        ),
        "认证服务" to listOf(
            ChatGroup("5", "OAuth 对接", "张三：回调地址已更新", "周一", 2,
                lastActiveTime = now - 1000 * 60 * 60 * 24),
            ChatGroup("6", "Token 刷新", "我：异常情况如何处理", "周一", 0,
                lastActiveTime = now - 1000 * 60 * 60 * 48)
        ),
        "网关" to listOf(
            ChatGroup("7", "限流策略", "李四：阈值需要讨论", "周二", 1,
                lastActiveTime = now - 1000 * 60 * 60 * 72)
        ),
        "数据平台" to listOf(
            ChatGroup("8", "数据接入", "王五：Schema 已对齐", "周三", 0,
                lastActiveTime = now - 1000 * 60 * 60 * 96),
            ChatGroup("9", "报表需求", "赵六：新增 3 个维度", "周三", 4,
                lastActiveTime = now - 1000 * 60 * 60 * 100)
        ),
        "运营后台" to listOf(
            ChatGroup("10", "权限管理", "张三：角色树已更新", "周四", 0,
                lastActiveTime = now - 1000 * 60 * 60 * 120)
        )
    )

    // ── 当前团队 / 项目（页面间共享） ──

    private val _currentTeam = MutableLiveData("团队A")
    val currentTeam: LiveData<String> = _currentTeam

    private val _currentProject = MutableLiveData<String>()
    val currentProject: LiveData<String> = _currentProject

    // ── 团队列表 ──

    private val _teams = MutableLiveData<List<String>>()
    val teams: LiveData<List<String>> = _teams

    // ── 当前项目下的群聊列表 ──

    private val _groups = MutableLiveData<List<ChatGroup>>()
    val groups: LiveData<List<ChatGroup>> = _groups

    // ── 项目列表（按当前团队） ──

    private val _projects = MutableLiveData<List<String>>()
    val projects: LiveData<List<String>> = _projects

    // ── 缓存：name → id 映射（用于 API 调用时反查） ──

    private var projectNameToId = mutableMapOf<String, String>()

    // ── 已读群聊 ID 集合（跨项目切换保持） ──

    private val readGroupIds = mutableSetOf<String>()

    // ── 当前已加载 projects 对应的团队（防止串数据） ──

    private var loadedProjectsTeam: String? = null

    // ── 用户权限（mock 阶段默认 Project Admin） ──

    val isProjectAdmin: Boolean = true

    init {
        _currentProject.value = mockProjectsByTeam.getValue("团队A").first()
        loadTeams()
        loadProjects("团队A")
        loadGroups("Qgents Web")
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

    fun projectsOf(team: String): List<String> =
        if (loadedProjectsTeam == team) (_projects.value ?: emptyList()).ifEmpty {
            mockProjectsByTeam[team] ?: emptyList()
        } else mockProjectsByTeam[team] ?: emptyList()

    fun groupsOf(project: String): List<ChatGroup> =
        _groups.value?.let {
            if (it.isNotEmpty()) it else mockGroupsByProject[project] ?: emptyList()
        } ?: mockGroupsByProject[project] ?: emptyList()

    /** 标记群聊为已读（未读数清零，并持久化到已读集合） */
    fun markAsRead(groupId: String) {
        readGroupIds.add(groupId)
        val current = _groups.value ?: return
        val updated = current.map {
            if (it.id == groupId) it.copy(unread = 0) else it
        }
        _groups.value = updated
    }

    /** 切换群聊置顶状态，重新排序后发出 */
    fun togglePin(groupId: String) {
        val current = _groups.value ?: return
        val updated = current.map {
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

    // ── 数据加载（API → mock fallback） ──

    private fun loadTeams() {
        viewModelScope.launch {
            userRepo.getTeams().onSuccess { dtos ->
                _teams.value = dtos.map { it.name }
            }.onFailure {
                _teams.value = mockTeams
            }
        }
    }

    private fun loadProjects(team: String, onLoaded: (() -> Unit)? = null) {
        viewModelScope.launch {
            userRepo.getTeams().onSuccess { teamDtos ->
                val teamDto = teamDtos.find { it.name == team }
                if (teamDto != null) {
                    userRepo.getProjects(teamDto.id).onSuccess { projectDtos ->
                        _projects.value = projectDtos.map { it.name }
                        projectDtos.forEach { projectNameToId[it.name] = it.id }
                        loadedProjectsTeam = team
                        onLoaded?.invoke()
                        return@launch
                    }
                }
            }
            _projects.value = mockProjectsByTeam[team] ?: emptyList()
            loadedProjectsTeam = team
            onLoaded?.invoke()
        }
    }

    private fun loadGroups(projectName: String) {
        viewModelScope.launch {
            val pid = projectNameToId[projectName]
            if (pid != null) {
                chatRepo.getGroups(pid).onSuccess { dtos ->
                    _groups.value = applyReadState(sortGroups(dtos.map { dto ->
                        ChatGroup(dto.id, dto.title, dto.lastMessage ?: "", dto.updatedAt, 0)
                    }))
                    return@launch
                }
            }
            val raw = mockGroupsByProject[projectName] ?: emptyList()
            _groups.value = applyReadState(sortGroups(raw.map { it.copy() }))
        }
    }

    /** 对已读群聊清零未读数 */
    private fun applyReadState(groups: List<ChatGroup>): List<ChatGroup> =
        groups.map { if (it.id in readGroupIds) it.copy(unread = 0) else it }
}
