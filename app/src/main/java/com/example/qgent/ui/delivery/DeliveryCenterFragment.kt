package com.example.qgent.ui.delivery

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
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
import com.example.qgent.data.model.toDiffFile
import com.example.qgent.data.repository.DiffRepository
import com.example.qgent.data.repository.TaskRepository
import com.example.qgent.databinding.FragmentDeliveryCenterBinding
import com.example.qgent.viewmodel.MainViewModel
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.UUID

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
                binding.tvDeliveriesEmpty.text = "交付物暂不可用"
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

    // ── 交付物卡片（代码构建，简化） ──

    private fun fillDeliveries(items: List<DeliveryItemDto>) {
        binding.rvDeliveries.removeAllViews()
        items.forEach { item -> binding.rvDeliveries.addView(deliveryCard(item)) }
    }

    private fun deliveryCard(item: DeliveryItemDto): View {
        val card = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(12))
            setBackgroundResource(R.drawable.bg_card)
        }
        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(10) }
        card.layoutParams = lp

        // 标题：任务展示码 + 标题
        val title = item.source?.taskDisplayCode?.let { "($it) " }.orEmpty() + item.title
        card.addView(sectionText(title, 15f, bold = true))

        // 仓库/分支
        val repos = item.repositories?.joinToString("、") { "${it.name} / ${it.branch}" }.orEmpty()
        if (repos.isNotBlank()) card.addView(sectionText("📦 $repos", 13f))

        // Diff 统计
        card.addView(sectionText("Diff ${item.filesChanged} 个文件 · +${item.additions} / -${item.deletions}", 13f))

        // Review / Delivery 状态
        val review = item.reviewStatus ?: "-"
        val delivery = item.deliveryStatus ?: "-"
        card.addView(sectionText("Review $review · Delivery $delivery", 13f))

        // 逐仓库交付状态
        item.repositoryDeliveries?.forEach { rd ->
            val status = rd.deliveryStatus ?: "-"
            val reason = rd.failureReason?.takeIf { it.isNotBlank() }?.let { "（$it）" }.orEmpty()
            card.addView(sectionText("• ${rd.repositoryName ?: rd.repositoryId ?: "仓库"}：$status$reason", 12f))
        }

        // MR 链接
        item.mergeRequest?.let { mr ->
            card.addView(sectionText("🔀 MR #${mr.number} ${mr.title.orEmpty()}", 12f))
        }

        // 操作行
        val caps = item.capabilities
        val actions = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        fun actionButton(text: String, onClick: () -> Unit): TextView =
            TextView(requireContext()).apply {
                this.text = text
                setTextColor(requireContext().getColor(R.color.primary))
                textSize = 13f
                setPadding(dp(10), dp(6), dp(10), dp(6))
                setOnClickListener { onClick() }
            }
        if (caps?.canOpenResource == true && !item.diffId.isNullOrBlank()) {
            actions.addView(actionButton("查看 Diff") { showDiffFiles(item.diffId!!) })
        }
        if (caps?.canApprove == true && !item.source?.taskId.isNullOrBlank()) {
            actions.addView(actionButton("确认交付") { confirmDelivery(item) })
        }
        if (caps?.canReject == true && !item.source?.taskId.isNullOrBlank()) {
            actions.addView(actionButton("拒绝") { showRejectDialog(item) })
        }
        if (caps?.canRetryDelivery == true && !item.source?.taskId.isNullOrBlank()) {
            actions.addView(actionButton("重试交付") { retryDelivery(item) })
        }
        if (actions.childCount > 0) card.addView(actions)

        return card
    }

    private fun sectionText(text: String, size: Float, bold: Boolean = false): TextView =
        TextView(requireContext()).apply {
            this.text = text
            textSize = size
            setTextColor(requireContext().getColor(R.color.text_primary))
            if (bold) setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(0, dp(2), 0, dp(2))
        }

    /** 查看 Diff：拉取文件列表弹窗（仅文件名 + 增删统计） */
    private fun showDiffFiles(diffId: String) {
        val projectId = mainViewModel.currentProjectId() ?: return
        viewLifecycleOwner.lifecycleScope.launch {
            val files = diffRepo.getDiffFiles(projectId, diffId).getOrNull().orEmpty()
            val sb = StringBuilder()
            files.forEach { f ->
                sb.append("📄 ").append(f.fileName ?: f.path)
                    .append("  +${f.additions} -${f.deletions}").append("\n")
            }
            if (sb.isBlank()) sb.append("（该 Diff 无文件内容）")
            MaterialAlertDialogBuilder(requireContext())
                .setTitle("实时/交付 Diff")
                .setMessage(sb.toString())
                .setPositiveButton("关闭", null)
                .show()
        }
    }

    /** 确认交付（MR_FIRST 已自动授权；DIFF_FIRST 手动确认后进入交付） */
    private fun confirmDelivery(item: DeliveryItemDto) {
        val projectId = mainViewModel.currentProjectId() ?: return
        val taskId = item.source?.taskId ?: return
        viewLifecycleOwner.lifecycleScope.launch {
            diffRepo.confirmDiffReview(projectId, taskId, UUID.randomUUID().toString())
                .onSuccess { toast("已确认交付") }
                .onFailure { e -> toast("确认失败：${e.message}") }
            loadDeliveries()
        }
    }

    /** 拒绝交付（可填原因） */
    private fun showRejectDialog(item: DeliveryItemDto) {
        val input = android.widget.EditText(requireContext()).apply { hint = "拒绝原因（可选）" }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("拒绝交付")
            .setView(input)
            .setNegativeButton("取消", null)
            .setPositiveButton("确认拒绝") { _, _ ->
                val projectId = mainViewModel.currentProjectId() ?: return@setPositiveButton
                val taskId = item.source?.taskId ?: return@setPositiveButton
                viewLifecycleOwner.lifecycleScope.launch {
                    diffRepo.rejectDiffReview(
                        projectId, taskId,
                        input.text?.toString()?.trim()?.ifEmpty { null },
                        UUID.randomUUID().toString()
                    ).onSuccess { toast("已拒绝交付") }
                        .onFailure { e -> toast("拒绝失败：${e.message}") }
                    loadDeliveries()
                }
            }
            .show()
    }

    /** 重试交付（部分失败/失败后） */
    private fun retryDelivery(item: DeliveryItemDto) {
        val projectId = mainViewModel.currentProjectId() ?: return
        val taskId = item.source?.taskId ?: return
        viewLifecycleOwner.lifecycleScope.launch {
            diffRepo.retryDiffDelivery(projectId, taskId, UUID.randomUUID().toString())
                .onSuccess { toast("已重新发起交付") }
                .onFailure { e -> toast("重试失败：${e.message}") }
            loadDeliveries()
        }
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
                    findNavController().navigate(
                        R.id.action_deliveryCenter_to_mrDetail,
                        bundleOf(
                            com.example.qgent.ui.tasks.MergeRequestDetailFragment.ARG_MR_ID to mr.id,
                            com.example.qgent.ui.tasks.MergeRequestDetailFragment.ARG_PROJECT_ID to (mainViewModel.currentProjectId().orEmpty())
                        )
                    )
                }
            }
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(8) }
            row.layoutParams = lp
            row.addView(TextView(requireContext()).apply {
                text = "#${mr.number}"
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
        else -> status
    }

    private fun toast(msg: String) = Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
