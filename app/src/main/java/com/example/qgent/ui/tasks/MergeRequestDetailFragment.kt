package com.example.qgent.ui.tasks

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.example.qgent.QgentApp
import com.example.qgent.R
import com.example.qgent.data.model.MergeRequestDetailDto
import com.example.qgent.data.model.toDiffFile
import com.example.qgent.data.repository.DiffRepository
import com.example.qgent.data.repository.GitHubRepository
import com.example.qgent.data.repository.TaskRepository
import com.example.qgent.databinding.FragmentMrDetailBinding
import com.example.qgent.databinding.ItemMrDiffFileBinding
import com.example.qgent.model.DiffFile
import com.example.qgent.model.DiffLineType
import com.example.qgent.ui.personal.fillLinearLayout
import com.example.qgent.viewmodel.MainViewModel
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.launch
import java.util.UUID

/** MR 详情页：MR 基础信息 + 交付流程（DryRun/CQ+1/合并/同步）+ diff 完整代码块（§13 + §12.3） */
class MergeRequestDetailFragment : Fragment() {

    private var _binding: FragmentMrDetailBinding? = null
    private val binding get() = _binding!!

    private val mainViewModel: MainViewModel by activityViewModels {
        (requireActivity().application as QgentApp).container.mainViewModelFactory
    }
    private val taskRepository: TaskRepository
        get() = (requireActivity().application as QgentApp).container.taskRepository
    private val githubRepository: GitHubRepository
        get() = (requireActivity().application as QgentApp).container.githubRepository
    private val diffRepository: DiffRepository
        get() = (requireActivity().application as QgentApp).container.diffRepository

