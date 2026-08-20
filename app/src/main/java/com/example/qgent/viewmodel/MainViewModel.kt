package com.example.qgent.viewmodel

import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.asLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.qgent.data.SessionStore
import com.example.qgent.data.model.ApiException
import com.example.qgent.data.model.BindProjectRepositoryRequest
import com.example.qgent.data.model.GitHubRepositoryDto
import com.example.qgent.data.model.GroupDto
import com.example.qgent.data.model.GroupMemberDto
import com.example.qgent.data.model.NewRepositoryRequest
import com.example.qgent.data.model.ProjectDto
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
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
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

    // ── 团队列表加载中标记：刷新团队期间为 true，供界面显示 ProgressBar ──

    private val _teamsLoading = MutableStateFlow(false)
    val teamsLoading: LiveData<Boolean> = _teamsLoading.asLiveData()

    // ── 团队详情列表（含 role，用于区分“我创建的 / 我加入的”） ──

    private val _teamDtos = MutableStateFlow<List<TeamDto>>(emptyList())
    val teamDtos: LiveData<List<TeamDto>> = _teamDtos.asLiveData()

    // ── 当前项目下的群聊列表 ──

    private val _groups = MutableStateFlow<List<ChatGroup>>(emptyList())
    val groups: LiveData<List<ChatGroup>> = _groups.asLiveData()

    // ── 群聊列表加载中标记：切换项目/团队加载群聊期间为 true，供群聊列表页显示 ProgressBar ──

    private val _groupsLoading = MutableStateFlow(false)
    val groupsLoading: LiveData<Boolean> = _groupsLoading.asLiveData()

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

    // ── 当前已加载 projects 对应的团队（防止串数据） ──

    private var loadedProjectsTeam: String? = null

    // ── 加载任务句柄：快速切换团队时取消上一次未完成的加载，只保留最后停下的团队 ──

    private var loadProjectsJob: Job? = null
    private var loadGroupsJob: Job? = null
    private var loadAgentsJob: Job? = null

    // ── 抽屉红点刷新节流：事件风暴时 3s 合并只算一次；默认先延后 800ms 再算，
    //    让 loadGroups 等主链路请求先发出（同一 host OkHttp 默认并发只有 5，避免抢名额拖慢群列表） ──

    private var drawerUnreadJob: Job? = null
    private var drawerUnreadPending = false

    // ── 冷启动初始数据就绪信号：teams + 首个团队 projects + 首个项目群聊都就绪后置 true，供 MainActivity 路由 ──

    private val _initialDataLoaded = MutableStateFlow(false)
    val initialDataLoaded: LiveData<Boolean> = _initialDataLoaded.asLiveData()

    // 冷启动路由进行中标记：首个团队项目为空或群聊加载完成后解除（供 hasGroups 判断）
    private var routingInitPending = false

    /** 群 id → 后端已读游标（lastReadSequenceNo）：群聊页「有人@你」未读 @ 判定基准（对齐 web ChatPanel） */
    private val lastReadSeqByGroup = mutableMapOf<String, Long>()

    /** 群 id → 群成员列表缓存（拼图头像 + 成员校验共用；拉取失败为 null 按「在群内/无头像」兜底） */
    private val groupMembersCache = mutableMapOf<String, List<GroupMemberDto>?>()

    /** 无代码变更（diff-review.skipped FINAL_DIFF_EMPTY）任务 id 集合（Activity 级，跨详情页/聊天页共享）：
     *  文档 §15.6.4/§20.3 无代码任务无 DiffReviewBatch，不能走确认/拒绝/重试；
     *  此标记为唯一机器依据，两个 Fragment 的 SSE 流共同写入，重进页面不丢失。 */
    private val _noCodeChangeTaskIds = MutableStateFlow<Set<String>>(emptySet())
    val noCodeChangeTaskIds: LiveData<Set<String>> = _noCodeChangeTaskIds.asLiveData()

    /** 记录收到 diff-review.skipped（FINAL_DIFF_EMPTY）的任务 id */
    fun recordNoCodeChangeTask(taskId: String) {
        if (taskId.isBlank()) return
        _noCodeChangeTaskIds.value = _noCodeChangeTaskIds.value + taskId
    }

    /** 该任务是否为无代码变更任务（无 Diff Review 可确认） */
    fun isNoCodeChangeTask(taskId: String): Boolean = taskId in _noCodeChangeTaskIds.value

    // ── 用户权限 ──

    /** 当前用户是否项目管理员（后端 role 权威：GET /projects/{id} 返回，Team Owner 兜底 PROJECT_ADMIN） */
    suspend fun isProjectAdmin(projectId: String): Boolean =
        userRepo.getProject(projectId).getOrNull()?.role == "PROJECT_ADMIN"

    // ── 创建项目结果（表单页观察） ──

    private val _createProjectState = MutableStateFlow<CreateProjectState>(CreateProjectState.Idle)
    val createProjectState: LiveData<CreateProjectState> = _createProjectState.asLiveData()

    // ── 未读的团队邀请通知（kind=INVITED 且未读），抽屉铃铛 / 群聊列表头像 / GitHub 页头像红点 ──

    private val _unreadInvitations = MutableStateFlow(false)
    val unreadInvitations: LiveData<Boolean> = _unreadInvitations.asLiveData()

    // ── 未读的任务类通知（非 INVITED 且未读），任务页铃铛 / 底部任务 tab 红点 ──

    private val _unreadTaskNotifications = MutableStateFlow(false)
    val unreadTaskNotifications: LiveData<Boolean> = _unreadTaskNotifications.asLiveData()

    // ── 抽屉未读红点：有未读的团队名集合（左栏团队红点）+ 有未读的当前团队项目名集合（右栏项目红点） ──

    private val _unreadTeamNames = MutableStateFlow<Set<String>>(emptySet())
    val unreadTeamNames: LiveData<Set<String>> = _unreadTeamNames.asLiveData()

    private val _unreadProjectNames = MutableStateFlow<Set<String>>(emptySet())
    val unreadProjectNames: LiveData<Set<String>> = _unreadProjectNames.asLiveData()

    /** 抽屉任一团队/项目有未读（群聊或任务消息）：群聊列表/GitHub 页头像红点 */
    private val _hasUnreadBadge = MutableStateFlow(false)
    val hasUnreadBadge: LiveData<Boolean> = _hasUnreadBadge.asLiveData()

    init {
        loadTeams()
        refreshUnreadInvitations()
        refreshUnreadTaskNotifications()
    }

    /** 拉取通知列表，统计未读的个人通知（团队邀请 INVITED 或 有人@我 MESSAGE_MENTION）；
     *  与抽屉铃铛列表过滤条件（MessageListFragment）保持一致；失败时保持现状不打扰用户 */
    fun refreshUnreadInvitations() {
        viewModelScope.launch {
            userRepo.getNotifications().onSuccess { list ->
                _unreadInvitations.value = list.any {
                    (it.kind == "INVITED" || it.kind == "MESSAGE_MENTION") && !it.isRead
                }
            }
        }
    }

    /**
     * 拉取通知列表，统计「当前项目」下未读的任务类通知（除 INVITED 与 MESSAGE_MENTION 外，
     * @我 归抽屉铃铛，不点亮任务铃铛红点），与任务铃铛列表过滤条件（TaskMessageListFragment）保持一致，
     * 避免其他项目/历史遗留的未读通知点亮当前任务页红点；失败时保持现状不打扰用户。
     */
    fun refreshUnreadTaskNotifications() {
        viewModelScope.launch {
            userRepo.getNotifications().onSuccess { list ->
                val projectId = currentProjectId()
                _unreadTaskNotifications.value = list.any {
                    it.kind != "INVITED" && it.kind != "MESSAGE_MENTION" && !it.isRead &&
                        projectId != null && it.projectId == projectId
                }
            }
        }
    }

    /**
     * 刷新抽屉未读红点：输出「有未读的团队名集合」与「有未读的当前团队项目名集合」。
     * 数据源：
     * - 群聊未读：各项目群列表的 unreadCount（逐项目拉群列表，按 projectId 关联）；
     * - 任务消息未读：通知列表（非 INVITED 且未读，按通知 projectId 关联）。
     * 团队未读 = 任一下属项目有未读。遍历所有团队（teamNameToId），失败的项目/团队静默跳过不打断其余。
     * 头像红点（hasUnreadBadge）：仅统计「当前项目之外」的未读——用户已切换到该有未读的项目时
     * 不再用头像红点打扰（抽屉内项目红点仍全量保留）。
     *
     * 性能：团队/项目/群拉取并行（async），避免串行 N+1 拖慢红点刷新；
     * 当前项目用本地 _groups 缓存判断未读（markGroupRead 已本地清零），红点消除即时生效，不依赖已读接口在途返回。
     * [force] 红点刷新节流：默认先延后 [DRAWER_UNREAD_DEFER_MS] 再算（让 loadGroups 等主链路请求先发出，
     * 不抢同一 host 的并发名额），且 [DRAWER_UNREAD_DEBOUNCE_MS] 窗口内合并只算一次；
     * 事件风暴只算首尾各一次。force=true（用户主动点已读 / 打开抽屉）取消在途节流立即算。
     */
    fun refreshDrawerUnread(force: Boolean = false) {
        if (force) {
            drawerUnreadJob?.cancel()
            drawerUnreadPending = false
            viewModelScope.launch { runDrawerUnread() }
            return
        }
        if (drawerUnreadJob?.isActive == true) {
            drawerUnreadPending = true
            return
        }
        drawerUnreadJob = viewModelScope.launch {
            delay(DRAWER_UNREAD_DEFER_MS)
            runDrawerUnread()
            if (drawerUnreadPending) {
                drawerUnreadPending = false
                delay(DRAWER_UNREAD_DEBOUNCE_MS)
                runDrawerUnread()
            }
        }
    }

    private suspend fun runDrawerUnread() {
        val notificationUnreadByProject = userRepo.getNotifications().getOrNull().orEmpty()
            .filter { it.kind != "INVITED" && !it.isRead && it.projectId != null }
            .mapNotNull { it.projectId }
            .toSet()
        val currentTeamId = teamNameToId[_currentTeam.value]
        val currentProjectId = currentProjectId()
        // 当前项目群聊未读：直接用本地 _groups 缓存（markGroupRead 已本地清零，避免等已读接口在途返回）
        val localGroupUnread = if (currentProjectId != null) {
            _groups.value.any { it.unread > 0 }
        } else {
            false
        }
        val results = coroutineScope {
            // 每个团队并行拉项目；同团队项目并行拉群列表
            teamNameToId.entries.map { (teamName, teamId) ->
                async {
                    val projects = userRepo.getProjects(teamId).getOrNull().orEmpty()
                    val projectUnread = projects.map { project ->
                        async {
                            val groupUnread = if (project.id == currentProjectId) {
                                localGroupUnread
                            } else {
                                chatRepo.getGroups(project.id).getOrNull().orEmpty()
                                    .any { (it.unreadCount ?: 0) > 0 }
                            }
                            val unread = groupUnread || project.id in notificationUnreadByProject
                            ProjectUnread(project, unread, teamId == currentTeamId, project.id == currentProjectId)
                        }
                    }.awaitAll()
                    TeamUnreadResult(teamName, projectUnread.any { it.unread }, projectUnread)
                }
            }.awaitAll()
        }
        val unreadTeamNames = mutableSetOf<String>()
        val unreadProjectNames = mutableSetOf<String>()
        var hasUnreadElsewhere = false
        results.forEach { teamResult ->
            if (teamResult.teamHasUnread) unreadTeamNames.add(teamResult.teamName)
            teamResult.projects.forEach { pu ->
                if (pu.unread) {
                    if (pu.isCurrentTeamProject) unreadProjectNames.add(pu.project.name)
                    if (!pu.isCurrentProject) hasUnreadElsewhere = true
                }
            }
        }
        _unreadTeamNames.value = unreadTeamNames
        _unreadProjectNames.value = unreadProjectNames
        _hasUnreadBadge.value = hasUnreadElsewhere
    }

    private data class ProjectUnread(
        val project: ProjectDto,
        val unread: Boolean,
        val isCurrentTeamProject: Boolean,
        val isCurrentProject: Boolean
    )

    private data class TeamUnreadResult(
        val teamName: String,
        val teamHasUnread: Boolean,
        val projects: List<ProjectUnread>
    )

    /** 加入团队后刷新团队列表（复用 init 的加载逻辑） */
    fun refreshTeams() = loadTeams()

    /**
     * 打开抽屉时全量刷新：团队列表 + 当前团队项目（force 重载）+ 未读红点。
     * refreshTeams 仅在冷启动会重选当前团队，故对当前团队显式 force 重载项目，
     * 保证抽屉右侧项目列表是最新；autoSelectProject=false 不改动主界面当前项目。
     */
    fun refreshDrawer() {
        refreshTeams()
        val team = _currentTeam.value
        if (team.isNotEmpty()) {
            setCurrentTeam(team, force = true, autoSelectProject = false)
        }
        // 打开抽屉 = 用户要看最新红点，绕过节流立即算
        refreshDrawerUnread(force = true)
    }

    /** 当前团队是否可新建项目：仅 TEAM_OWNER（文档 §5.2）；角色未知时放行交给后端判定 */
    fun canCreateProject(teamName: String): Boolean =
        _teamDtos.value.firstOrNull { it.name == teamName }?.role?.let { it == "TEAM_OWNER" } ?: true

    fun setCurrentTeam(team: String, onProjectsLoaded: ((Boolean) -> Unit)? = null, force: Boolean = false, autoSelectProject: Boolean = true) {
        // 同团队重复选择默认跳过（避免冗余请求）；force=true 用于项目列表已被清空的场景（如 GitHub 页）强制重载
        if (!force && _currentTeam.value == team) return
        _currentTeam.value = team
        loadedProjectsTeam = null  // 失效旧缓存，防止 projectsOf 串数据
        _projects.value = emptyList()  // 先清空，避免加载期间显示上一个团队的项目
        loadAgents(team)
        loadProjects(team) {
            val firstProject = projectsOf(team).firstOrNull() ?: ""
            if (autoSelectProject) {
                // 冷启动 / 默认行为：自动选中该团队第一个项目并加载群聊
                _currentProject.value = firstProject
                if (firstProject.isNotEmpty()) {
                    loadGroups(firstProject, showLoading = true)
                } else if (routingInitPending) {
                    // 首个团队无项目 → 无需等群聊，直接完成冷启动路由
                    resolveRoutingReady()
                }
            }
            // 用户手动点击团队（autoSelectProject=false）时不改动 currentProject，
            // 主界面保持现状、不切换；是否高亮由抽屉按 currentProject 是否在当前团队项目列表中判断
            onProjectsLoaded?.invoke(firstProject.isNotEmpty())
            // 项目上下文已定，同步刷新任务红点（限定当前项目），避免沿用上一项目的未读状态
            refreshUnreadTaskNotifications()
            // 切团队后重算抽屉未读红点（右栏项目集合按新团队重算）
            refreshDrawerUnread()
        }
    }

    fun setCurrentProject(project: String) {
        if (_currentProject.value != project) {
            _currentProject.value = project
            loadGroups(project, showLoading = true)
            refreshUnreadTaskNotifications()
            // 已切换到该有未读的项目 → 重算头像红点（当前项目未读不再点亮）
            refreshDrawerUnread()
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

    /**
     * 通知直达：projectId → (teamName, projectName)，供点击系统通知后把团队/项目切到消息所属项目再进群。
     * 本地已加载的项目桶优先（命中零网络）；未命中时拉全量团队/项目查找（通知可能来自其他团队的项目）。
     */
    suspend fun resolveProjectContext(projectId: String): Pair<String, String>? {
        if (projectId.isBlank()) return null
        // 1) 本地已加载桶（当前团队的项目已 loadProjects 过）
        projectIdsByTeam.entries.forEach { (teamId, nameToId) ->
            nameToId.entries.firstOrNull { it.value == projectId }?.let { (name, _) ->
                val teamName = teamNameToId.entries.firstOrNull { it.value == teamId }?.key
                if (teamName != null) return teamName to name
            }
        }
        // 2) 全量拉取：遍历团队找该项目（通知可能属于其他团队）
        val teams = userRepo.getTeams().getOrNull().orEmpty()
        for (team in teams) {
            val projects = userRepo.getProjects(team.id).getOrNull().orEmpty()
            projects.firstOrNull { it.id == projectId }?.let { return team.name to it.name }
        }
        return null
    }

    /**
     * 退回列表页时刷新群聊最新消息摘要（onResume 调用）；projectId 未就绪时跳过，避免冷启动误清空。
     * 轮询/SSE 高频调用：上一次加载仍在途（接口慢于轮询间隔）时跳过本次，
     * 避免 loadGroupsJob 被反复 cancel 导致请求永远完不成（列表/未读永不刷新）。
     */
    fun refreshGroups() {
        if (currentProjectId() == null) return
        if (loadGroupsJob?.isActive == true) return
        loadGroups(_currentProject.value)
    }

    /** 标记群聊为已读（v2.0.6 §1.2）：调用后端 read 接口推进已读游标，本地立即清未读/@我。
     *  不再用本地时间戳模拟已读（此前跨端不同步、重启即失效）。
     *  成功后记录后端返回的 lastReadSequenceNo，作为群聊页「有人@你」未读 @ 判定基准
     *  （对齐 web ChatPanel：seq > 游标 且 mentions 含我 的才触发提示）。 */
    fun markGroupRead(groupId: String, lastSeenAt: Long? = null) {
        val projectId = currentProjectId() ?: return
        _groups.value = _groups.value.map {
            if (it.id == groupId) it.copy(unread = 0, mentionedMe = false) else it
        }
        // 用户主动点已读 → 红点即时反馈，绕过节流
        refreshDrawerUnread(force = true)
        viewModelScope.launch {
            chatRepo.markGroupRead(projectId, groupId, UUID.randomUUID().toString())
                .onSuccess { resp ->
                    lastReadSeqByGroup[groupId] = resp.lastReadSequenceNo
                }
            // 失败保持现状：游标由下次进群重试覆盖（对齐 web「已读失败不打断」）
        }
    }

    /** 该群已读游标（进群 markRead 成功后才有值；null = 尚未完成进群全读，不提示未读 @） */
    fun lastReadSeq(groupId: String): Long? = lastReadSeqByGroup[groupId]

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
        _teamsLoading.value = true
        viewModelScope.launch {
            // 按最后活跃倒序（v2.0.6 §10.1）；排序接口未就绪（后端 400/404）时回退普通团队列表，
            // 避免空团队列表导致冷启动误跳团队引导页
            val dtos = userRepo.getTeamsByLastActivity()
                .recoverCatching { userRepo.getTeams().getOrThrow() }
                .getOrElse { emptyList() }
            dtos.forEach { teamNameToId[it.name] = it.id }
            _teams.value = dtos.map { it.name }
            _teamDtos.value = dtos
            // 团队就绪后刷新抽屉未读红点（团队名/项目名集合）
            refreshDrawerUnread()
            // 无团队 → 初始就绪（启动页引导创建）；有团队 → 等首个团队项目及群聊加载完成再就绪
            if (dtos.isEmpty()) {
                _initialDataLoaded.value = true
            } else if (_currentTeam.value.isEmpty()) {
                routingInitPending = true
                dtos.firstOrNull()?.name?.let { setCurrentTeam(it) }
            }
            _teamsLoading.value = false
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
                // 按最后活跃倒序（v2.0.6 §10.2）；排序接口未就绪（后端 500/404）时回退普通项目列表
                val projectDtos = userRepo.getProjectsByLastActivity(teamDto.id)
                    .recoverCatching { userRepo.getProjects(teamDto.id).getOrThrow() }
                    .getOrElse { emptyList() }
                // 每次全量刷新该团队的项目映射，覆盖旧桶，防止跨团队残留
                projectIdsByTeam[teamDto.id] = projectDtos.associate { it.name to it.id }
                _projects.value = projectDtos.map { it.name }
                loadedProjectsTeam = team
                finished()
                return@launch
            }
            _projects.value = emptyList()
            loadedProjectsTeam = team
            finished()
        }
    }

    /**
     * 项目按最后活跃时间倒序：取各项目总群（PROJECT_MAIN）的 latestActivityAt 作为项目活跃时间。
     * 群拉取失败或总群无活动时间时按 0L 兜底排到最末（parseRfc3339 失败会回退当前时间，故先判空）。
     */
    private suspend fun sortProjectsByActivity(projectDtos: List<ProjectDto>): List<String> =
        projectDtos
            .map { dto ->
                val latest = chatRepo.getGroups(dto.id).getOrNull()
                    ?.firstOrNull { it.type == "PROJECT_MAIN" }
                    ?.latestActivityAt?.trim()?.takeIf { it.isNotEmpty() }
                    ?.let { parseRfc3339(it) } ?: 0L
                dto.name to latest
            }
            .sortedByDescending { it.second }
            .map { it.first }

    /** 拉取当前团队 / 项目下的群聊。只用真实 projectId，失败由数据层 mock 回退兜底。
     *  只保留当前用户所在的群：PROJECT_MAIN 总群恒保留（项目成员都在），
     *  需求群按群成员列表校验自己是否在群内（不在的群后端允许看到但发不了消息，直接隐藏）。
     *  [showLoading] 切换项目/团队时传 true 显示加载指示；轮询/SSE 后台刷新传默认 false，避免列表页频繁闪转圈。 */
    private fun loadGroups(projectName: String, showLoading: Boolean = false) {
        val projectId = currentProjectId() ?: run {
            _groups.value = emptyList()
            if (showLoading) _groupsLoading.value = false
            resolveRoutingReady()
            return
        }
        // 切换项目 / 团队时取消上一次未完成的群聊加载，防止旧项目群聊覆盖新结果
        loadGroupsJob?.cancel()
        if (showLoading) _groupsLoading.value = true
        loadGroupsJob = viewModelScope.launch {
            try {
                val dtos = chatRepo.getGroups(projectId).getOrNull() ?: emptyList()
                val myId = SessionStore.user()?.id
                // C1：先立即渲染后端返回的群列表（含可能不在的群），列表不再等每群 getMembers 返回；
                // 成员校验后台异步进行，校验完剔除不在的群（通常全部在群，visible 与 dtos 一致 → 不发第二次）
                _groups.value = toChatGroups(dtos)
                if (myId != null) {
                    coroutineScope {
                        dtos.map { dto ->
                            async {
                                // 所有群都拉成员（缓存头像供拼图，见 groupMembersCache）；总群恒显示，需求群校验是否在群内
                                val members = groupMembers(projectId, dto.id)
                                val ok = dto.type == "PROJECT_MAIN" || members?.any { it.id == myId } != false
                                if (ok) dto else null
                            }
                        }.awaitAll().filterNotNull().let { visible ->
                            if (visible.size != dtos.size) {
                                // v2.0.6 §1.1：未读/@我 直接用后端权威值（unreadCount / mentionedUnread）
                                _groups.value = toChatGroups(visible)
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("Groups", "loadGroups FAILED: ${e::class.simpleName}: ${e.message}", e)
            }
            if (showLoading) _groupsLoading.value = false
            resolveRoutingReady()
        }
    }

    /** 当前用户是否在该群成员列表中。成员关系缓存避免每次 loadGroups 对每个群重复调 getMembers；
     *  成员拉取失败时按「在群内」处理（信任 getGroups 返回的群列表），
     *  避免后端成员接口抖动导致需求群被整体误隐藏（列表/摘要/未读全部不更新）。 */
    private suspend fun isGroupMember(projectId: String, groupId: String, myId: String): Boolean =
        groupMembers(projectId, groupId)?.any { it.id == myId } ?: true

    /** 群成员列表（缓存，拼图头像 + 成员校验共用）：拉取失败返回 null（调用方按「在群内/无头像」兜底） */
    private suspend fun groupMembers(projectId: String, groupId: String): List<GroupMemberDto>? {
        groupMembersCache[groupId]?.let { return it }
        val result = chatRepo.getMembers(projectId, groupId).getOrNull()
        groupMembersCache[groupId] = result
        return result
    }

    /** 成员变动（SSE group.member.updated / 移入移出群后）清空成员缓存 */
    fun clearGroupMemberCache() {
        groupMembersCache.clear()
    }

    /** GroupDto → ChatGroup 映射（v2.0.6 §1.1：未读/@我 直接用后端权威值 unreadCount / mentionedUnread） */
    private fun toChatGroups(dtos: List<GroupDto>): List<ChatGroup> =
        sortGroups(dtos.map { dto ->
            val lastActive = parseRfc3339(dto.latestActivityAt.orEmpty())
            ChatGroup(
                id = dto.id,
                name = dto.title,
                lastMessage = dto.latestMessage.toSummary(),
                time = formatGroupTime(lastActive),
                unread = (dto.unreadCount ?: 0).coerceAtLeast(0),
                lastActiveTime = lastActive,
                mentionedMe = (dto.mentionedUnread ?: 0) > 0,
                type = if (dto.type == "PROJECT_MAIN") GroupType.PROJECT_MAIN else GroupType.REQUIREMENT,
                lastMessageType = dto.latestMessage?.type,
                // 拼图头像：成员缓存（loadGroups 已拉取）中取最多 9 个非空头像
                memberAvatars = groupMembersCache[dto.id].orEmpty().mapNotNull { it.avatar }
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
                    // 过滤已下线（ARCHIVED）：下线即视为删除，不再出现在 Agent 名片列表
                    _agents.value = dtos.filter { it.status != "ARCHIVED" }.map { it.toAgent() }
                }
                .onFailure { e ->
                    Log.e("Agents", "loadAgents FAILED: ${e::class.simpleName} ${e.message}")
                    _agents.value = emptyList()
                }
        }
    }

    /** 刷新当前团队的 Agent 列表（创建/编辑/发布/下线后调用） */
    fun refreshAgents() {
        val team = _currentTeam.value
        if (team.isNotEmpty()) loadAgents(team)
    }

    /**
     * 创建项目：POST /teams/{teamId}/projects，随后逐条绑定已选仓库，
     * 成功后刷新项目列表、选中新项目并拉取群聊（总群由后端自动生成）。
     * [newRepository] 非空时后端自动建仓（与 [repos] 二选一，清单一）。
     * 绑定仓库失败不阻断创建。
     */
    fun createProject(
        name: String,
        description: String?,
        memberIds: List<String>,
        repos: List<GitHubRepositoryDto>,
        newRepository: NewRepositoryRequest? = null
    ) {
        val teamName = _currentTeam.value
        val teamId = teamNameToId[teamName] ?: run {
            _createProjectState.value = CreateProjectState.Error("请先选择团队")
            return
        }
        if (_createProjectState.value == CreateProjectState.Loading) return
        _createProjectState.value = CreateProjectState.Loading
        viewModelScope.launch {
            userRepo.createProject(teamId, name, description, newRepository, UUID.randomUUID().toString())
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
                    finishCreateProject(teamName, name)
                }
                .onFailure { e ->
                    _createProjectState.value = CreateProjectState.Error(createProjectErrorMessage(e))
                }
        }
    }

    /**
     * 自动建仓创建项目：后端一次创建仅支持一个 newRepository（§22.5，无批量接口），
     * 故多个仓库名逐次调用创建项目接口（每次新建一个项目并自动建仓绑定）。
     * 单个失败仅记录不中断其余仓库；全部结束后以最后一个创建的项目为成功结果，
     * 只发一次 Success 避免界面重复跳转。memberIds 添加到每个创建的项目。
     */
    fun createProjectAutoRepos(
        name: String,
        description: String?,
        memberIds: List<String>,
        newRepoNames: List<String>
    ) {
        val teamName = _currentTeam.value
        val teamId = teamNameToId[teamName] ?: run {
            _createProjectState.value = CreateProjectState.Error("请先选择团队")
            return
        }
        if (_createProjectState.value == CreateProjectState.Loading) return
        _createProjectState.value = CreateProjectState.Loading
        viewModelScope.launch {
            var last: ProjectDto? = null
            var lastError: Throwable? = null
            newRepoNames.forEach { repoName ->
                userRepo.createProject(
                    teamId,
                    name,
                    description,
                    NewRepositoryRequest(name = repoName, description = description, isPrivate = true, displayName = name),
                    UUID.randomUUID().toString()
                ).fold(
                    onSuccess = { project ->
                        last = project
                        memberIds.forEach { userId ->
                            userRepo.addProjectMember(project.id, userId, UUID.randomUUID().toString())
                        }
                    },
                    onFailure = { e -> lastError = e }
                )
            }
            val project = last ?: run {
                _createProjectState.value = CreateProjectState.Error(createProjectErrorMessage(lastError))
                return@launch
            }
            finishCreateProject(teamName, name)
        }
    }

    /** 建仓错误文案：冲突/缺安装等按错误码给专属提示，其余回退通用文案 */
    private fun createProjectErrorMessage(e: Throwable?): String = when {
        e is ApiException && e.code == "GITHUB_REPOSITORY_CREATE_CONFLICT" ->
            "仓库名已存在或不合规，请修改仓库名后重试"
        e is ApiException && e.code == "GITHUB_INSTALLATION_REQUIRED" ->
            "团队有多个 GitHub 安装，自动建仓需指定安装"
        else -> e?.message ?: "创建项目失败"
    }

    /** 创建成功收尾：刷新项目列表、选中新项目并拉取群聊（总群由后端自动生成） */
    private fun finishCreateProject(teamName: String, name: String) {
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

    companion object {
        /** 抽屉红点刷新：默认先延后这么久再算，让 loadGroups 等主链路请求先发出（不抢同一 host 并发名额） */
        private const val DRAWER_UNREAD_DEFER_MS = 800L

        /** 抽屉红点刷新合并窗口：窗口内多次触发只算一次，风暴尾部补算一次 */
        private const val DRAWER_UNREAD_DEBOUNCE_MS = 3_000L
    }
}

/** 创建项目进度状态 */
sealed interface CreateProjectState {
    data object Idle : CreateProjectState
    data object Loading : CreateProjectState
    data class Success(val projectName: String, val groupId: String, val groupName: String) : CreateProjectState
    data class Error(val message: String) : CreateProjectState
}
