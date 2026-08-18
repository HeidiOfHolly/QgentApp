package com.example.qgent.ui.tasks

import androidx.lifecycle.LiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.asLiveData
import androidx.lifecycle.viewModelScope
import com.example.qgent.R
import com.example.qgent.data.model.MergeRequestDto
import com.example.qgent.data.model.TaskListItemDto
import com.example.qgent.data.repository.GitHubRepository
import com.example.qgent.data.repository.TaskRepository
import com.example.qgent.model.Agent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/** 任务页/任务卡片列表 ViewModel：任务、Agent 近况、MR 三列表 + 任务筛选 */
class TaskListViewModel(
    private val repo: TaskRepository,
    private val githubRepo: GitHubRepository
) : ViewModel() {

    /** 任务页「我的任务」最多展示条数 */
    companion object {
        const val MAX_MY_TASKS = 2
        const val MAX_AGENT_ACTIVITIES = 5
        const val MAX_MR = 2

        /** 任务状态筛选项（§3.2 状态枚举的常用子集） */
        val STATUS_OPTIONS = listOf(
            "PLANNING" to "规划中",
            "PENDING" to "待执行",
            "RUNNING" to "执行中",
            "WAITING_DIFF_CONFIRMATION" to "待确认 Diff",
            "DELIVERING" to "交付中",
            "SUCCEEDED" to "已完成",
            "DELIVERY_FAILED" to "交付失败",
            "FAILED" to "失败",
            "CANCELLED" to "已取消"
        )
    }

    /** Agent 近况项：Agent 名称 + 完成任务 */
    data class AgentRun(
        val agentName: String,
        val taskTitle: String,
        val createdAt: String
    )

    /** 任务筛选条件（null 表示不筛） */
    data class TaskFilter(
        val groupId: String? = null,
        val status: String? = null,
        val createdBy: String? = null,
        val repositoryId: String? = null
    ) {
        val isActive: Boolean
            get() = groupId != null || status != null || createdBy != null || repositoryId != null
    }

    data class TaskListUiState(
        val loading: Boolean = false,
        val tasks: List<TaskListItemDto> = emptyList(),
        val agentRuns: List<AgentRun> = emptyList(),
        val activitiesLoading: Boolean = false,
        val mergeRequests: List<MergeRequestDto> = emptyList(),
        val filter: TaskFilter = TaskFilter(),
        val error: String? = null
    )

    private val _uiState = MutableStateFlow(TaskListUiState())
    val uiState: LiveData<TaskListUiState> = _uiState.asLiveData()

    private var loadedProjectId: String? = null
    private var loadedTeamId: String? = null
    /** MR 已加载项目：与 loadedProjectId（任务）独立，避免跨项目串数据 */
    private var loadedMrProjectId: String? = null
    /** 最近动态查询任务：新查询启动前取消旧查询，避免轮询并发导致旧结果覆盖新结果 */
    private var activitiesJob: kotlinx.coroutines.Job? = null

    /**
     * 加载任务。
     * @param filter 显式传入时应用新筛选（applyFilter）；为 null 时保留当前筛选
     *（轮询 / onResume 刷新不清除用户已选条件）。
     */
    fun loadTasks(projectId: String?, filter: TaskFilter? = null) {
        if (projectId == null) return
        loadedProjectId = projectId
        val effective = filter ?: _uiState.value.filter
        _uiState.value = _uiState.value.copy(loading = true, filter = effective)
        viewModelScope.launch {
            repo.getTasks(
                projectId,
                groupId = effective.groupId,
                status = effective.status,
                createdBy = effective.createdBy,
                repositoryId = effective.repositoryId
            )
                .onSuccess { tasks ->
                    android.util.Log.d("TaskPoll", "getTasks success: ${tasks.map { "${it.title}:${it.status}" }}")
                    _uiState.value = _uiState.value.copy(loading = false, tasks = tasks)
                }
                .onFailure { e ->
                    android.util.Log.e("TaskPoll", "getTasks FAILED: ${e.message}")
                    _uiState.value = _uiState.value.copy(loading = false, error = e.message ?: "加载任务失败")
                }
        }
    }

    /** 加载最近被调用的 Agent 及任务：遍历项目内 Agent，逐个查 task-runs（§20.6），
     *  按 createdAt 倒序取最新 MAX_AGENT_ACTIVITIES 条。单个 Agent 查询失败静默跳过。
     *  仅当列表为空（首次加载 / 暂无数据）时显示加载指示，已有内容刷新时静默更新避免轮询闪现；
     *  finally 保证 activitiesLoading 无论成功/失败/取消都复位，避免 ProgressBar 卡死。 */
    fun loadActivities(projectId: String?, agents: List<Agent>) {
        if (projectId == null) return
        // 新查询前取消上一次：轮询每 3 秒触发，多 agent 串行查询慢，不取消会导致旧协程晚到覆盖新结果
        activitiesJob?.cancel()
        _uiState.value = _uiState.value.copy(
            activitiesLoading = _uiState.value.agentRuns.isEmpty()
        )
        activitiesJob = viewModelScope.launch {
            try {
                val all = mutableListOf<AgentRun>()
                agents.forEach { agent ->
                    repo.getTaskRuns(projectId, agent.id)
                        .getOrElse { emptyList() }
                        .forEach { run ->
                            all.add(
                                AgentRun(
                                    agentName = agent.name,
                                    taskTitle = run.taskTitle ?: "执行任务",
                                    createdAt = run.createdAt
                                )
                            )
                        }
                }
                android.util.Log.d("Activities", "loadActivities agents=${agents.size} runs=${all.size} " +
                    "top=${all.sortedWith(compareByDescending { it.createdAt }).take(MAX_AGENT_ACTIVITIES).map { "${it.agentName}:${it.taskTitle}" }}")
                _uiState.value = _uiState.value.copy(
                    agentRuns = all.sortedWith(compareByDescending { it.createdAt }).take(MAX_AGENT_ACTIVITIES)
                )
            } finally {
                _uiState.value = _uiState.value.copy(activitiesLoading = false)
            }
        }
    }

    /**
     * 过滤 MR 只保留当前项目所属：
     * 后端 GET /projects/{id}/merge-requests 实际返回团队级 MR，而详情接口按项目校验，
     * 跨项目点击会报「MR 不存在或不可见」。MR 无 projectId，用 repositoryId
     * （= project_repositories.id，唯一归属项目）与当前项目仓库绑定比对。
     * 仓库列表拉取失败时不过滤（避免把有效 MR 误隐藏）。
     */
    private suspend fun filterMrByProject(projectId: String, mrs: List<MergeRequestDto>): List<MergeRequestDto> {
        val repoIds = githubRepo.getProjectRepositories(projectId).getOrNull().orEmpty().map { it.id }.toSet()
        if (repoIds.isEmpty()) return mrs
        return mrs.filter { it.repositoryId in repoIds }
    }

    private fun loadMergeRequests(projectId: String) {
        viewModelScope.launch {
            repo.getMergeRequests(projectId)
                .onSuccess { mrs ->
                    // 存全量（仅当前项目）；任务页展示时自行 take(MAX_MR)，MR 列表页用全量
                    _uiState.value = _uiState.value.copy(mergeRequests = filterMrByProject(projectId, mrs))
                }
                .onFailure { e ->
                    _uiState.value = _uiState.value.copy(error = e.message ?: "加载合并请求失败")
                }
        }
    }

    /** MR 列表页专用：只加载 MR（全量），供独立 MR 列表页使用 */
    fun loadMergeRequestsForList(projectId: String?) {
        if (projectId == null) return
        // 独立跟踪 MR 项目：与任务加载共用 loadedProjectId 会串数据（见 loadTasks）
        if (loadedMrProjectId == projectId && _uiState.value.mergeRequests.isNotEmpty()) return
        loadedMrProjectId = projectId
        viewModelScope.launch {
            repo.getMergeRequests(projectId)
                .onSuccess { mrs ->
                    _uiState.value = _uiState.value.copy(mergeRequests = filterMrByProject(projectId, mrs))
                }
                .onFailure { e ->
                    _uiState.value = _uiState.value.copy(error = e.message ?: "加载合并请求失败")
                }
        }
    }

    /** 更新筛选并重新查询（当前项目 id 由调用方传入） */
    fun applyFilter(projectId: String?, filter: TaskFilter) {
        loadTasks(projectId, filter)
    }

    fun consumeError() {
        _uiState.value = _uiState.value.copy(error = null)
    }
}

/**
 * 任务状态文字颜色（卡片/步骤/运行共用）：
 * 已完成保持默认色（teal）；失败与交付失败红色；已取消灰色；其余进行中类状态黄色。
 */
fun taskStatusColorRes(status: String): Int = when (status) {
    "FAILED", "DELIVERY_FAILED" -> R.color.exit_red
    "CANCELLED" -> R.color.gray
    "SUCCEEDED" -> R.color.teal
    else -> R.color.status_yellow
}
