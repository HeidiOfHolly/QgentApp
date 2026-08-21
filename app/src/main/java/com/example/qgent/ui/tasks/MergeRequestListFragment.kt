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
import com.example.qgent.ui.personal.setSkeletonLoading
import com.example.qgent.viewmodel.MainViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** MR 列表页：展示当前项目的全部 MR（§13），复用 item_mr_card 卡片；实时监听项目 SSE + WS（MR/分支/交付/仓库事件） */
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

    /** 首次加载是否完成（onDone 回调或有数据时置位）：控制骨架屏显示，事件刷新不重复闪骨架 */
    private var mrLoaded = false

    /** 项目级 SSE 事件流：MR/分支/交付/仓库状态事件到达 → 刷新 MR 列表与仓库名映射 */
    private val eventStream: ProjectEventStream
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
            if (state.mergeRequests.isNotEmpty()) mrLoaded = true
            mrAdapter.submitList(state.mergeRequests)
            // 骨架屏：首次加载完成前且无数据时显示；有数据/加载完成（onDone）隐藏
            setSkeletonLoading(binding.viewSkeleton.root, !mrLoaded && state.mergeRequests.isEmpty())
            binding.tvEmpty.isVisible = state.mergeRequests.isEmpty() && mrLoaded
            state.error?.let {
                Toast.makeText(requireContext(), it, Toast.LENGTH_SHORT).show()
                taskListViewModel.consumeError()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        val projectId = mainViewModel.currentProjectId() ?: return
        taskListViewModel.loadMergeRequestsForList(projectId, onDone = { mrLoaded = true })
        loadRepoNameMap(projectId)
        startEventStream(projectId)
    }

    override fun onPause() {
        super.onPause()
        stopEventStream()
    }

    /**
     * 实时监听：项目 SSE + WebSocket（事件名一致，双通道兜底）。
     * MR 打开/更新/关闭/合并、分支锁定/解锁、DryRun/预检/交付、任务状态、仓库授权变化 →
     * 一律重新查询接口（事件只作刷新信号，不信任 payload 为完整数据）。
     */
    private fun startEventStream(projectId: String) {
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

    /** 会影响 MR 列表的事件：MR 状态 / 分支锁定解锁 / DryRun / 预检 / 交付 / 任务状态 / 仓库授权 */
    private fun eventAffectsMrList(type: SseEventType?): Boolean = when (type) {
        SseEventType.MERGE_REQUEST_UPDATED,
        SseEventType.WORK_BRANCH_UPDATED,
        SseEventType.GITHUB_REPOSITORY_UPDATED,
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
