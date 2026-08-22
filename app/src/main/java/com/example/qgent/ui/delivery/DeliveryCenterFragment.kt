package com.example.qgent.ui.delivery

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.os.bundleOf
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.example.qgent.QgentApp
import com.example.qgent.R
import com.example.qgent.data.model.DeliveryItemDto
import com.example.qgent.data.model.MergeRequestDto
import com.example.qgent.data.repository.DiffRepository
import com.example.qgent.data.repository.TaskRepository
import com.example.qgent.databinding.FragmentDeliveryCenterBinding
import com.example.qgent.viewmodel.MainViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * 交付中心（CODE 交付物 + MR，diff 审核之后的交付流程入口）。
 * - 交付物卡片：仓库/分支、Diff 统计、Review/Delivery 状态、逐仓库状态、MR #n；
 *   操作（capabilities 控制）：查看 Diff / 确认交付 / 拒绝 / 重试交付（复用 diff-review 接口）；
 * - MR 区：项目 MR 列表（从任务页挪来），点击进 MR 详情（CQ+1 / merge 等流程操作在详情页）。
 */
class DeliveryCenterFragment : Fragment() {

    private var _binding: FragmentDeliveryCenterBinding? = null
    private val binding get() = _binding!!
    private val mainViewModel: MainViewModel by activityViewModels {
        (requireActivity().application as QgentApp).container.mainViewModelFactory
    }
    private val taskRepo: TaskRepository
        get() = (requireActivity().application as QgentApp).container.taskRepository
    private val diffRepo: DiffRepository
        get() = (requireActivity().application as QgentApp).container.diffRepository

    private var eventStreamJob: Job? = null
    private var pendingLoads = 0
    private var loadingGeneration = 0
    /** 同一时刻只允许一种刷新来源，避免页面加载层与下拉刷新圈同时出现。 */
    private var isRefreshInFlight = false

    /** 交付物操作（查看 Diff / 确认 / 拒绝 / 重试），成功后刷新本页交付物列表 */
    private val deliveryActions by lazy {
        DeliveryItemActions(this, mainViewModel, diffRepo) { loadDeliveries() }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentDeliveryCenterBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.swipeRefresh.setOnRefreshListener { refreshAll(showOverlay = false) }
        binding.tvMRMore.setOnClickListener {
            findNavController().navigate(R.id.action_deliveryCenter_to_mrList)
        }
        binding.tvDeliveriesMore.setOnClickListener {
            findNavController().navigate(R.id.action_deliveryCenter_to_deliveryItemList)
        }
        // 顶栏按钮：TestSet / 通知，仅项目管理员可见
        binding.btnTestset.setOnClickListener {
            findNavController().navigate(R.id.action_deliveryCenter_to_testsetList)
        }
        // 通知铃铛 → 管理员消息列表：仅接收当前项目的 MR 申请审批通知（MR_PENDING）
        binding.btnNotification.setOnClickListener {
            findNavController().navigate(R.id.action_deliveryCenter_to_deliveryMessageList)
        }
        // 未读 MR 申请审批通知 → 铃铛右上角红点
        mainViewModel.unreadDeliveryNotifications.observe(viewLifecycleOwner) { hasUnread ->
            binding.ivNotificationBadge.isVisible = hasUnread
        }
        loadAdminButtons()
        refreshAll()
    }

    /** 顶栏按钮（TestSet / 通知）与 MR 区仅项目管理员可见：管理员身份后端权威（GET /projects/{id}.role，Team Owner 兜底） */
    private fun loadAdminButtons() {
        val projectId = mainViewModel.currentProjectId() ?: return
        viewLifecycleOwner.lifecycleScope.launch {
            val isAdmin = mainViewModel.isProjectAdmin(projectId)
            binding.btnTestset.isVisible = isAdmin
            binding.btnNotification.isVisible = isAdmin
            binding.mrSection.isVisible = isAdmin
        }
    }

    override fun onResume() {
        super.onResume()
        startEventStream()
        // 返回交付中心时刷新 MR 申请未读红点
        mainViewModel.refreshUnreadDeliveryNotifications()
    }

    override fun onPause() {
        super.onPause()
        eventStreamJob?.cancel()
        eventStreamJob = null
    }

    private fun refreshAll(showOverlay: Boolean = true) {
        if (isRefreshInFlight) {
            binding.swipeRefresh.isRefreshing = false
            return
        }
        if (mainViewModel.currentProjectId() == null) {
            binding.swipeRefresh.isRefreshing = false
            return
        }
        val generation = beginLoading(2, showOverlay)
        loadDeliveries(startLoading = false, generation = generation)
        loadMRs(startLoading = false, generation = generation)
    }

