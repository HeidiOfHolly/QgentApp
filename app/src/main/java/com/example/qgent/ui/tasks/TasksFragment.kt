package com.example.qgent.ui.tasks

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.qgent.QgentApp
import com.example.qgent.R
import com.example.qgent.data.repository.ChatRepository
import com.example.qgent.data.repository.GitHubRepository
import com.example.qgent.data.repository.TaskRepository
import com.example.qgent.databinding.FragmentTasksBinding
import com.example.qgent.ui.common.CreateTaskDialog
import com.example.qgent.viewmodel.MainViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class TasksFragment : Fragment() {

    private var _binding: FragmentTasksBinding? = null
    private val binding get() = _binding!!

    private val mainViewModel: MainViewModel by activityViewModels {
        (requireActivity().application as QgentApp).container.mainViewModelFactory
    }
    private val taskListViewModel: TaskListViewModel by activityViewModels {
        (requireActivity().application as QgentApp).container.taskListViewModelFactory
    }
    private val chatRepository: ChatRepository
        get() = (requireActivity().application as QgentApp).container.chatRepository
    private val githubRepository: GitHubRepository
        get() = (requireActivity().application as QgentApp).container.githubRepository
    private val taskRepository: TaskRepository
        get() = (requireActivity().application as QgentApp).container.taskRepository

    private val taskAdapter = TaskCardAdapter { task ->
        findNavController().navigate(
            R.id.action_tasks_to_taskDetail,
            Bundle().apply {
                putString(TaskDetailFragment.ARG_TASK_ID, task.id)
                putString(TaskDetailFragment.ARG_PROJECT_ID, task.projectId)
            }
        )
    }
    private val activityAdapter = ActivityAdapter()

    private var pollingJob: Job? = null
    private var eventStreamJob: Job? = null

    /** 项目级 SSE：任务/MR/分支事件到达 → 立即刷新（减少对 3s 轮询的依赖） */
    private val eventStream: com.example.qgent.data.sse.ProjectEventStream
        get() = (requireActivity().application as QgentApp).container.projectEventStream
    /** 新建任务弹窗正在加载（拉群）中：防止连点触发多次加载/弹窗 */
    private var isNewTaskDialogLoading = false

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentTasksBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        // 铃铛 → 消息列表
        binding.btnNotification.setOnClickListener {
            findNavController().navigate(R.id.taskMessageListFragment)
        }

        // 新建任务 → 弹「新建任务」（与群聊一致，但可选分支群）
        binding.btnNewTask.setOnClickListener {
            showNewTaskDialog()
        }

        // 更多任务 → 任务卡片列表
        binding.tvTaskMore.setOnClickListener {
            findNavController().navigate(R.id.action_tasks_to_taskCardList)
        }

        binding.rvTaskList.layoutManager = LinearLayoutManager(requireContext())
        binding.rvTaskList.adapter = taskAdapter
        binding.rvAgentTaskList.layoutManager = LinearLayoutManager(requireContext())
        binding.rvAgentTaskList.adapter = activityAdapter

        // 下拉刷新：重新拉取任务 / MR / 最近动态
        binding.swipeRefresh.setOnRefreshListener { refreshAllData() }

        // 未读任务类通知 → 铃铛右上角红点
        mainViewModel.unreadTaskNotifications.observe(viewLifecycleOwner) { hasUnread ->
            binding.ivInviteBadge.isVisible = hasUnread
        }

        // 顶部标题跟随抽屉切换的当前团队 / 项目
        mainViewModel.currentTeam.observe(viewLifecycleOwner) { team ->
            binding.tvTeamName.text = team
        }
        mainViewModel.currentProject.observe(viewLifecycleOwner) { project ->
            binding.tvProjectName.text = project.ifEmpty { getString(R.string.short_test) }
            // 切项目立即按新项目重新加载任务与最近动态（轮询每次也现取项目 id，双保险防串项目）
            if (project.isNotEmpty()) {
                loadMyTasks()
                taskListViewModel.loadActivities(mainViewModel.currentProjectId(), mainViewModel.agents.value.orEmpty())
            }
        }
        // Agent 名单变化时触发最近动态；agents 为空时也会清空旧动态，避免串项目
        mainViewModel.agents.observe(viewLifecycleOwner) { agents ->
            taskListViewModel.loadActivities(mainViewModel.currentProjectId(), agents)
        }

        // 两列表数据
        taskListViewModel.uiState.observe(viewLifecycleOwner) { state ->
            // 任务页仅展示当前用户最近 MAX_MY_TASKS 条（myTasks 已按创建者过滤且不受列表页筛选影响）
            taskAdapter.submitList(state.myTasks.take(TaskListViewModel.MAX_MY_TASKS))
            activityAdapter.submitList(state.agentRuns)
            // 空状态：列表为空时展示提示，非空时隐藏
            binding.tvTaskEmpty.isVisible = state.myTasks.isEmpty()
            // 最近动态：未加载出来前/无数据时统一显示空态提示
            binding.tvAgentEmpty.isVisible = state.agentRuns.isEmpty()
            state.error?.let {
                taskListViewModel.consumeError()
            }
            // 刷新完成 → 收起下拉刷新动画（uiState 更新即视为刷新结束）
            binding.swipeRefresh.isRefreshing = false
        }
    }

    /** 新建任务：加载当前项目已加入的需求群（ACTIVE REQUIREMENT），弹窗中选分支群后创建。
     *  弹窗加载（拉群）完成前重复点击直接忽略，只加载/弹窗一次。 */
    private fun showNewTaskDialog() {
        if (isNewTaskDialogLoading) return
        val projectId = mainViewModel.currentProjectId() ?: return
        isNewTaskDialogLoading = true
        viewLifecycleOwner.lifecycleScope.launch {
            val groups = chatRepository.getGroups(projectId).getOrNull().orEmpty()
                .filter { it.type == "REQUIREMENT" && it.status == "ACTIVE" }
            CreateTaskDialog(
                context = requireContext(),
                projectId = projectId,
                taskRepo = taskRepository,
                githubRepo = githubRepository,
                scope = viewLifecycleOwner.lifecycleScope,
                candidateGroups = groups,
                initialGroupId = null,
                showGroupSelector = true,
                onSubmit = { pid, groupId, title, requirement, repoIds, baseRef ->
                    CreateTaskDialog.create(
                        taskRepo = taskRepository,
                        scope = viewLifecycleOwner.lifecycleScope,
                        projectId = pid,
                        groupId = groupId,
                        title = title,
                        requirement = requirement,
                        repoIds = repoIds,
                        baseRef = baseRef,
                        context = requireContext()
                    )
                }
            ).show()
            isNewTaskDialogLoading = false
        }
    }

    /** 加载任务首页数据：当前用户的任务（不读列表页筛选状态，返回后列表筛选不影响首页） */
    private fun loadMyTasks() {
        val projectId = mainViewModel.currentProjectId()
        taskListViewModel.loadMyTasks(projectId, com.example.qgent.data.SessionStore.user()?.id)
    }

    /** 下拉刷新：重新拉取任务 / 最近动态 */
    private fun refreshAllData() {
        loadMyTasks()
        taskListViewModel.loadActivities(mainViewModel.currentProjectId(), mainViewModel.agents.value.orEmpty())
    }

    override fun onResume() {
        super.onResume()
        mainViewModel.refreshUnreadTaskNotifications()
        // 首页加载当前用户任务（myTasks 体系，不受列表页筛选影响）
        loadMyTasks()
        taskListViewModel.loadActivities(mainViewModel.currentProjectId(), mainViewModel.agents.value.orEmpty())
        startEventStream()
        startPolling()
    }

    override fun onPause() {
        super.onPause()
        stopPolling()
        stopEventStream()
    }

    /** 项目 SSE：任务/交付/MR/分支事件 → 刷新任务与最近动态（事件驱动，轮询保留兜底） */
    private fun startEventStream() {
        val projectId = mainViewModel.currentProjectId() ?: return
        eventStream.startProject(projectId)
        if (eventStreamJob == null) {
            eventStreamJob = viewLifecycleOwner.lifecycleScope.launch {
                eventStream.events.collect { event ->
                    when (event.type) {
                        com.example.qgent.data.sse.SseEventType.TASK_UPDATED,
                        com.example.qgent.data.sse.SseEventType.TASK_STEP_UPDATED,
                        com.example.qgent.data.sse.SseEventType.TASK_RUN_UPDATED,
                        com.example.qgent.data.sse.SseEventType.DIFF_CREATED,
                        com.example.qgent.data.sse.SseEventType.DELIVERY_REPOSITORY_UPDATED,
                        com.example.qgent.data.sse.SseEventType.DELIVERY_FAILED,
                        com.example.qgent.data.sse.SseEventType.DELIVERY_COMPLETED,
                        com.example.qgent.data.sse.SseEventType.DIFF_REVIEW_SKIPPED,
                        // MR/分支事件：任务交付状态与分支可能联动变化
                        com.example.qgent.data.sse.SseEventType.MERGE_REQUEST_UPDATED,
                        com.example.qgent.data.sse.SseEventType.WORK_BRANCH_UPDATED -> {
                            refreshAllData()
                        }
                        else -> Unit
                    }
                }
            }
        }
    }

    private fun stopEventStream() {
        eventStreamJob?.cancel()
        eventStreamJob = null
        eventStream.stop()
    }

    /** 轮询：任务页 Tab 停留时每 3 秒刷新任务/最近动态（后端任务执行进度实时可见）。
     *  每次现取当前项目 id，切项目后轮询自动跟随新项目，避免捕获旧 projectId 导致跨项目串数据。 */
    private fun startPolling() {
        if (pollingJob?.isActive == true) return
        pollingJob = viewLifecycleOwner.lifecycleScope.launch {
            while (true) {
                delay(POLL_INTERVAL_MS)
                val projectId = mainViewModel.currentProjectId()
                if (projectId == null) continue
                taskListViewModel.loadMyTasks(projectId, com.example.qgent.data.SessionStore.user()?.id)
                taskListViewModel.loadActivities(projectId, mainViewModel.agents.value.orEmpty())
            }
        }
    }

    private fun stopPolling() {
        pollingJob?.cancel()
        pollingJob = null
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        private const val POLL_INTERVAL_MS = 3_000L
    }
}
