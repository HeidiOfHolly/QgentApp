package com.example.qgent.viewmodel

import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.asLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.qgent.data.SessionStore
import com.example.qgent.data.model.BindProjectRepositoryRequest
import com.example.qgent.data.model.GitHubRepositoryDto
import com.example.qgent.data.model.GroupDto
import com.example.qgent.data.model.TeamDto
import com.example.qgent.data.model.formatGroupTime
import com.example.qgent.data.model.parseRfc3339
import com.example.qgent.data.model.toAgent
import com.example.qgent.data.model.toSummary
import com.example.qgent.data.repository.AgentRepository
import com.example.qgent.data.repository.ChatRepository
import com.example.qgent.data.repository.GitHubRepository
import com.example.qgent.data.repository.UserRepository
import com.example.qgent.model.Agent
import com.example.qgent.model.ChatGroup
import com.example.qgent.model.GroupType
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.util.UUID

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
    private val agentRepo: AgentRepository,
    private val githubRepo: GitHubRepository
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

    // ── 项目加载中标记：切换团队拉取项目期间为 true，供抽屉显示 ProgressBar ──

    private val _projectsLoading = MutableStateFlow(false)
    val projectsLoading: LiveData<Boolean> = _projectsLoading.asLiveData()

    // ── 缓存：id 反查映射。团队名全局唯一，teamNameToId 全局共享；
    //    项目名只在其所属团队内唯一，projectIdsByTeam 按 teamId 分桶，
    //    避免两个团队同名项目时跨团队串 id ──

    private val teamNameToId = mutableMapOf<String, String>()
    private val projectIdsByTeam = mutableMapOf<String, Map<String, String>>()

    // ── 群聊最后已读时间：记录进入群聊时的 lastActiveTime，跨项目切换保持 ──

    private val lastReadAt = mutableMapOf<String, Long>()

    // ── 当前已加载 projects 对应的团队（防止串数据） ──

    private var loadedProjectsTeam: String? = null

    // ── 加载任务句柄：快速切换团队时取消上一次未完成的加载，只保留最后停下的团队 ──

    private var loadProjectsJob: Job? = null
    private var loadGroupsJob: Job? = null
    private var loadAgentsJob: Job? = null

    // ── 冷启动初始数据就绪信号：teams + 首个团队 projects + 首个项目群聊都就绪后置 true，供 MainActivity 路由 ──

    private val _initialDataLoaded = MutableStateFlow(false)
    val initialDataLoaded: LiveData<Boolean> = _initialDataLoaded.asLiveData()

    // 冷启动路由进行中标记：首个团队项目为空或群聊加载完成后解除（供 hasGroups 判断）
    private var routingInitPending = false

    // ── 用户权限（当前为演示阶段默认 Project Admin，接入真实权限后替换） ──

    val isProjectAdmin: Boolean = true

    // ── 创建项目结果（表单页观察） ──

    private val _createProjectState = MutableStateFlow<CreateProjectState>(CreateProjectState.Idle)
    val createProjectState: LiveData<CreateProjectState> = _createProjectState.asLiveData()

    // ── 未读的团队邀请通知（kind=INVITED 且未读），抽屉铃铛 / 群聊列表头像 / GitHub 页头像红点 ──

    private val _unreadInvitations = MutableStateFlow(false)
    val unreadInvitations: LiveData<Boolean> = _unreadInvitations.asLiveData()

    // ── 未读的任务类通知（非 INVITED 且未读），任务页铃铛 / 底部任务 tab 红点 ──

    private val _unreadTaskNotifications = MutableStateFlow(false)
    val unreadTaskNotifications: LiveData<Boolean> = _unreadTaskNotifications.asLiveData()

    init {
        loadTeams()
        refreshUnreadInvitations()
        refreshUnreadTaskNotifications()
    }

    /** 拉取通知列表，统计未读的团队邀请（INVITED）；失败时保持现状不打扰用户 */
    fun refreshUnreadInvitations() {
        viewModelScope.launch {
            userRepo.getNotifications().onSuccess { list ->
                _unreadInvitations.value = list.any { it.kind == "INVITED" && !it.isRead }
            }
        }
    }

    /**
     * 拉取通知列表，统计「当前项目」下未读的任务类通知（除 INVITED 外），
     * 与任务铃铛列表过滤条件（TaskMessageListFragment）保持一致，
     * 避免其他项目/历史遗留的未读通知点亮当前任务页红点；失败时保持现状不打扰用户。
     */
    fun refreshUnreadTaskNotifications() {
        viewModelScope.launch {
            userRepo.getNotifications().onSuccess { list ->
                val projectId = currentProjectId()
                _unreadTaskNotifications.value = list.any {
                    it.kind != "INVITED" && !it.isRead && projectId != null && it.projectId == projectId
                }
            }
        }
    }

    /** 加入团队后刷新团队列表（复用 init 的加载逻辑） */
    fun refreshTeams() = loadTeams()

    /** 当前团队是否可新建项目：仅 TEAM_OWNER（文档 §5.2）；角色未知时放行交给后端判定 */
    fun canCreateProject(teamName: String): Boolean =
        _teamDtos.value.firstOrNull { it.name == teamName }?.role?.let { it == "TEAM_OWNER" } ?: true

    fun setCurrentTeam(team: String, onProjectsLoaded: ((Boolean) -> Unit)? = null, force: Boolean = false) {
        // 同团队重复选择默认跳过（避免冗余请求）；force=true 用于项目列表已被清空的场景（如 GitHub 页）强制重载
        if (!force && _currentTeam.value == team) return
        _currentTeam.value = team
        loadedProjectsTeam = null  // 失效旧缓存，防止 projectsOf 串数据
        _projects.value = emptyList()  // 先清空，避免加载期间显示上一个团队的项目
        loadAgents(team)
        loadProjects(team) {
            val firstProject = projectsOf(team).firstOrNull() ?: ""
            _currentProject.value = firstProject
            if (firstProject.isNotEmpty()) {
                loadGroups(firstProject)
            } else if (routingInitPending) {
                // 首个团队无项目 → 无需等群聊，直接完成冷启动路由
                resolveRoutingReady()
            }
            onProjectsLoaded?.invoke(firstProject.isNotEmpty())
            // 项目上下文已定，同步刷新任务红点（限定当前项目），避免沿用上一项目的未读状态
            refreshUnreadTaskNotifications()
        }
    }

    fun setCurrentProject(project: String) {
        if (_currentProject.value != project) {
            _currentProject.value = project
            loadGroups(project)
            refreshUnreadTaskNotifications()
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

    /** 退回列表页时刷新群聊最新消息摘要（onResume 调用）；projectId 未就绪时跳过，避免冷启动误清空 */
    fun refreshGroups() {
        if (currentProjectId() == null) return
        loadGroups(_currentProject.value)
    }

    /** 标记群聊为已读：记录最后已读时间（列表点击用列表 lastActiveTime，详情页退出传最新消息时间），未读数与 @我 标记清零 */
    fun markGroupRead(groupId: String, lastSeenAt: Long? = null) {
        // 详情页退出传的是消息 createdAt 解析值，后端 createdAt 时区不一致时会是未来时间，
        // 会把已读基线推到未来，导致后续 unread/@me 恒被「无新活动」短路；钳制到当前时间兜底。
        val ts = (lastSeenAt ?: _groups.value.find { it.id == groupId }?.lastActiveTime ?: return)
            .coerceAtMost(System.currentTimeMillis())
        val prev = lastReadAt[groupId]
        if (prev == null || ts > prev) lastReadAt[groupId] = ts
        _groups.value = _groups.value.map {
            if (it.id == groupId) it.copy(unread = 0, mentionedMe = false) else it
        }
    }

    /** 切换群聊置顶状态，重新排序后发出 */
    fun togglePin(groupId: String) {
        val updated = _groups.value.map {
            if (it.id == groupId) it.copy(isPinned = !it.isPinned) else it
        }
        _groups.value = sortGroups(updated)
    }

    // ── 排序：项目总群恒置顶（文档 §7：PROJECT_MAIN 不可归档/删除，天然固定首位），
    //    其余群按最新活跃倒序排列；置顶（手动 pin）群在总群之后、普通群之前 ──

    private fun sortGroups(groups: List<ChatGroup>): List<ChatGroup> =
        groups.sortedWith(
            compareByDescending<ChatGroup> { it.type == GroupType.PROJECT_MAIN }
                .thenByDescending { it.isPinned }
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
                    // 无团队 → 初始就绪（启动页引导创建）；有团队 → 等首个团队项目及群聊加载完成再就绪
                    if (dtos.isEmpty()) {
                        _initialDataLoaded.value = true
                    } else if (_currentTeam.value.isEmpty()) {
                        routingInitPending = true
                        dtos.firstOrNull()?.name?.let { setCurrentTeam(it) }
                    }
                }
                .onFailure { _initialDataLoaded.value = true }
        }
    }

    private fun loadProjects(team: String, onLoaded: (() -> Unit)? = null) {
        // 切换团队时取消上一次未完成的加载，防止旧团队项目后完成覆盖新团队结果
        loadProjectsJob?.cancel()
        _projectsLoading.value = true
        // 包装回调：加载结束（成功或空/失败）统一关闭加载标记
        val finished: () -> Unit = {
            _projectsLoading.value = false
            onLoaded?.invoke()
        }
        loadProjectsJob = viewModelScope.launch {
            val teamDto = _teamDtos.value.find { it.name == team }
            if (teamDto != null) {
                userRepo.getProjects(teamDto.id).onSuccess { projectDtos ->
                    _projects.value = projectDtos.map { it.name }
                    // 每次全量刷新该团队的项目映射，覆盖旧桶，防止跨团队残留
                    projectIdsByTeam[teamDto.id] = projectDtos.associate { it.name to it.id }
                    loadedProjectsTeam = team
                    finished()
                    return@launch
                }
            }
            _projects.value = emptyList()
            loadedProjectsTeam = team
            finished()
        }
    }

    /** 拉取当前团队 / 项目下的群聊。只用真实 projectId，失败由数据层 mock 回退兜底 */
    private fun loadGroups(projectName: String) {
        val projectId = currentProjectId() ?: run {
            _groups.value = emptyList()
            resolveRoutingReady()
            return
        }
        // 切换项目 / 团队时取消上一次未完成的群聊加载，防止旧项目群聊覆盖新结果
        loadGroupsJob?.cancel()
        loadGroupsJob = viewModelScope.launch {
            val dtos = chatRepo.getGroups(projectId).getOrNull() ?: emptyList()
            val myId = SessionStore.user()?.id
            val withUnread = toChatGroups(dtos).map { g ->
                val info = countUnread(projectId, g.id, myId, g.lastActiveTime)
                g.copy(unread = info.unread, mentionedMe = info.mentionedMe)
            }
            _groups.value = withUnread
            resolveRoutingReady()
        }
    }

    /** 某群未读状态：未读条数 + 是否有人 @ 我 */
    private data class UnreadInfo(val unread: Int, val mentionedMe: Boolean)

    /** 统计某群未读：首次加载以当前活动时间为已读基线；仅当有新活动时才拉消息列表，数未读并判断是否 @ 我 */
    private suspend fun countUnread(projectId: String, groupId: String, myId: String?, lastActive: Long): UnreadInfo {
        val lastRead = lastReadAt[groupId]
        if (lastRead == null) {
            lastReadAt[groupId] = lastActive
            Log.d("Mention", "baseline group=$groupId lastActive=$lastActive")
            return UnreadInfo(0, false)
        }
        if (lastActive <= lastRead) {
            Log.d("Mention", "no-new-activity group=$groupId lastRead=$lastRead lastActive=$lastActive")
            return UnreadInfo(0, false)
        }
        val dtos = chatRepo.getMessages(projectId, groupId).getOrNull() ?: run {
            Log.d("Mention", "getMessages-failed group=$groupId")
            return UnreadInfo(0, false)
        }
        val newFromOthers = dtos.filter {
            it.type != "SYSTEM" && it.senderId != myId && parseRfc3339(it.createdAt) > lastRead
        }
        val mentioned = myId != null && newFromOthers.any { it.mentions?.any { m -> m.id == myId } == true }
        Log.d("Mention", "group=$groupId myId=$myId lastRead=$lastRead msgs=${dtos.map { "${it.senderId}|${it.mentions}|${it.createdAt}" }} mentioned=$mentioned newCount=${newFromOthers.size}")
        return UnreadInfo(
            unread = newFromOthers.size,
            mentionedMe = mentioned
        )
    }

    /** GroupDto → ChatGroup 基础映射（排序，未读默认 0，由 loadGroups 统计后回填） */
    private fun toChatGroups(dtos: List<GroupDto>): List<ChatGroup> =
        sortGroups(dtos.map { dto ->
            val lastActive = parseRfc3339(dto.latestActivityAt.orEmpty())
            ChatGroup(
                id = dto.id,
                name = dto.title,
                lastMessage = dto.latestMessage.toSummary(),
                time = formatGroupTime(lastActive),
                unread = 0,
                lastActiveTime = lastActive,
                type = if (dto.type == "PROJECT_MAIN") GroupType.PROJECT_MAIN else GroupType.REQUIREMENT
            )
        })

    /** 冷启动路由就绪：仅当存在待解析的冷启动标记时解除（普通切项目时的 loadGroups 是 no-op） */
    private fun resolveRoutingReady() {
        if (routingInitPending) {
            routingInitPending = false
            _initialDataLoaded.value = true
        }
    }

    private fun loadAgents(teamName: String) {
        // 切换团队时取消上一次未完成的加载，防止旧团队 Agent 后完成覆盖新结果
        loadAgentsJob?.cancel()
        loadAgentsJob = viewModelScope.launch {
            val teamId = teamNameToId[teamName] ?: return@launch
            agentRepo.getAgents(teamId)
                .onSuccess { dtos ->
                    Log.d("Agents", "loadAgents success: ${dtos.map { it.name }}")
                    _agents.value = dtos.map { it.toAgent() }
                }
                .onFailure { e ->
                    Log.e("Agents", "loadAgents FAILED: ${e::class.simpleName} ${e.message}")
                    _agents.value = emptyList()
                }
        }
    }

    /**
     * 创建项目：POST /teams/{teamId}/projects，随后逐条绑定已选仓库，
     * 成功后刷新项目列表、选中新项目并拉取群聊（总群由后端自动生成）。
     * 绑定仓库失败不阻断创建。
     */
    fun createProject(
        name: String,
        description: String?,
        memberIds: List<String>,
        repos: List<GitHubRepositoryDto>
    ) {
        val teamName = _currentTeam.value
        val teamId = teamNameToId[teamName] ?: run {
            _createProjectState.value = CreateProjectState.Error("请先选择团队")
            return
        }
        if (_createProjectState.value == CreateProjectState.Loading) return
        _createProjectState.value = CreateProjectState.Loading
        viewModelScope.launch {
            userRepo.createProject(teamId, name, description, UUID.randomUUID().toString())
                .onSuccess { project ->
                    // 选中的成员逐个加入项目（初始 PROJECT_MEMBER），失败不阻断创建
                    memberIds.forEach { userId ->
                        userRepo.addProjectMember(project.id, userId, UUID.randomUUID().toString())
                    }
                    repos.forEach { repo ->
                        githubRepo.bindProjectRepository(
                            project.id,
                            UUID.randomUUID().toString(),
                            BindProjectRepositoryRequest(repo.installationId, repo.id, repo.fullName)
                        )
                    }
                    loadProjects(teamName) {
                        _currentProject.value = name
                        val projectId = currentProjectId()
                        if (projectId == null) {
                            _createProjectState.value = CreateProjectState.Success(name, "", name)
                        } else {
                            viewModelScope.launch {
                                val dtos = chatRepo.getGroups(projectId).getOrNull() ?: emptyList()
                                _groups.value = toChatGroups(dtos)
                                val main = dtos.firstOrNull { it.type == "PROJECT_MAIN" }
                                _createProjectState.value = CreateProjectState.Success(
                                    projectName = name,
                                    groupId = main?.id.orEmpty(),
                                    groupName = main?.title ?: name
                                )
                            }
                        }
                    }
                }
                .onFailure { e ->
                    _createProjectState.value = CreateProjectState.Error(e.message ?: "创建项目失败")
                }
        }
    }
}

/** 创建项目进度状态 */
sealed interface CreateProjectState {
    data object Idle : CreateProjectState
    data object Loading : CreateProjectState
    data class Success(val projectName: String, val groupId: String, val groupName: String) : CreateProjectState
    data class Error(val message: String) : CreateProjectState
}
