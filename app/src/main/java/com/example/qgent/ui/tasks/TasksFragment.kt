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
import com.example.qgent.data.repository.GitHubRepository
import com.example.qgent.databinding.FragmentTasksBinding
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
    private val githubRepository: GitHubRepository
        get() = (requireActivity().application as QgentApp).container.githubRepository

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
    private val mrAdapter = MergeRequestAdapter { mr ->
        findNavController().navigate(
            R.id.action_tasks_to_mrDetail,
            Bundle().apply {
                putString(MergeRequestDetailFragment.ARG_MR_ID, mr.id)
                putString(MergeRequestDetailFragment.ARG_PROJECT_ID, mainViewModel.currentProjectId().orEmpty())
            }
        )
    }

    private var pollingJob: Job? = null

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

        // 更多任务 → 任务卡片列表
        binding.tvTaskMore.setOnClickListener {
            findNavController().navigate(R.id.action_tasks_to_taskCardList)
        }

        // 更多 MR → MR 列表页
        binding.tvMRMore.setOnClickListener {
            findNavController().navigate(R.id.action_tasks_to_mrList)
        }

        binding.rvTaskList.layoutManager = LinearLayoutManager(requireContext())
        binding.rvTaskList.adapter = taskAdapter
        binding.rvAgentTaskList.layoutManager = LinearLayoutManager(requireContext())
        binding.rvAgentTaskList.adapter = activityAdapter
        binding.rvMRList.layoutManager = LinearLayoutManager(requireContext())
        binding.rvMRList.adapter = mrAdapter

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
        }
        // Agent 名单变化时触发最近动态；agents 为空时也会清空旧动态，避免串项目
        mainViewModel.agents.observe(viewLifecycleOwner) { agents ->
            taskListViewModel.loadActivities(mainViewModel.currentProjectId(), agents)
        }

        // 三列表数据
        taskListViewModel.uiState.observe(viewLifecycleOwner) { state ->
            // 任务页仅展示最新 MAX_MY_TASKS 条（完整列表走「更多任务」）
            taskAdapter.submitList(state.tasks.take(TaskListViewModel.MAX_MY_TASKS))
            activityAdapter.submitList(state.agentRuns)
            mrAdapter.submitList(state.mergeRequests.take(TaskListViewModel.MAX_MR))
            // 空状态：列表为空时展示提示，非空时隐藏
            binding.tvTaskEmpty.isVisible = state.tasks.isEmpty()
            // 最近动态：加载中显示 ProgressBar，空态隐藏；完成后据列表是否为空切换空态提示
            binding.pbActivitiesLoading.isVisible = state.activitiesLoading
            binding.tvAgentEmpty.isVisible = !state.activitiesLoading && state.agentRuns.isEmpty()
            binding.tvMREmpty.isVisible = state.mergeRequests.isEmpty()
            state.error?.let {
                taskListViewModel.consumeError()
            }
            // 三列表刷新完成 → 收起下拉刷新动画（uiState 更新即视为刷新结束）
            binding.swipeRefresh.isRefreshing = false
        }
    }

    /** 下拉刷新：重新拉取任务 / MR / 最近动态 */
    private fun refreshAllData() {
        val projectId = mainViewModel.currentProjectId()
        taskListViewModel.loadTasks(projectId)
        taskListViewModel.loadMergeRequestsForList(projectId)
        taskListViewModel.loadActivities(projectId, mainViewModel.agents.value.orEmpty())
        loadRepoNameMap(projectId)
    }

    override fun onResume() {
        super.onResume()
        mainViewModel.refreshUnreadTaskNotifications()
        val projectId = mainViewModel.currentProjectId()
        // loadTasks 内部对同项目防重复跳过，这里先强制刷新一次再启动轮询
        taskListViewModel.loadTasks(projectId)
        taskListViewModel.loadMergeRequestsForList(projectId)
        taskListViewModel.loadActivities(projectId, mainViewModel.agents.value.orEmpty())
        loadRepoNameMap(projectId)
        startPolling()
    }

    override fun onPause() {
        super.onPause()
        stopPolling()
    }

    /** 轮询：任务页 Tab 停留时每 3 秒刷新任务/MR/最近动态（后端任务执行进度实时可见） */
    private fun startPolling() {
        if (pollingJob?.isActive == true) return
        val projectId = mainViewModel.currentProjectId() ?: return
        pollingJob = viewLifecycleOwner.lifecycleScope.launch {
            while (true) {
                delay(POLL_INTERVAL_MS)
                taskListViewModel.loadTasks(projectId)
                taskListViewModel.loadMergeRequestsForList(projectId)
                taskListViewModel.loadActivities(projectId, mainViewModel.agents.value.orEmpty())
            }
        }
    }

    private fun stopPolling() {
        pollingJob?.cancel()
        pollingJob = null
    }

    /** 拉取项目绑定仓库，建立 repositoryId → 仓库名 映射供 MR 卡片展示 */
    private fun loadRepoNameMap(projectId: String?) {
        if (projectId == null) return
        viewLifecycleOwner.lifecycleScope.launch {
            githubRepository.getProjectRepositories(projectId)
                .onSuccess { repos ->
                    mrAdapter.updateRepoNames(repos.associate { it.id to it.displayName })
                }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        private const val POLL_INTERVAL_MS = 3_000L
    }
}
