package com.example.qgent.ui.tasks

import android.app.AlertDialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
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
import com.example.qgent.data.repository.UserRepository
import com.example.qgent.databinding.FragmentTaskCardListBinding
import com.example.qgent.viewmodel.MainViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** 任务卡片列表页：展示当前项目的任务（§16），顶部横向筛选（需求群/状态/发起人/仓库） */
class TaskCardListFragment : Fragment() {

    private var _binding: FragmentTaskCardListBinding? = null
    private val binding get() = _binding!!

    private var eventStreamJob: Job? = null
    private var pollingJob: Job? = null

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
    private val userRepository: UserRepository
        get() = (requireActivity().application as QgentApp).container.userRepository

    private val taskAdapter = TaskCardAdapter { task ->
        findNavController().navigate(
            R.id.action_taskCardList_to_taskDetail,
            Bundle().apply {
                putString(TaskDetailFragment.ARG_TASK_ID, task.id)
                putString(TaskDetailFragment.ARG_PROJECT_ID, task.projectId)
            }
        )
    }
    private val filterAdapter = FilterChipAdapter { type -> showFilterOptions(type) }

    // 候选值缓存
    private var groupOptions: Map<String, String> = emptyMap()       // id -> 群名
    private var repoOptions: Map<String, String> = emptyMap()        // id -> 仓库名
    private var creatorOptions: List<Pair<String, String>> = emptyList() // id -> 显示名

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentTaskCardListBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.ivBack.setOnClickListener { findNavController().navigateUp() }

