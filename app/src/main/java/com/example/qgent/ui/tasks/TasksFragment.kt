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

    private val taskAdapter = TaskCardAdapter { }
    private val activityAdapter = ActivityAdapter()
    private val mrAdapter = MergeRequestAdapter()

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

        // 三列表数据
        taskListViewModel.uiState.observe(viewLifecycleOwner) { state ->
            // 任务页仅展示最新 MAX_MY_TASKS 条（完整列表走「更多任务」）
            taskAdapter.submitList(state.tasks.take(TaskListViewModel.MAX_MY_TASKS))
            activityAdapter.submitList(state.agentRuns)
            mrAdapter.submitList(state.mergeRequests.take(TaskListViewModel.MAX_MR))
            state.error?.let {
                taskListViewModel.consumeError()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        mainViewModel.refreshUnreadTaskNotifications()
        val projectId = mainViewModel.currentProjectId()
        val teamId = mainViewModel.currentTeamId()
        taskListViewModel.load(projectId, teamId, mainViewModel.agents.value.orEmpty())
        loadRepoNameMap(projectId)
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
}
