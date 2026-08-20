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
import com.example.qgent.data.sse.ProjectEventStream
import com.example.qgent.data.sse.SseEventType
import com.example.qgent.databinding.FragmentMrListBinding
import com.example.qgent.viewmodel.MainViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** MR 列表页：展示当前项目的全部 MR（§13），复用 item_mr_card 卡片。
 *  设计要点：WS+SSE 双订阅，事件只作刷新信号（收到一律重新查询，不解析 payload 为完整数据）；
 *  onResume 启动 / onPause 停止，避免不可见时空转。 */
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
    /** 项目级 SSE 事件流（收到 MR/交付/DryRun/preflight 事件 → 刷新列表） */
    private val eventStream: ProjectEventStream
        get() = (requireActivity().application as QgentApp).container.projectEventStream
    /** WebSocket 实时通道（单连接用户级聚合，事件名与 SSE 一致） */
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
        startEventStream()
    }

    override fun onPause() {
        super.onPause()
        stopEventStream()
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

    /**
     * WS+SSE 双订阅（设计要点）：WebSocket 主通道 + SSE 兜底，事件名一致。
     * 事件只作刷新信号：收到 MR/交付/DryRun/preflight/分支相关事件一律重新查询列表，
     * 不解析 payload 为完整数据；重复/乱序/晚到事件无害（以 REST 查询为准）。
     */
    private fun startEventStream() {
        val projectId = mainViewModel.currentProjectId() ?: return
        eventStream.startProject(projectId)
        if (eventStreamJob == null) {
            eventStreamJob = viewLifecycleOwner.lifecycleScope.launch {
                eventStream.events.collect { event ->
                    if (eventAffectsMrList(event.type)) refreshMrList(projectId)
                }
            }
        }
        if (wsJob == null) {
            wsJob = viewLifecycleOwner.lifecycleScope.launch {
                realtimeClient.events.collect { frame ->
                    val type = runCatching { SseEventType.fromWire(frame.type) }.getOrNull()
                    if (eventAffectsMrList(type)) refreshMrList(projectId)
                }
            }
        }
    }

    /** 哪些事件会改变 MR 列表（MR/DryRun/preflight/交付/分支锁定） */
    private fun eventAffectsMrList(type: SseEventType?): Boolean = when (type) {
        SseEventType.MERGE_REQUEST_UPDATED,
        SseEventType.DRY_RUN_UPDATED,
        SseEventType.PREFLIGHT_UPDATED,
        SseEventType.DELIVERY_STARTED,
        SseEventType.DELIVERY_REPOSITORY_UPDATED,
        SseEventType.DELIVERY_FAILED,
        SseEventType.DELIVERY_COMPLETED,
        SseEventType.TASK_UPDATED -> true
        else -> false
    }

    /** 事件到：强制重新查询 MR 列表 + 仓库名映射（force 跳过防重复，事件一律以查询为准） */
    private fun refreshMrList(projectId: String) {
        taskListViewModel.loadMergeRequestsForList(projectId, force = true)
        loadRepoNameMap(projectId)
    }

    private fun stopEventStream() {
        eventStreamJob?.cancel()
        eventStreamJob = null
        wsJob?.cancel()
        wsJob = null
        eventStream.stop()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
