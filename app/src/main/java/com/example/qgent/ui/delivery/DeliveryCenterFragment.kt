package com.example.qgent.ui.delivery

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
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
        binding.swipeRefresh.setOnRefreshListener { refreshAll() }
        binding.tvMRMore.setOnClickListener {
            findNavController().navigate(R.id.action_deliveryCenter_to_mrList)
        }
        binding.tvDeliveriesMore.setOnClickListener {
            findNavController().navigate(R.id.action_deliveryCenter_to_deliveryItemList)
        }
        refreshAll()
    }

    override fun onResume() {
        super.onResume()
        startEventStream()
    }

    override fun onPause() {
        super.onPause()
        eventStreamJob?.cancel()
        eventStreamJob = null
    }

    private fun refreshAll() {
        loadDeliveries()
        loadMRs()
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

    private fun loadDeliveries() {
        val projectId = mainViewModel.currentProjectId() ?: return
        // 立即收起下拉刷新，避免新接口（delivery-items）在沙箱/后端未就绪时超时导致一直转圈
        binding.swipeRefresh.isRefreshing = false
        viewLifecycleOwner.lifecycleScope.launch {
            // 10s 超时：接口未就绪/网络差时快速显示空态，不阻塞页面
            val items = runCatching {
                kotlinx.coroutines.withTimeout(10_000) { taskRepo.getDeliveryItems(projectId, type = "CODE").getOrThrow() }
            }.getOrNull()
            if (items == null) {
                binding.tvDeliveriesEmpty.isVisible = true
                binding.tvDeliveriesEmpty.text = "交付物功能暂不可用"
            } else {
                binding.tvDeliveriesEmpty.isVisible = items.isEmpty()
                fillDeliveries(items)
            }
        }
    }

    private fun loadMRs() {
        val projectId = mainViewModel.currentProjectId() ?: return
        viewLifecycleOwner.lifecycleScope.launch {
            val mrs = runCatching {
                kotlinx.coroutines.withTimeout(10_000) { taskRepo.getMergeRequests(projectId).getOrThrow() }
            }.getOrNull()
            if (mrs == null) {
                binding.tvMREmpty.isVisible = true
                binding.tvMREmpty.text = "合并请求暂不可用"
            } else {
                binding.tvMREmpty.isVisible = mrs.isEmpty()
                fillMRs(mrs)
            }
        }
    }

    // ── 交付物卡片（共享构建器，交付中心最多展示 5 条，完整列表走「更多交付物」） ──

    private fun fillDeliveries(items: List<DeliveryItemDto>) {
        binding.rvDeliveries.removeAllViews()
        val showMore = items.size > MAX_DELIVERIES
        // 交付物区标题行右侧「更多交付物 ›」（>5 条才显示），点击跳转全量列表页
        binding.tvDeliveriesMore.isVisible = showMore
        items.take(MAX_DELIVERIES).forEach { item ->
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

    private fun fillMRs(mrs: List<MergeRequestDto>) {
        binding.rvMRList.removeAllViews()
        mrs.forEach { mr ->
            val row = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(dp(12), dp(10), dp(12), dp(10))
                setBackgroundResource(R.drawable.bg_card)
                setOnClickListener {
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
            }
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(8) }
            row.layoutParams = lp
            row.addView(TextView(requireContext()).apply {
                // PENDING_CREATE 占位 number=0（§43），不当作真实 PR 号展示
                text = if (mr.status == "PENDING_CREATE") "待创建" else "#${mr.number}"
                setTextColor(requireContext().getColor(R.color.primary))
                textSize = 15f
                setTypeface(null, android.graphics.Typeface.BOLD)
            })
            row.addView(TextView(requireContext()).apply {
                text = "  ${mr.sourceBranch} → ${mr.targetBranch}"
                textSize = 13f
                setTextColor(requireContext().getColor(R.color.text_primary))
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            row.addView(TextView(requireContext()).apply {
                text = mrStatusLabel(mr.status)
                textSize = 12f
                setTextColor(requireContext().getColor(
                    if (mr.status == "MERGED") R.color.green else R.color.text_secondary
                ))
            })
            binding.rvMRList.addView(row)
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
    }
}
