package com.example.qgent.ui.tasks

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.example.qgent.QgentApp
import com.example.qgent.R
import com.example.qgent.data.model.DiffLineResponseDto
import com.example.qgent.data.model.DiffFileResponseDto
import com.example.qgent.data.model.MergeRequestDetailDto
import com.example.qgent.data.repository.GitHubRepository
import com.example.qgent.data.repository.TaskRepository
import com.example.qgent.databinding.FragmentMrDetailBinding
import com.example.qgent.databinding.ItemDiffLineBinding
import com.example.qgent.databinding.ItemMrDiffFileBinding
import com.example.qgent.model.DiffLineType
import com.example.qgent.ui.personal.fillLinearLayout
import kotlinx.coroutines.launch

/** MR 详情页：MR 基础信息 + diff 完整代码块（§13 + §12.3） */
class MergeRequestDetailFragment : Fragment() {

    private var _binding: FragmentMrDetailBinding? = null
    private val binding get() = _binding!!

    private val taskRepository: TaskRepository
        get() = (requireActivity().application as QgentApp).container.taskRepository
    private val githubRepository: GitHubRepository
        get() = (requireActivity().application as QgentApp).container.githubRepository

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

    private fun loadDiffFiles(diffId: String) {
        viewLifecycleOwner.lifecycleScope.launch {
            taskRepository.getDiffFiles(projectId, diffId)
                .onSuccess { files ->
                    binding.loading.isVisible = false
                    binding.tvDiffEmpty.isVisible = files.isEmpty()
                    fillLinearLayout(binding.containerDiffFiles, files, R.layout.item_mr_diff_file) { view, file ->
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

    private fun bindDiffFile(view: View, file: DiffFileResponseDto) {
        val item = ItemMrDiffFileBinding.bind(view)
        item.tvDiffFileName.text = file.path
        item.tvDiffStats.text = "+${file.additions} -${file.deletions}"
        val lines = file.lines.orEmpty()
        if (lines.isEmpty()) {
            item.containerDiffLines.isVisible = false
            return
        }
        fillLinearLayout(item.containerDiffLines, lines, R.layout.item_diff_line) { lineView, line ->
            bindDiffLine(ItemDiffLineBinding.bind(lineView), line)
        }
    }

    private fun bindDiffLine(item: ItemDiffLineBinding, line: DiffLineResponseDto) {
        val context = binding.root.context
        val type = when (line.type) {
            "ADD" -> DiffLineType.ADD
            "DELETE" -> DiffLineType.DELETE
            else -> DiffLineType.CONTEXT
        }
        item.tvSign.text = when (type) {
            DiffLineType.ADD -> "+"
            DiffLineType.DELETE -> "-"
            DiffLineType.CONTEXT -> " "
        }
        item.tvSign.setTextColor(androidx.core.content.ContextCompat.getColor(context, when (type) {
            DiffLineType.ADD -> R.color.diff_add_fg
            DiffLineType.DELETE -> R.color.diff_del_fg
            DiffLineType.CONTEXT -> R.color.diff_line_no
        }))
        item.tvCode.text = line.text
        item.root.setBackgroundColor(androidx.core.content.ContextCompat.getColor(context, when (type) {
            DiffLineType.ADD -> R.color.diff_add_bg
            DiffLineType.DELETE -> R.color.diff_del_bg
            DiffLineType.CONTEXT -> android.R.color.white
        }))
    }

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
