package com.example.qgent.viewmodel

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.qgent.data.model.TeamDto
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

    // Mock 团队详情：role 区分“我创建的 / 我加入的”
    private val mockTeamDtos = listOf(
        TeamDto("1", "团队A", "TEAM_OWNER", 8, "2026-06-01"),
        TeamDto("2", "团队B", "TEAM_MEMBER", 12, "2026-06-10"),
        TeamDto("3", "团队C", "TEAM_MEMBER", 5, "2026-07-02"),
        TeamDto("4", "团队D", "TEAM_OWNER", 15, "2026-07-20")
    )

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

    // ── 团队详情列表（含 role，用于区分“我创建的 / 我加入的”） ──

    private val _teamDtos = MutableLiveData<List<TeamDto>>()
    val teamDtos: LiveData<List<TeamDto>> = _teamDtos

    // ── 当前项目下的群聊列表 ──

    private val _groups = MutableLiveData<List<ChatGroup>>()
    val groups: LiveData<List<ChatGroup>> = _groups

    // ── 项目列表（按当前团队） ──

    private val _projects = MutableLiveData<List<String>>()
    val projects: LiveData<List<String>> = _projects

    // ── 缓存：name → id 映射（用于 API 调用时反查） ──

    private var projectNameToId = mutableMapOf<String, String>()

    init {
        _currentProject.value = mockProjectsByTeam.getValue("团队A").first()
        loadTeams()
        loadProjects("团队A")
        loadGroups("Qgents Web")
    }

    fun setCurrentTeam(team: String) {
        if (_currentTeam.value == team) return
        _currentTeam.value = team
        val firstProject = projectsOf(team).firstOrNull() ?: ""
        _currentProject.value = firstProject
        loadProjects(team)
        if (firstProject.isNotEmpty()) loadGroups(firstProject)
    }

    fun setCurrentProject(project: String) {
        if (_currentProject.value != project) {
            _currentProject.value = project
            loadGroups(project)
        }
    }

    fun projectsOf(team: String): List<String> =
        _projects.value?.let {
            if (it.isNotEmpty()) it else mockProjectsByTeam[team] ?: emptyList()
        } ?: mockProjectsByTeam[team] ?: emptyList()

    fun groupsOf(project: String): List<ChatGroup> =
        _groups.value?.let {
            if (it.isNotEmpty()) it else mockGroupsByProject[project] ?: emptyList()
        } ?: mockGroupsByProject[project] ?: emptyList()

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
                _teams.postValue(dtos.map { it.name })
                _teamDtos.postValue(dtos)
            }.onFailure {
                _teams.postValue(mockTeams)
                _teamDtos.postValue(mockTeamDtos)
            }
        }
    }

    private fun loadProjects(team: String) {
        viewModelScope.launch {
            userRepo.getTeams().onSuccess { teamDtos ->
                val teamDto = teamDtos.find { it.name == team }
                if (teamDto != null) {
                    userRepo.getProjects(teamDto.id).onSuccess { projectDtos ->
                        _projects.postValue(projectDtos.map { it.name })
                        projectDtos.forEach { projectNameToId[it.name] = it.id }
                        return@launch
                    }
                }
            }
            _projects.postValue(mockProjectsByTeam[team] ?: emptyList())
        }
    }

    private fun loadGroups(projectName: String) {
        viewModelScope.launch {
            val pid = projectNameToId[projectName]
            if (pid != null) {
                chatRepo.getGroups(pid).onSuccess { dtos ->
                    _groups.postValue(sortGroups(dtos.map { dto ->
                        ChatGroup(dto.id, dto.title, dto.lastMessage ?: "", dto.updatedAt, 0)
                    }))
                    return@launch
                }
            }
            _groups.postValue(sortGroups(mockGroupsByProject[projectName] ?: emptyList()))
        }
    }
}
