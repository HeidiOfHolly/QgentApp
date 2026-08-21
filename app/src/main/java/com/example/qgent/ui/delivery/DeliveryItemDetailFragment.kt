package com.example.qgent.ui.delivery

import android.content.Intent
import android.net.Uri
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
import com.example.qgent.data.model.DeliveryRepositoryDeliveryDto
import com.example.qgent.data.repository.TaskRepository
import com.example.qgent.databinding.FragmentDeliveryItemDetailBinding
import com.example.qgent.viewmodel.MainViewModel
import com.google.gson.Gson
import kotlinx.coroutines.launch
import java.util.UUID

/** 交付物详情页：交付物信息 + 逐仓库交付进度 + MR 摘要入口（MR webUrl 外部打开；关联任务跳任务详情） */
class DeliveryItemDetailFragment : Fragment() {

    private var _binding: FragmentDeliveryItemDetailBinding? = null
    private val binding get() = _binding!!

    private val mainViewModel: MainViewModel by activityViewModels {
        (requireActivity().application as QgentApp).container.mainViewModelFactory
    }
    private val taskRepository: TaskRepository
        get() = (requireActivity().application as QgentApp).container.taskRepository

    private val item: DeliveryItemDto? by lazy {
        arguments?.getString(ARG_ITEM_JSON)?.let { Gson().fromJson(it, DeliveryItemDto::class.java) }
    }
    private var eventStreamJob: kotlinx.coroutines.Job? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentDeliveryItemDetailBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.ivBack.setOnClickListener { findNavController().navigateUp() }
        val it = item
        if (it == null) {
            Toast.makeText(requireContext(), "交付物数据缺失", Toast.LENGTH_SHORT).show()
            findNavController().navigateUp()
            return
        }
        bind(it)
        loadPreflight(it)
    }

    override fun onResume() {
        super.onResume()
        startEventStream()
    }

    override fun onPause() {
        super.onPause()
        stopEventStream()
    }

    /** 项目级 SSE：预检/DryRun/MR 事件 → 刷新预检状态（计划 §4.4；事件只触发刷新，以查询接口为准） */
    private fun startEventStream() {
        val projectId = mainViewModel.currentProjectId() ?: return
        val stream = (requireActivity().application as QgentApp).container.projectEventStream
        stream.startProject(projectId)
        if (eventStreamJob == null) {
            eventStreamJob = viewLifecycleOwner.lifecycleScope.launch {
                stream.events.collect { event ->
                    when (event.type) {
                        com.example.qgent.data.sse.SseEventType.PREFLIGHT_UPDATED,
                        com.example.qgent.data.sse.SseEventType.DRY_RUN_UPDATED,
                        com.example.qgent.data.sse.SseEventType.MERGE_REQUEST_UPDATED -> {
                            item?.let { loadPreflight(it) }
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

    /**
     * 统一创建 MR 自动预检流程（计划 §4.2/§4.3）：
     * 按 Task 查全部仓库预检状态，按状态展示操作按钮：
     * - 无预检记录 → 只读提示（MR_FIRST 交付后后端自动发起预检，不提供手动「创建 MR」）
     * - REQUESTED/DRY_RUN_QUEUED/DRY_RUN_RUNNING → 「预检中」，禁用
     * - WAITING_CQ → 显示「CQ+1」
     * - CQ_REJECTED → 显示「重新预检」
     * - FAILED/STALE → 显示「重试预检」
     * - CREATING_MR → 「正在创建 MR」，禁用
     * - MR_CREATED → 显示真实 MR 链接，无按钮
     */
    private fun loadPreflight(itemDto: DeliveryItemDto) {
        val projectId = mainViewModel.currentProjectId() ?: return
        val taskId = itemDto.source?.taskId ?: return
        viewLifecycleOwner.lifecycleScope.launch {
            // 复位操作区
            binding.btnCqApprove.isVisible = false
            binding.btnCreateMr.isVisible = false
            val status = taskRepository.getTaskMergeRequestPreflight(projectId, taskId)
                .getOrNull()?.firstOrNull()   // 交付物详情按首个仓库展示
            if (status == null) {
                // MR_FIRST 交付后由后端自动发起 Dry Run（§27.10/§46），前端无需手动「创建 MR」；
                // 未查到预检记录时只读提示，交由后端自动推进（SSE preflight.updated 到达后自动刷新）
                binding.tvPreflightStatus.isVisible = true
                binding.tvPreflightStatus.text = "等待后端自动发起预检"
                return@launch
            }
            // 真实 MR 已创建 → 展示链接，无操作按钮
            if (status.status == "MR_CREATED" || status.mergeRequest != null) {
                binding.tvPreflightStatus.isVisible = true
                val mr = status.mergeRequest
                binding.tvPreflightStatus.text = "MR 已创建：MR #${mr?.number ?: "?"} ${mr?.title.orEmpty()}"
                return@launch
            }
            when (status.status) {
                // Dry Run 通过，等待 CQ+1
                "WAITING_CQ" -> {
                    binding.btnCqApprove.isVisible = true
                    binding.tvPreflightStatus.isVisible = true
                    binding.tvPreflightStatus.text = "DryRun 通过，等待独立成员 CQ+1"
                    binding.btnCqApprove.setOnClickListener { doCqApprove(projectId, status.dryRunId) }
                }
                // 正在创建 MR（CQ+1 已通过，后端异步创建）
                "CREATING_MR" -> {
                    binding.tvPreflightStatus.isVisible = true
                    binding.tvPreflightStatus.text = "CQ+1 已通过，正在创建 MR…"
                }
                // CQ 被拒绝
                "CQ_REJECTED" -> {
                    binding.tvPreflightStatus.isVisible = true
                    binding.tvPreflightStatus.text = "CQ 被拒绝：${status.failureReason ?: "见审查意见"}"
                    binding.btnCreateMr.isVisible = true
                    binding.btnCreateMr.text = "重新预检"
                    binding.btnCreateMr.setOnClickListener {
                        requestPreflight(projectId, taskId, itemDto)
                    }
                }
                // 预检失败
                "FAILED", "STALE" -> {
                    binding.tvPreflightStatus.isVisible = true
                    binding.tvPreflightStatus.text = "预检失败：${status.failureReason ?: status.status}"
                    binding.btnCreateMr.isVisible = true
                    binding.btnCreateMr.text = "重试预检"
                    binding.btnCreateMr.setOnClickListener {
                        requestPreflight(projectId, taskId, itemDto)
                    }
                }
                // 进行中
                "REQUESTED", "DRY_RUN_QUEUED", "DRY_RUN_RUNNING" -> {
                    binding.tvPreflightStatus.isVisible = true
                    binding.tvPreflightStatus.text = "预检中（${status.status}）…"
                }
                else -> {
                    binding.tvPreflightStatus.isVisible = true
                    binding.tvPreflightStatus.text = "预检状态：${status.status}"
                }
            }
        }
    }

    /** 申请 MR 预检：统一入口，启动 Dry Run（计划 §4.2：前端只调预检申请接口，不自行拼装 targetBranch/Testset） */
    private fun requestPreflight(projectId: String, taskId: String, itemDto: DeliveryItemDto) {
        val repositoryId = itemDto.repositoryDeliveries?.firstOrNull()?.repositoryId
        if (repositoryId == null) {
            Toast.makeText(requireContext(), "缺少仓库信息", Toast.LENGTH_SHORT).show()
            return
        }
        viewLifecycleOwner.lifecycleScope.launch {
            taskRepository.requestMergeRequestPreflight(
                projectId, taskId, repositoryId, UUID.randomUUID().toString()
            ).onSuccess {
                Toast.makeText(requireContext(), "已申请预检，正在运行 DryRun", Toast.LENGTH_SHORT).show()
                item?.let { loadPreflight(it) }
            }.onFailure { e ->
                Toast.makeText(requireContext(), "申请预检失败：${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    /** CQ+1：对 Dry Run 提交审批，通过后服务端自动创建 MR（计划 §4.3） */
    private fun doCqApprove(projectId: String, dryRunId: String?) {
        if (dryRunId.isNullOrBlank()) {
            Toast.makeText(requireContext(), "暂无可审批的 DryRun", Toast.LENGTH_SHORT).show()
            return
        }
        viewLifecycleOwner.lifecycleScope.launch {
            taskRepository.dryRunCqApprove(projectId, dryRunId, null, UUID.randomUUID().toString())
                .onSuccess {
                    Toast.makeText(requireContext(), "已提交 CQ+1", Toast.LENGTH_SHORT).show()
                    item?.let { loadPreflight(it) }
                }
                .onFailure { e ->
                    Toast.makeText(requireContext(), "CQ+1 失败：${e.message}", Toast.LENGTH_LONG).show()
                }
        }
    }

    private fun bind(it: DeliveryItemDto) {
        // 标题：任务展示码 + 标题
        val title = it.source?.taskDisplayCode?.let { "($it) " }.orEmpty() + it.title
        binding.tvTitle.text = title

        // 状态
        val displayStatus = it.displayStatus ?: "-"
        val statusColor = when (displayStatus) {
            "FAILED", "REJECTED" -> R.color.exit_red
            "DELIVERED", "ACCEPTED" -> R.color.teal
            else -> R.color.status_yellow
        }
        binding.tvStatus.backgroundTintList = android.content.res.ColorStateList.valueOf(requireContext().getColor(statusColor))
        binding.tvStatus.text = displayStatus

        // Diff 统计
        binding.tvDiffStats.text = "Diff ${it.filesChanged} 个文件 · +${it.additions} / -${it.deletions}"

        // Review / Delivery 状态
        val review = it.reviewStatus ?: "-"
        val delivery = it.deliveryStatus ?: "-"
        binding.tvReviewDelivery.text = "Review $review · Delivery $delivery"

        // 逐仓库交付进度
        fillRepos(it.repositoryDeliveries.orEmpty())

        // MR 摘要入口：webUrl 外部打开
        val mr = it.mergeRequest
        binding.tvMrEntry.isVisible = mr != null
        binding.tvMrEmpty.isVisible = mr == null
        if (mr != null) {
            val mrText = "🔀 MR #${mr.number ?: "?"} ${mr.title.orEmpty()}"
            binding.tvMrEntry.text = "$mrText ›"
            binding.tvMrEntry.setOnClickListener { openExternalUrl(mr.webUrl) }
        }

        // 关联任务入口
        val taskId = it.source?.taskId
        binding.tvTaskEntry.isVisible = !taskId.isNullOrBlank()
        if (!taskId.isNullOrBlank()) {
            binding.tvTaskEntry.setOnClickListener {
                findNavController().navigate(
                    R.id.action_deliveryItemDetail_to_taskDetail,
                    bundleOf(
                        com.example.qgent.ui.tasks.TaskDetailFragment.ARG_TASK_ID to taskId,
                        com.example.qgent.ui.tasks.TaskDetailFragment.ARG_PROJECT_ID to (mainViewModel.currentProjectId().orEmpty())
                    )
                )
            }
        }
    }

    private fun fillRepos(repos: List<DeliveryRepositoryDeliveryDto>) {
        binding.containerRepos.removeAllViews()
        repos.forEach { rd ->
            val status = rd.deliveryStatus ?: "-"
            val reason = rd.failureReason?.takeIf { it.isNotBlank() }?.let { "（$it）" }.orEmpty()
            val row = com.example.qgent.databinding.ItemRepoProgressRowBinding.inflate(
                layoutInflater, binding.containerRepos, false
            )
            row.tvRepoStatus.text = "• ${rd.repositoryName ?: rd.repositoryId ?: "仓库"}：$status$reason"
            // 单仓库 MR 链接
            val repoMr = rd.mergeRequest
            if (repoMr != null && !repoMr.webUrl.isNullOrBlank()) {
                row.tvMrLink.isVisible = true
                row.tvMrLink.text = "   查看 MR #${repoMr.number ?: "?"} ↗"
                row.tvMrLink.setOnClickListener { openExternalUrl(repoMr.webUrl) }
            }
            binding.containerRepos.addView(row.root)
        }
    }

    private fun openExternalUrl(url: String?) {
        if (url.isNullOrBlank()) {
            Toast.makeText(requireContext(), "暂无 MR 链接", Toast.LENGTH_SHORT).show()
            return
        }
        runCatching {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        }.onFailure {
            Toast.makeText(requireContext(), "无法打开链接", Toast.LENGTH_SHORT).show()
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        const val ARG_ITEM_JSON = "itemJson"

        /** 序列化 DeliveryItemDto 供跳转传参 */
        fun toJson(item: DeliveryItemDto): String = Gson().toJson(item)
    }
}
