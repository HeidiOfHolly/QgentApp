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
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.launch

/** MR 详情页：MR 基础信息 + diff 完整代码块（§13 + §12.3） */
class MergeRequestDetailFragment : Fragment() {

    private var _binding: FragmentMrDetailBinding? = null
    private val binding get() = _binding!!

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
        loadDetail()
    }

    private fun loadDetail() {
        if (projectId.isEmpty() || mergeRequestId.isEmpty()) return
        binding.loading.isVisible = true
        viewLifecycleOwner.lifecycleScope.launch {
            taskRepository.getMergeRequestDetail(projectId, mergeRequestId)
                .onSuccess { detail -> bindDetail(detail) }
                .onFailure { e ->
                    binding.loading.isVisible = false
                    Toast.makeText(requireContext(), e.message ?: "加载合并请求详情失败", Toast.LENGTH_SHORT).show()
                }
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
        val scroll = ScrollView(requireContext())
        val container = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }
        scroll.addView(container, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(file.fileName.substringAfterLast('/'))
            .setView(scroll)
            .setPositiveButton(R.string.close, null)
            .show()
        if (file.lines.isEmpty()) {
            container.addView(TextView(requireContext()).apply {
                text = "（该文件无行内容）"
                textSize = 12f
                setPadding(dp(10), dp(4), dp(10), dp(4))
            })
            return
        }
        file.lines.forEach { line ->
            container.addView(TextView(requireContext()).apply {
                val sign = when (line.type) {
                    DiffLineType.ADD -> "+"
                    DiffLineType.DELETE -> "-"
                    else -> " "
                }
                text = "$sign ${line.text}"
                setTypeface(android.graphics.Typeface.MONOSPACE)
                setPadding(dp(10), dp(2), dp(10), dp(2))
                textSize = 12f
                setBackgroundColor(requireContext().getColor(
                    when (line.type) {
                        DiffLineType.ADD -> R.color.diff_add_bg
                        DiffLineType.DELETE -> R.color.diff_del_bg
                        else -> R.color.white
                    }
                ))
            })
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
