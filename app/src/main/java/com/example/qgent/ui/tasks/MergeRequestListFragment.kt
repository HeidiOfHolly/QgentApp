package com.example.qgent.ui.tasks

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
import com.example.qgent.data.repository.GitHubRepository
import com.example.qgent.data.sse.SseEventType
import com.example.qgent.databinding.FragmentMrListBinding
import com.example.qgent.viewmodel.MainViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** MR 列表页：展示当前项目的全部 MR（§13），复用 item_mr_card 卡片；实时监听项目 SSE + WS（MR/分支/仓库事件） */
class MergeRequestListFragment : Fragment() {

    private var _binding: FragmentMrListBinding? = null
    private val binding get() = _binding!!

    private val mainViewModel: MainViewModel by activityViewModels {
        (requireActivity().application as QgentApp).container.mainViewModelFactory
    }
    private val taskListViewModel: TaskListViewModel by activityViewModels {
        (requireActivity().application as QgentApp).container.taskListViewModelFactory
    }
    private val githubRepository: GitHubRepository
        get() = (requireActivity().application as QgentApp).container.githubRepository

    /** 项目级 SSE 事件流：MR/分支/仓库状态事件到达 → 刷新 MR 列表与仓库名映射 */
    private val eventStream: com.example.qgent.data.sse.ProjectEventStream
        get() = (requireActivity().application as QgentApp).container.projectEventStream

    /** WebSocket 实时通道（单连接用户级聚合；事件名与 SSE 一致） */
    private val realtimeClient: com.example.qgent.data.ws.RealtimeClient
        get() = (requireActivity().application as QgentApp).container.realtimeClient

    private var eventStreamJob: Job? = null
    private var wsJob: Job? = null

    private val mrAdapter = MergeRequestAdapter { mr ->
        findNavController().navigate(
            R.id.action_mrList_to_mrDetail,
            Bundle().apply {
                putString(MergeRequestDetailFragment.ARG_MR_ID, mr.id)
                putString(MergeRequestDetailFragment.ARG_PROJECT_ID, mainViewModel.currentProjectId().orEmpty())
            }
        )
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentMrListBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.ivBack.setOnClickListener { findNavController().navigateUp() }
        binding.rvMrList.layoutManager = LinearLayoutManager(requireContext())
        binding.rvMrList.adapter = mrAdapter

        taskListViewModel.uiState.observe(viewLifecycleOwner) { state ->
            mrAdapter.submitList(state.mergeRequests)
            binding.tvEmpty.isVisible = state.mergeRequests.isEmpty()
            state.error?.let {
                Toast.makeText(requireContext(), it, Toast.LENGTH_SHORT).show()
                taskListViewModel.consumeError()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        val projectId = mainViewModel.currentProjectId() ?: return
        taskListViewModel.loadMergeRequestsForList(projectId)
        loadRepoNameMap(projectId)
        startEventStream(projectId)
    }

    override fun onPause() {
        super.onPause()
        stopEventStream()
    }

    /**
     * 实时监听：项目 SSE + WebSocket。
     * MR 状态变化（打开/更新/关闭/合并）、分支锁定/解锁、仓库授权变化 → 重新查询接口（不信任 payload 为完整数据）。
     */
    private fun startEventStream(projectId: String) {
        eventStream.startProject(projectId)
        if (eventStreamJob == null) {
            eventStreamJob = viewLifecycleOwner.lifecycleScope.launch {
                eventStream.events.collect { event ->
                    when (event.type) {
                        SseEventType.MERGE_REQUEST_UPDATED,
                        SseEventType.WORK_BRANCH_UPDATED,
                        SseEventType.GITHUB_REPOSITORY_UPDATED -> {
                            taskListViewModel.loadMergeRequestsForList(projectId)
                            loadRepoNameMap(projectId)
                        }
                        else -> Unit
                    }
                }
            }
        }
        if (wsJob == null) {
            wsJob = viewLifecycleOwner.lifecycleScope.launch {
                realtimeClient.events.collect { frame ->
                    when (frame.type) {
                        "merge-request.updated", "work-branch.updated", "github-repository.updated" -> {
                            taskListViewModel.loadMergeRequestsForList(projectId)
                            loadRepoNameMap(projectId)
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
        wsJob?.cancel()
        wsJob = null
        eventStream.stop()
    }

    /** 拉取项目绑定仓库，建立 repositoryId → 仓库名 映射供 MR 卡片展示 */
    private fun loadRepoNameMap(projectId: String) {
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