        binding.rvFilters.layoutManager = LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false)
        binding.rvFilters.adapter = filterAdapter

        binding.rvTaskList.layoutManager = LinearLayoutManager(requireContext())
        binding.rvTaskList.adapter = taskAdapter

        // 下拉刷新：重新拉取当前任务列表
        binding.swipeRefresh.setOnRefreshListener { refresh() }

        taskListViewModel.uiState.observe(viewLifecycleOwner) { state ->
            taskAdapter.submitList(state.tasks)
            binding.tvEmpty.isVisible = state.tasks.isEmpty() && !state.loading
            // 刷新筛选行选中值；从当前任务列表提取发起人候选
            refreshFilterRow(state)
            // uiState 更新即视为刷新结束，收起下拉刷新动画
            binding.swipeRefresh.isRefreshing = false
            state.error?.let {
                Toast.makeText(requireContext(), it, Toast.LENGTH_SHORT).show()
                taskListViewModel.consumeError()
            }
        }
    }

    /** 下拉刷新：按当前筛选条件重新拉取任务 */
    private fun refresh() {
        val projectId = mainViewModel.currentProjectId() ?: return
        taskListViewModel.loadTasks(projectId)
    }

    override fun onResume() {
        super.onResume()
        val projectId = mainViewModel.currentProjectId() ?: return
        loadCandidates(projectId)
        taskListViewModel.loadTasks(projectId)
        startEventStream()
        startPolling()
    }

    override fun onPause() {
        super.onPause()
        stopEventStream()
        stopPolling()
    }

    /** 轮询兜底：SSE 偶发断连时任务进度仍能刷新（3s 一次） */
    private fun startPolling() {
        if (pollingJob?.isActive == true) return
        val projectId = mainViewModel.currentProjectId() ?: return
        pollingJob = viewLifecycleOwner.lifecycleScope.launch {
            while (true) {
                delay(POLL_INTERVAL_MS)
                taskListViewModel.loadTasks(projectId)
            }
        }
    }

    private fun stopPolling() {
        pollingJob?.cancel()
        pollingJob = null
    }

    /**
     * 项目级 SSE：收到任务相关事件（task.updated / task-run.* / diff.* / delivery.*）→ 刷新任务列表，
     * 让任务进度（规划中 → 执行中 → 完成）实时可见。ProjectEventStream 已由 AppContainer 装配。
     */
    private fun startEventStream() {
        val projectId = mainViewModel.currentProjectId() ?: return
        val stream = (requireActivity().application as QgentApp).container.projectEventStream
        stream.startProject(projectId)
        if (eventStreamJob == null) {
            eventStreamJob = viewLifecycleOwner.lifecycleScope.launch {
                stream.events.collect { event ->
                    when (event.type) {
                        com.example.qgent.data.sse.SseEventType.TASK_UPDATED,
                        com.example.qgent.data.sse.SseEventType.TASK_STEP_UPDATED,
                        com.example.qgent.data.sse.SseEventType.TASK_RUN_UPDATED,
                        com.example.qgent.data.sse.SseEventType.TASK_RUN_STEP_PROGRESS,
                        com.example.qgent.data.sse.SseEventType.DIFF_CREATED,
                        com.example.qgent.data.sse.SseEventType.DELIVERY_REPOSITORY_UPDATED,
                        com.example.qgent.data.sse.SseEventType.DELIVERY_FAILED,
                        com.example.qgent.data.sse.SseEventType.DELIVERY_COMPLETED,
                        com.example.qgent.data.sse.SseEventType.DIFF_REVIEW_SKIPPED,
                        // MR 打开/合并/状态变化、分支锁定/解锁 → 任务交付状态与分支可能联动变化
                        com.example.qgent.data.sse.SseEventType.MERGE_REQUEST_UPDATED,
                        com.example.qgent.data.sse.SseEventType.WORK_BRANCH_UPDATED -> {
                            taskListViewModel.loadTasks(projectId)
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
        (requireActivity().application as QgentApp).container.projectEventStream.stop()
    }

    /** 加载需求群 / 仓库 / 发起人候选。发起人取项目成员（稳定来源，不随筛选收窄） */
    private fun loadCandidates(projectId: String) {
        viewLifecycleOwner.lifecycleScope.launch {
            chatRepository.getGroups(projectId).onSuccess { groups ->
                groupOptions = groups.associate { it.id to it.title }
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            githubRepository.getProjectRepositories(projectId).onSuccess { repos ->
                repoOptions = repos.associate { it.id to it.displayName }
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            val teamId = mainViewModel.currentTeamId() ?: return@launch
            val memberNames = userRepository.getTeamMembers(teamId).getOrNull().orEmpty()
                .associate { it.userId to it.displayName }
            creatorOptions = userRepository.getProjectMembers(projectId).getOrNull().orEmpty()
                .map { it.userId to (memberNames[it.userId] ?: it.userId) }
        }
    }

    private fun refreshFilterRow(state: TaskListViewModel.TaskListUiState) {
        val f = state.filter
        // 发起人候选保持全量：项目成员为稳定来源，再与任务列表并集兜底，
        // 避免筛选后任务列表收窄导致已选值之外的条件消失
        val fromTasks = state.tasks
            .mapNotNull { it.createdByUser }
            .distinctBy { it.id }
            .map { it.id to it.displayName }
        creatorOptions = (creatorOptions + fromTasks).distinctBy { it.first }
        filterAdapter.submitList(
            listOf(
                FilterChip(TaskFilterType.GROUP, "需求群", f.groupId?.let { groupOptions[it] }),
                FilterChip(TaskFilterType.STATUS, "状态", f.status?.let { statusLabel(it) }),
                FilterChip(TaskFilterType.CREATED_BY, "发起人", f.createdBy?.let { creatorLabel(it) }),
                FilterChip(TaskFilterType.REPOSITORY, "仓库", f.repositoryId?.let { repoOptions[it] })
            )
        )
    }

    private fun statusLabel(status: String): String =
        TaskListViewModel.STATUS_OPTIONS.firstOrNull { it.first == status }?.second ?: status

    private fun creatorLabel(id: String): String =
        creatorOptions.firstOrNull { it.first == id }?.second ?: id

    /** 点击筛选项：弹出下拉三角列表（候选值单选），选中后应用筛选 */
    private fun showFilterOptions(type: TaskFilterType) {
        val projectId = mainViewModel.currentProjectId() ?: return
        val current = taskListViewModel.uiState.value?.filter
        when (type) {
            TaskFilterType.GROUP -> showChoiceDialog(
                "按需求群筛选",
                groupOptions.entries.map { it.value to it.key },
                current?.groupId
            ) { id -> applyFilter(projectId, current?.copy(groupId = id)) }

            TaskFilterType.STATUS -> showChoiceDialog(
                "按状态筛选",
                TaskListViewModel.STATUS_OPTIONS.map { it.second to it.first },
                current?.status
            ) { status -> applyFilter(projectId, current?.copy(status = status)) }

            TaskFilterType.CREATED_BY -> showChoiceDialog(
                "按发起人筛选",
                creatorOptions.map { it.second to it.first },
                current?.createdBy
            ) { id -> applyFilter(projectId, current?.copy(createdBy = id)) }

            TaskFilterType.REPOSITORY -> showChoiceDialog(
                "按仓库筛选",
                repoOptions.entries.map { it.value to it.key },
                current?.repositoryId
            ) { id -> applyFilter(projectId, current?.copy(repositoryId = id)) }
        }
    }

    private fun showChoiceDialog(
        title: String,
        options: List<Pair<String, String>>,   // 显示文本 -> 值
        selected: String?,
        onSelect: (String?) -> Unit
    ) {
        // 「全部」作为首项：选中后清除该维度筛选（null 表示不筛）；无候选时仍可点「全部」重置
        val labels = arrayOf(getString(R.string.filter_all)) + options.map { it.first }.toTypedArray()
        val selectedIndex = if (selected == null) 0
        else options.indexOfFirst { it.second == selected }.takeIf { it >= 0 }?.plus(1) ?: 0
        val builder = AlertDialog.Builder(requireContext())
            .setTitle(title)
            .setNegativeButton(R.string.cancel, null)
        var dialogRef: AlertDialog? = null
        builder.setSingleChoiceItems(labels, selectedIndex) { _, which ->
            onSelect(if (which == 0) null else options[which - 1].second)
            dialogRef?.dismiss()
        }
        dialogRef = builder.create()
        dialogRef.show()
    }

    private fun applyFilter(projectId: String, filter: TaskListViewModel.TaskFilter?) {
        taskListViewModel.applyFilter(projectId, filter ?: TaskListViewModel.TaskFilter())
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        private const val POLL_INTERVAL_MS = 3_000L
    }
}