    private fun beginLoading(requestCount: Int, showOverlay: Boolean = true): Int {
        loadingGeneration += 1
        pendingLoads = requestCount
        isRefreshInFlight = true
        binding.deliveryLoadingState.isVisible = showOverlay
        // 自动刷新使用页面加载层并暂时禁止下拉；手动下拉只保留 SwipeRefreshLayout 的圈。
        binding.swipeRefresh.isEnabled = !showOverlay
        if (showOverlay) binding.swipeRefresh.isRefreshing = false
        if (requestCount > 1) {
            binding.deliveriesEmptyState.isVisible = false
            binding.mrEmptyState.isVisible = false
        }
        return loadingGeneration
    }

    private fun finishLoading(generation: Int) {
        if (generation != loadingGeneration) return
        pendingLoads = (pendingLoads - 1).coerceAtLeast(0)
        if (pendingLoads == 0) {
            isRefreshInFlight = false
            binding.deliveryLoadingState.isVisible = false
            binding.swipeRefresh.isRefreshing = false
            binding.swipeRefresh.isEnabled = true
        }
    }

    /** 项目级 SSE：交付/MR 事件 → 刷新交付中心 */
    private fun startEventStream() {
        val projectId = mainViewModel.currentProjectId() ?: return
        val stream = (requireActivity().application as QgentApp).container.projectEventStream
        stream.startProject(projectId)
        if (eventStreamJob == null) {
            eventStreamJob = viewLifecycleOwner.lifecycleScope.launch {
                stream.events.collect { event ->
                    when (event.type) {
                        com.example.qgent.data.sse.SseEventType.DELIVERY_STARTED,
                        com.example.qgent.data.sse.SseEventType.DELIVERY_REPOSITORY_UPDATED,
                        com.example.qgent.data.sse.SseEventType.DELIVERY_FAILED,
                        com.example.qgent.data.sse.SseEventType.DELIVERY_COMPLETED,
                        com.example.qgent.data.sse.SseEventType.DIFF_REVIEW_SKIPPED,
                        com.example.qgent.data.sse.SseEventType.MERGE_REQUEST_UPDATED -> refreshAll()
                        else -> Unit
                    }
                }
            }
        }
    }

    private fun loadDeliveries(startLoading: Boolean = true, generation: Int? = null) {
        val projectId = mainViewModel.currentProjectId() ?: return
        if (startLoading && isRefreshInFlight) return
        val activeGeneration = if (startLoading) beginLoading(1) else generation ?: beginLoading(1)
        if (startLoading) binding.deliveriesEmptyState.isVisible = false
        viewLifecycleOwner.lifecycleScope.launch {
            // 10s 超时：接口未就绪/网络差时快速显示空态，不阻塞页面
            try {
                val items = runCatching {
                    kotlinx.coroutines.withTimeout(10_000) { taskRepo.getDeliveryItems(projectId, type = "CODE").getOrThrow() }
                }.getOrNull()
                if (items == null) {
                    binding.rvDeliveries.removeAllViews()
                    binding.deliveriesEmptyState.isVisible = true
                    binding.tvDeliveriesEmpty.text = getString(R.string.delivery_unavailable_title)
                    binding.tvDeliveriesEmptyHint.text = getString(R.string.delivery_unavailable_hint)
                } else {
                    binding.deliveriesEmptyState.isVisible = items.isEmpty()
                    binding.tvDeliveriesEmpty.text = getString(R.string.delivery_empty_title)
                    binding.tvDeliveriesEmptyHint.text = getString(R.string.delivery_empty_hint)
                    fillDeliveries(items)
                }
            } finally {
                finishLoading(activeGeneration)
            }
        }
    }

    private fun loadMRs(startLoading: Boolean = true, generation: Int? = null) {
        val projectId = mainViewModel.currentProjectId() ?: return
        if (startLoading && isRefreshInFlight) return
        val activeGeneration = if (startLoading) beginLoading(1) else generation ?: beginLoading(1)
        if (startLoading) binding.mrEmptyState.isVisible = false
        viewLifecycleOwner.lifecycleScope.launch {
            // MR 区仅项目管理员可见：非管理员隐藏并跳过加载
            if (!mainViewModel.isProjectAdmin(projectId)) {
                binding.mrSection.isVisible = false
                finishLoading(activeGeneration)
                return@launch
            }
            try {
                val mrs = runCatching {
                    kotlinx.coroutines.withTimeout(10_000) { taskRepo.getMergeRequests(projectId).getOrThrow() }
                }.getOrNull()
                if (mrs == null) {
                    binding.rvMRList.removeAllViews()
                    binding.mrEmptyState.isVisible = true
                    binding.tvMREmpty.text = getString(R.string.delivery_unavailable_title)
                    binding.tvMREmptyHint.text = getString(R.string.delivery_unavailable_hint)
                } else {
                    binding.mrEmptyState.isVisible = mrs.isEmpty()
                    binding.tvMREmpty.text = getString(R.string.merge_request_empty_title)
                    binding.tvMREmptyHint.text = getString(R.string.merge_request_empty_hint)
                    fillMRs(mrs)
                }
            } finally {
                finishLoading(activeGeneration)
            }
        }
    }

