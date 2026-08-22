package com.example.qgent.ui.tasks

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
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

/** MR 详情页：MR 基础信息 + 交付流程（门禁/合并/同步）+ diff 完整代码块（§13 + §12.3） */
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

    /** 当前 MR 所属仓库的 GitHub 主页 URL（如 https://github.com/owner/repo），用于拼 MR 链接 */
    private var repoGithubUrl: String? = null

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
                    loadFlow(projectId, detail)
                }
                .onFailure { e ->
                    binding.loading.isVisible = false
                    Toast.makeText(requireContext(), e.message ?: "加载合并请求详情失败", Toast.LENGTH_SHORT).show()
                }
        }
    }

    /** 交付流程：CQ+1 通过者 + 操作按钮（按状态/权限显示）；不阻塞基础详情 loading */
    private fun loadFlow(projectId: String, detail: MergeRequestDetailDto) {
        val open = detail.status == "OPEN"
        viewLifecycleOwner.lifecycleScope.launch {
            // CQ+1 通过者：来自预检状态（发起 MR 前的预检 CQ+1，cqReviewerUserId），而非 MR 门禁审查。
            // MR 详情只有 MR_ID，无 taskId；通过 repositoryId + MR number 遍历项目任务预检匹配当前 MR。
            binding.tvFlowReviews.text = "CQ+1 通过者：" + loadCqPasser(projectId, detail)
            // 合并请求 MR 链接卡片（跳 GitHub）：优先 MR 详情 webUrl，兜底仓库 githubUrl + number 拼
            loadMrEntry(detail)
            // 操作按钮：OPEN 时显示 同步；合并仅 Project Admin
            binding.btnSync.isVisible = open
            if (open) {
                binding.btnMerge.isVisible = mainViewModel.isProjectAdmin(projectId)
            } else {
                binding.btnMerge.isVisible = false
            }
            // 交付流程（最慢，含遍历预检）完成后再隐藏 loading，避免 diff 先渲染导致指示器提前消失
            binding.loading.isVisible = false
        }
    }

    /**
     * 查找当前 MR 对应的预检记录：遍历项目全部任务的预检
     * （GET /tasks/{taskId}/merge-request-preflight），用预检 mergeRequest.id 精确匹配当前 MR id
     * （= 导航 ARG_MR_ID / MR 详情 id），repositoryId 作为辅助校验。
     * MR 详情只有 MR_ID 无 taskId，故需遍历；数据源为预检 CQ+1（发起 MR 前的独立成员审批）。
     */
    private suspend fun findMatchingPreflight(projectId: String, detail: MergeRequestDetailDto): com.example.qgent.data.model.MergeRequestPreflightDto? {
        val mrId = detail.id
        val mrRepositoryId = detail.repositoryId
        val tasks = taskRepository.getTasks(projectId).getOrNull().orEmpty()
        for (task in tasks) {
            val preflights = taskRepository.getTaskMergeRequestPreflight(projectId, task.id).getOrNull().orEmpty()
            preflights.firstOrNull {
                it.mergeRequest?.id == mrId || (it.repositoryId == mrRepositoryId && it.mergeRequest?.number == detail.number)
            }?.let { return it }
        }
        return null
    }

    /** CQ+1 通过者姓名：取匹配预检的 cqReviewerUserId 反查成员 displayName；未命中返回「暂无」 */
    private suspend fun loadCqPasser(projectId: String, detail: MergeRequestDetailDto): String {
        val hit = findMatchingPreflight(projectId, detail) ?: return "暂无"
        val passerId = hit.cqReviewerUserId
        if (passerId.isNullOrBlank()) return "暂无"
        return userRepository().getTeamMembers(mainViewModel.currentTeamId().orEmpty())
            .getOrNull().orEmpty().firstOrNull { it.userId == passerId }?.displayName ?: "成员"
    }

    /** 填充「合并请求」MR 链接卡片：优先用 MR 详情返回的 webUrl（对齐交付物详情方式，§13/§21.2）；
     *  后端未返回时用仓库 GitHub URL + MR number 拼（https://github.com/{owner}/{repo}/pull/{number}）。
     *  点击外部打开 GitHub MR。 */
    private fun loadMrEntry(detail: MergeRequestDetailDto) {
        val builtUrl = repoGithubUrl?.let { g -> detail.number.takeIf { it > 0 }?.let { "$g/pull/$it" } }
        val url = detail.webUrl?.takeIf { it.isNotBlank() } ?: builtUrl
        binding.tvMrEntry.isVisible = url != null
        binding.tvMrEmpty.isVisible = url == null
        if (url != null) {
            binding.tvMrEntry.text = "🔀 MR #${detail.number} ${detail.title.orEmpty()} ›"
            binding.tvMrEntry.setOnClickListener {
                runCatching {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                }.onFailure {
                    Toast.makeText(requireContext(), "无法打开链接", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun userRepository(): com.example.qgent.data.repository.UserRepository =
        (requireActivity().application as QgentApp).container.userRepository

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
                    val repo = repos.firstOrNull { it.id == detail.repositoryId }
                    binding.tvMrRepo.text = repo?.displayName ?: detail.repositoryId
                    repoGithubUrl = repo?.githubUrl?.takeIf { it.isNotBlank() }
                }
        }
        // 加载 diff（loading 由 loadFlow 结束时统一隐藏，避免提前消失）
        val diffId = detail.diffId
        if (diffId.isNullOrEmpty()) {
            binding.tvDiffEmpty.isVisible = true
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
                    binding.tvDiffEmpty.isVisible = files.isEmpty()
                    fillLinearLayout(binding.containerDiffFiles, files.map { it.toDiffFile() }, R.layout.item_mr_diff_file) { view, file ->
                        bindDiffFile(view, file)
                    }
                }
                .onFailure { e ->
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

    /** 弹窗展示单个文件 diff：外层垂直滚动（文件头/多行），内层横向滚动（长代码行可左右滑动） */
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
            // 用 item_mr_diff_line（tvCode 不截断、按内容撑宽），配合 HorizontalScrollView 横向滚动
            val row = com.example.qgent.databinding.ItemMrDiffLineBinding.inflate(layoutInflater, binding.codeContainer, false)
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
            binding.codeContainer.addView(row.root)
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