    private val mergeRequestId: String by lazy { arguments?.getString(ARG_MR_ID).orEmpty() }
    private val projectId: String by lazy { arguments?.getString(ARG_PROJECT_ID).orEmpty() }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentMrDetailBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.ivBack.setOnClickListener { findNavController().navigateUp() }
        binding.btnCqApprove.setOnClickListener { cqApprove() }
        binding.btnCqReject.setOnClickListener { showCqRejectDialog() }
        binding.btnMerge.setOnClickListener { confirmMerge() }
        binding.btnSync.setOnClickListener { syncStatus() }
        loadDetail()
    }

    private fun loadDetail() {
        if (projectId.isEmpty() || mergeRequestId.isEmpty()) return
        binding.loading.isVisible = true
        viewLifecycleOwner.lifecycleScope.launch {
            taskRepository.getMergeRequestDetail(projectId, mergeRequestId)
                .onSuccess { detail ->
                    bindDetail(detail)
                    loadFlow(projectId, mergeRequestId, detail.status)
                }
                .onFailure { e ->
                    binding.loading.isVisible = false
                    Toast.makeText(requireContext(), e.message ?: "加载合并请求详情失败", Toast.LENGTH_SHORT).show()
                }
        }
    }

    /** 交付流程：门禁检查 + CQ 审查 + 操作按钮（按状态/权限显示）；不阻塞基础详情 loading */
    private fun loadFlow(projectId: String, mrId: String, mrStatus: String) {
        val open = mrStatus == "OPEN"
        viewLifecycleOwner.lifecycleScope.launch {
            // 门禁检查（10s 超时：接口未就绪/网络差时快速显示空态，不让详情页一直转圈）
            val checks = runCatching {
                kotlinx.coroutines.withTimeout(10_000) { taskRepository.getMergeRequestChecks(projectId, mrId).getOrThrow() }
            }.getOrNull().orEmpty()
            binding.tvFlowChecks.text = if (checks.isEmpty()) {
                "暂无门禁检查"
            } else {
                checks.joinToString(" · ") { c ->
                    val label = when (c.type) {
                        "TESTSET" -> "测试集"
                        "AI_REVIEW" -> "AI 审查"
                        "DRY_RUN" -> "DryRun"
                        "CQ_PLUS_ONE" -> "CQ+1"
                        else -> c.type
                    }
                    val mark = when (c.status) {
                        "PASSED" -> "✓"
                        "FAILED" -> "✗"
                        else -> "…"
                    }
                    "$label $mark"
                }
            }
            // CQ 审查摘要（10s 超时）
            val reviews = runCatching {
                kotlinx.coroutines.withTimeout(10_000) { taskRepository.getMergeRequestReviews(projectId, mrId).getOrThrow() }
            }.getOrNull().orEmpty()
            binding.tvFlowReviews.text = if (reviews.isEmpty()) {
                "暂无 CQ 审批"
            } else {
                reviews.joinToString(" · ") { r ->
                    "${r.reviewer?.displayName ?: "成员"} ${if (r.cqPlusOne == true || r.decision == "APPROVED") "✓" else "✗"}"
                }
            }
            // 操作按钮：OPEN 时显示 CQ+1 / 拒绝 / 同步；合并仅 Project Admin
            binding.btnCqApprove.isVisible = open
            binding.btnCqReject.isVisible = open
            binding.btnSync.isVisible = open
            if (open) {
                binding.btnMerge.isVisible = mainViewModel.isProjectAdmin(projectId)
            } else {
                binding.btnMerge.isVisible = false
            }
        }
    }

    /** CQ+1 */
    private fun cqApprove() {
        if (projectId.isEmpty() || mergeRequestId.isEmpty()) return
        viewLifecycleOwner.lifecycleScope.launch {
            taskRepository.cqApprove(projectId, mergeRequestId, null, UUID.randomUUID().toString())
                .onSuccess { Toast.makeText(requireContext(), "已提交 CQ+1", Toast.LENGTH_SHORT).show() }
                .onFailure { e -> Toast.makeText(requireContext(), "CQ+1 失败：${e.message}", Toast.LENGTH_LONG).show() }
            loadDetail()
        }
    }

    /** 拒绝 CQ（必填原因） */
    private fun showCqRejectDialog() {
        val input = layoutInflater.inflate(R.layout.dialog_cq_reject, null) as android.widget.EditText
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("拒绝 CQ")
            .setView(input)
            .setNegativeButton("取消", null)
            .setPositiveButton("确认拒绝") { _, _ ->
                val reason = input.text?.toString()?.trim()
                if (reason.isNullOrEmpty()) {
                    Toast.makeText(requireContext(), "请填写修改意见", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                viewLifecycleOwner.lifecycleScope.launch {
                    taskRepository.cqReject(projectId, mergeRequestId, reason, UUID.randomUUID().toString())
                        .onSuccess { Toast.makeText(requireContext(), "已拒绝 CQ", Toast.LENGTH_SHORT).show() }
                        .onFailure { e -> Toast.makeText(requireContext(), "拒绝失败：${e.message}", Toast.LENGTH_LONG).show() }
                    loadDetail()
                }
            }
            .show()
    }

    /** Project Admin 合并 */
    private fun confirmMerge() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("确认合并")
            .setMessage("通过质量门禁后将合并该 MR，GitHub 返回 merged=true 后状态更新为已合并。确认执行？")
            .setNegativeButton("取消", null)
            .setPositiveButton("合并") { _, _ ->
                viewLifecycleOwner.lifecycleScope.launch {
                    taskRepository.mergeRequest(projectId, mergeRequestId, UUID.randomUUID().toString())
                        .onSuccess { Toast.makeText(requireContext(), "已发起合并", Toast.LENGTH_SHORT).show() }
                        .onFailure { e -> Toast.makeText(requireContext(), "合并失败：${e.message}", Toast.LENGTH_LONG).show() }
                    loadDetail()
                }
            }
            .show()
    }

    /** 从 GitHub 同步 MR 状态 */
    private fun syncStatus() {
        viewLifecycleOwner.lifecycleScope.launch {
            taskRepository.syncMergeRequest(projectId, mergeRequestId, UUID.randomUUID().toString())
                .onSuccess { Toast.makeText(requireContext(), "已触发同步", Toast.LENGTH_SHORT).show() }
                .onFailure { e -> Toast.makeText(requireContext(), "同步失败：${e.message}", Toast.LENGTH_LONG).show() }
            loadDetail()
        }
    }

    private fun bindDetail(detail: MergeRequestDetailDto) {
        binding.tvMrTitle.text = "#${detail.number} ${detail.title.orEmpty()}"
        binding.tvMrStatus.text = statusLabel(detail.status)
        binding.tvMrBranches.text = "${detail.sourceBranch} → ${detail.targetBranch}"
        // 仓库名反查
        viewLifecycleOwner.lifecycleScope.launch {
            githubRepository.getProjectRepositories(projectId)
                .onSuccess { repos ->
                    binding.tvMrRepo.text = repos.firstOrNull { it.id == detail.repositoryId }?.displayName
                        ?: detail.repositoryId
                }
        }
        // 加载 diff
        val diffId = detail.diffId
        if (diffId.isNullOrEmpty()) {
            binding.tvDiffEmpty.isVisible = true
            binding.loading.isVisible = false
            return
        }
        loadDiffFiles(diffId)
    }

    /** 加载 diff 文件列表（走 diffRepository，兼容后端 hunks / lines 两种返回形态），
     *  仅展示文件名 + 增删统计；点击文件弹出该文件完整 diff（与聊天 DIFF 卡片查看方式一致） */
    private fun loadDiffFiles(diffId: String) {
        viewLifecycleOwner.lifecycleScope.launch {
            diffRepository.getDiffFiles(projectId, diffId)
                .onSuccess { files ->
                    binding.loading.isVisible = false
                    binding.tvDiffEmpty.isVisible = files.isEmpty()
                    fillLinearLayout(binding.containerDiffFiles, files.map { it.toDiffFile() }, R.layout.item_mr_diff_file) { view, file ->
                        bindDiffFile(view, file)
                    }
                }
                .onFailure { e ->
                    binding.loading.isVisible = false
                    binding.tvDiffEmpty.isVisible = true
                    Toast.makeText(requireContext(), "加载代码变更失败：${e.message}", Toast.LENGTH_SHORT).show()
                }
        }
    }

    private fun bindDiffFile(view: View, file: DiffFile) {
        val item = ItemMrDiffFileBinding.bind(view)
        item.tvDiffFileName.text = file.fileName
        item.tvDiffStats.text = "+${file.additions} -${file.deletions}"
        // 点击文件整卡，弹窗查看该文件完整 diff
        item.root.setOnClickListener { showDiffFileDialog(file) }
    }

    /** 弹窗展示单个文件 diff：ScrollView 内文件头（basename + 增删）+ 代码行（+ 绿底 / - 红底、monospace） */
    private fun showDiffFileDialog(file: DiffFile) {
        val binding = com.example.qgent.databinding.DialogDiffFileBinding.inflate(layoutInflater)
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(file.fileName.substringAfterLast('/'))
            .setView(binding.root)
            .setPositiveButton(R.string.close, null)
            .show()
        if (file.lines.isEmpty()) {
            binding.container.addView(TextView(requireContext()).apply {
                text = "（该文件无行内容）"
                textSize = 12f
                setPadding(dp(10), dp(4), dp(10), dp(4))
            })
            return
        }
        file.lines.forEach { line ->
            val row = com.example.qgent.databinding.ItemDiffLineBinding.inflate(layoutInflater, binding.container, false)
            row.tvSign.text = when (line.type) {
                DiffLineType.ADD -> "+"
                DiffLineType.DELETE -> "-"
                else -> " "
            }
            row.tvSign.setTextColor(requireContext().getColor(when (line.type) {
                DiffLineType.ADD -> R.color.diff_add_fg
                DiffLineType.DELETE -> R.color.diff_del_fg
                else -> R.color.diff_line_no
            }))
            row.tvCode.text = line.text
            row.root.setBackgroundColor(requireContext().getColor(when (line.type) {
                DiffLineType.ADD -> R.color.diff_add_bg
                DiffLineType.DELETE -> R.color.diff_del_bg
                else -> R.color.white
            }))
            binding.container.addView(row.root)
        }
    }

    /** dp 转 px（弹窗内代码行布局用） */
    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    private fun statusLabel(status: String): String = when (status) {
        "OPEN" -> "进行中"
        "MERGED" -> "已合并"
        "CLOSED" -> "已关闭"
        else -> status
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        const val ARG_MR_ID = "mergeRequestId"
        const val ARG_PROJECT_ID = "projectId"
    }
}
