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
 * 当 AuthInterceptor.token 为 null 时，所有 API 调用会自动走 mock fallback。
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

    private val mockGroupsByProject = mapOf(
        "Qgents Web" to listOf(
            ChatGroup("1", "登录功能", "李四：好的没问题", "14:05", 3),
            ChatGroup("2", "认证安全", "我：RSA 公钥我看一下", "13:40", 0)
        ),
        "Qgents Mobile" to listOf(
            ChatGroup("3", "任务编排", "王五：Planner 已完成", "昨天", 5),
            ChatGroup("4", "移动端 UI", "赵六：设置页改好了", "昨天", 0)
        ),
        "认证服务" to listOf(
            ChatGroup("5", "OAuth 对接", "张三：回调地址已更新", "周一", 2),
            ChatGroup("6", "Token 刷新", "我：异常情况如何处理", "周一", 0)
        ),
        "网关" to listOf(
            ChatGroup("7", "限流策略", "李四：阈值需要讨论", "周二", 1)
        ),
        "数据平台" to listOf(
            ChatGroup("8", "数据接入", "王五：Schema 已对齐", "周三", 0),
            ChatGroup("9", "报表需求", "赵六：新增 3 个维度", "周三", 4)
        ),
        "运营后台" to listOf(
            ChatGroup("10", "权限管理", "张三：角色树已更新", "周四", 0)
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

    // ── 数据加载（API → mock fallback） ──

    private fun loadTeams() {
        viewModelScope.launch {
            userRepo.getTeams().onSuccess { dtos ->
                _teams.postValue(dtos.map { it.name })
            }.onFailure {
                _teams.postValue(mockTeams)
            }
        }
    }

    private fun loadProjects(team: String) {
        viewModelScope.launch {
            // 需要 teamId，mock 阶段用 team name 反查
            userRepo.getTeams().onSuccess { teamDtos ->
                val teamDto = teamDtos.find { it.name == team }
                if (teamDto != null) {
                    userRepo.getProjects(teamDto.id).onSuccess { projectDtos ->
                        _projects.postValue(projectDtos.map { it.name })
                        return@launch
                    }
                }
            }
            // fallback to mock
            _projects.postValue(mockProjectsByTeam[team] ?: emptyList())
        }
    }

    private fun loadGroups(project: String) {
        viewModelScope.launch {
            // 需要 projectId，mock 阶段用 project name 反查
            // 先尝试从已加载的项目列表中找 id，找不到回退 mock
            val projectId = resolveProjectId(project)
            if (projectId != null) {
                chatRepo.getGroups(projectId).onSuccess { dtos ->
                    _groups.postValue(dtos.map { dto ->
                        ChatGroup(dto.id, dto.name, dto.lastMessage ?: "", dto.updatedAt, 0)
                    })
                    return@launch
                }
            }
            _groups.postValue(mockGroupsByProject[project] ?: emptyList())
        }
    }

    /** 尝试根据项目名解析 projectId（需要先通过 teams API 拿到项目的真实 id） */
    private suspend fun resolveProjectId(projectName: String): String? {
        val team = _currentTeam.value ?: return null
        val teamDtos = userRepo.getTeams().getOrNull() ?: return null
        val teamDto = teamDtos.find { it.name == team } ?: return null
        val projectDtos = userRepo.getProjects(teamDto.id).getOrNull() ?: return null
        return projectDtos.find { it.name == projectName }?.id
    }
}