    // ── 交付物卡片（共享构建器，交付中心最多展示 5 条，完整列表走「更多交付物」） ──

    private fun fillDeliveries(items: List<DeliveryItemDto>) {
        // 排序：未创建 MR（mergeRequest==null）优先 → 已创建 MR 殿后，同级内按创建时间倒序（最新优先）
        val sorted = items.sortedWith(
            compareByDescending<DeliveryItemDto> { it.mergeRequest == null }
                .thenByDescending { it.createdAt }
        )
        binding.rvDeliveries.removeAllViews()
        val showMore = sorted.size > MAX_DELIVERIES
        // 交付物区标题行右侧「更多交付物 ›」（>5 条才显示），点击跳转全量列表页
        binding.tvDeliveriesMore.isVisible = showMore
        sorted.take(MAX_DELIVERIES).forEach { item ->
            binding.rvDeliveries.addView(
                DeliveryItemCardBuilder.build(requireContext(), item, deliveryActions, ::openTaskDetail)
            )
        }
    }

    /** 交付物卡片点击 → 交付物详情页（含逐仓库进度 + MR/任务入口） */
    private fun openTaskDetail(item: DeliveryItemDto) {
        findNavController().navigate(
            R.id.action_deliveryCenter_to_deliveryItemDetail,
            bundleOf(DeliveryItemDetailFragment.ARG_ITEM_JSON to DeliveryItemDetailFragment.toJson(item))
        )
    }

    // ── MR 区 ──

    /**
     * 填充 MR 列表：排序 = 未完成(OPEN) > 已完成(MERGED/CLOSED) > 待创建(PENDING_CREATE)，
     * 同级内按 createdAt 倒序（最新优先）；最多展示 MAX_MR_DISPLAY 条，超出显示「更多 MR」。
     */
    private fun fillMRs(mrs: List<MergeRequestDto>) {
        val sorted = mrs.sortedWith(
            compareByDescending<MergeRequestDto> { it.status == "OPEN" }               // 未完成优先
                .thenBy { it.status == "PENDING_CREATE" }                               // 已创建 > 未创建
                .thenByDescending { it.createdAt }                                      // 最新优先
        )
        val showMore = sorted.size > MAX_MR_DISPLAY
        binding.tvMRMore.isVisible = showMore
        binding.rvMRList.removeAllViews()
        sorted.take(MAX_MR_DISPLAY).forEach { mr ->
            val row = com.example.qgent.databinding.ItemMrRowBinding.inflate(layoutInflater, binding.rvMRList, false)
            row.root.setOnClickListener {
                // PENDING_CREATE 是列表投影占位（§43：number=0/webUrl=null，真实 MR 未创建），
                // 不得用占位 id 调真实 MR 详情；点击仅提示，不跳转
                if (mr.status == "PENDING_CREATE") {
                    android.widget.Toast.makeText(requireContext(), "MR 待创建，请先通过预检与 CQ+1", android.widget.Toast.LENGTH_SHORT).show()
                } else {
                    findNavController().navigate(
                        R.id.action_deliveryCenter_to_mrDetail,
                        bundleOf(
                            com.example.qgent.ui.tasks.MergeRequestDetailFragment.ARG_MR_ID to mr.id,
                            com.example.qgent.ui.tasks.MergeRequestDetailFragment.ARG_PROJECT_ID to (mainViewModel.currentProjectId().orEmpty())
                        )
                    )
                }
            }
            // PENDING_CREATE 占位 number=0（§43），不当作真实 PR 号展示
            row.tvMrNumber.text = if (mr.status == "PENDING_CREATE") "待创建" else "#${mr.number}"
            row.tvMrBranch.text = "  ${mr.sourceBranch} → ${mr.targetBranch}"
            row.tvMrStatus.text = mrStatusLabel(mr.status)
            row.tvMrStatus.setTextColor(requireContext().getColor(
                if (mr.status == "MERGED") R.color.green else R.color.text_secondary
            ))
            binding.rvMRList.addView(row.root)
        }
    }

    private fun mrStatusLabel(status: String): String = when (status) {
        "OPEN" -> "进行中"
        "MERGED" -> "已合并"
        "CLOSED" -> "已关闭"
        // §43：列表投影占位，真实 MR 未创建（number=0/webUrl=null），待预检/CQ+1 通过后创建
        "PENDING_CREATE" -> "待创建"
        else -> status
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        /** 交付中心展示交付物数量上限，超出走「更多交付物」全量列表页 */
        const val MAX_DELIVERIES = 5
        /** 交付中心 MR 展示数量上限，超出显示「更多 MR」跳全量列表页 */
        const val MAX_MR_DISPLAY = 5
    }
}
