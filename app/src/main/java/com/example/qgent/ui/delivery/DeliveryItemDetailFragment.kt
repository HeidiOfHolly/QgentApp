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
import com.example.qgent.data.repository.GitHubRepository
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
    private val githubRepository: GitHubRepository
        get() = (requireActivity().application as QgentApp).container.githubRepository

    private val item: DeliveryItemDto? by lazy {
        arguments?.getString(ARG_ITEM_JSON)?.let { Gson().fromJson(it, DeliveryItemDto::class.java) }
    }

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

    /**
     * MR_FIRST 预检流程：查 preflight（Dry Run + CQ+1 状态）。
     * - dryRun PASSED 且 cqPlusOne PENDING → 显示「CQ+1」；通过后服务端自动创建 MR。
     * - cqPlusOne APPROVED → 显示「申请合并请求」（DIFF_FIRST 手动创建 / MR_FIRST 补偿）。
     */
    private fun loadPreflight(itemDto: DeliveryItemDto) {
        val projectId = mainViewModel.currentProjectId() ?: return
        val taskId = itemDto.source?.taskId ?: return
        val repoIds = itemDto.repositoryDeliveries?.mapNotNull { d -> d.repositoryId }.orEmpty()
        if (repoIds.isEmpty()) return
        viewLifecycleOwner.lifecycleScope.launch {
            val repo = repoIds.firstOrNull()
            val targetBranch = githubRepository.getProjectRepositories(projectId)
                .getOrNull()?.firstOrNull { r -> r.id == repo }?.defaultBranch
            if (repo == null) return@launch
            val preflight = taskRepository.getPreflight(projectId, taskId, repo, targetBranch).getOrNull()
            if (preflight == null) {
                binding.tvPreflightStatus.isVisible = true
                binding.tvPreflightStatus.text = "暂无预检信息"
                return@launch
            }
            val dryRunStatus = preflight.dryRun?.status
            val cqStatus = preflight.cqPlusOne?.status
            when {
                // Dry Run 通过、CQ+1 待审批 → CQ+1 按钮
                dryRunStatus == "PASSED" && cqStatus == "PENDING" -> {
                    binding.btnCqApprove.isVisible = true
                    binding.tvPreflightStatus.isVisible = true
                    binding.tvPreflightStatus.text = "DryRun 通过，等待 CQ+1"
                    binding.btnCqApprove.setOnClickListener { doCqApprove(projectId, preflight.dryRun?.id) }
                }
                // CQ+1 已通过 → 申请合并请求（自动创建 MR 失败时人工补偿 / DIFF_FIRST 手动）
                cqStatus == "APPROVED" -> {
                    binding.btnCreateMr.isVisible = true
                    binding.tvPreflightStatus.isVisible = true
                    binding.tvPreflightStatus.text = "CQ+1 已通过，可申请合并请求"
                    binding.btnCreateMr.setOnClickListener {
                        doCreateMr(projectId, taskId, repo, targetBranch ?: "main", itemDto.title)
                    }
                }
                else -> {
                    val msg = when (dryRunStatus) {
                        "RUNNING" -> "DryRun 运行中…"
                        "FAILED" -> "DryRun 未通过"
                        null -> "暂无 DryRun"
                        else -> "预检状态：$dryRunStatus"
                    }
                    binding.tvPreflightStatus.isVisible = true
                    binding.tvPreflightStatus.text = msg
                }
            }
        }
    }

    /** CQ+1：对 Dry Run 提交审批，通过后服务端自动创建 MR（§27.10） */
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

    /** 申请合并请求：创建 MR（DIFF_FIRST 手动 / MR_FIRST 自动创建失败补偿） */
    private fun doCreateMr(projectId: String, taskId: String, repositoryId: String, targetBranch: String, title: String) {
        viewLifecycleOwner.lifecycleScope.launch {
            taskRepository.createMergeRequest(
                projectId, taskId, repositoryId, targetBranch, title, UUID.randomUUID().toString()
            ).onSuccess {
                Toast.makeText(requireContext(), "已申请合并请求", Toast.LENGTH_SHORT).show()
                item?.let { loadPreflight(it) }
            }.onFailure { e ->
                Toast.makeText(requireContext(), "申请失败：${e.message}", Toast.LENGTH_LONG).show()
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
            val row = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, dp(8), 0, dp(8))
            }
            row.addView(TextView(requireContext()).apply {
                text = "• ${rd.repositoryName ?: rd.repositoryId ?: "仓库"}：$status$reason"
                textSize = 14f
                setTextColor(requireContext().getColor(R.color.text_primary))
            })
            // 单仓库 MR 链接
            rd.mergeRequest?.let { repoMr ->
                if (!repoMr.webUrl.isNullOrBlank()) {
                    row.addView(TextView(requireContext()).apply {
                        text = "   查看 MR #${repoMr.number ?: "?"} ↗"
                        textSize = 13f
                        setTextColor(requireContext().getColor(R.color.primary))
                        setOnClickListener { openExternalUrl(repoMr.webUrl) }
                    })
                }
            }
            binding.containerRepos.addView(row)
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
