package com.example.qgent.ui.delivery

import android.widget.EditText
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.example.qgent.data.model.DeliveryItemDto
import com.example.qgent.data.repository.DiffRepository
import com.example.qgent.ui.common.OpenMrGuidance
import com.example.qgent.ui.diffreview.DiffReviewRules
import com.example.qgent.viewmodel.MainViewModel
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * 交付物卡片操作（查看 Diff / 确认 / 拒绝 / 重试）的共享实现，交付中心与交付物列表页共用。
 * 依赖 Fragment 生命周期、diffRepo 与 MainViewModel（取当前项目 id）；
 * 操作成功后调用 onChanged 刷新所在页列表。
 */
class DeliveryItemActions(
    private val fragment: Fragment,
    private val mainViewModel: MainViewModel,
    private val diffRepo: DiffRepository,
    private val onChanged: () -> Unit
) : DeliveryItemAction {

    override fun onViewDiff(item: DeliveryItemDto) = showDiffFiles(item)
    override fun onConfirm(item: DeliveryItemDto) = confirmDelivery(item)
    override fun onReject(item: DeliveryItemDto) = showRejectDialog(item)
    override fun onRetry(item: DeliveryItemDto) = retryDelivery(item)

    /** 查看 Diff：拉取文件列表弹窗（文件名 + 增删统计），顶部带「通过 Diff / 拒绝」审核按钮 */
    private fun showDiffFiles(item: DeliveryItemDto) {
        val projectId = mainViewModel.currentProjectId() ?: return
        val diffId = item.diffId ?: return
        fragment.viewLifecycleOwner.lifecycleScope.launch {
            val files = diffRepo.getDiffFiles(projectId, diffId).getOrNull().orEmpty()
            val sb = StringBuilder()
            files.forEach { f ->
                sb.append("📄 ").append(f.fileName ?: f.path)
                    .append("  +${f.additions} -${f.deletions}").append("\n")
            }
            if (sb.isBlank()) sb.append("（该 Diff 无文件内容）")
            val builder = MaterialAlertDialogBuilder(fragment.requireContext())
                .setTitle("实时/交付 Diff")
                .setMessage(sb.toString())
            val taskId = item.source?.taskId
            // 通过 Diff：确认交付（MR_FIRST 已自动授权；DIFF_FIRST 手动确认后进入交付）。
            // 能力位缺省按 true 兜底（与 TaskDetailFragment 一致，防旧后端/字段缺失误隐藏审核入口）。
            val caps = item.capabilities
            if (taskId != null && (caps?.canApprove ?: true)) {
                builder.setPositiveButton("通过 Diff") { _, _ ->
                    confirmDelivery(item)
                }
            }
            if (taskId != null && (caps?.canReject ?: true)) {
                builder.setNegativeButton("拒绝") { _, _ ->
                    showRejectDialog(item)
                }
            }
            builder.setNeutralButton("关闭", null)
            builder.show()
        }
    }

    /** 确认交付（MR_FIRST 已自动授权；DIFF_FIRST 手动确认后进入交付） */
    private fun confirmDelivery(item: DeliveryItemDto) {
        val projectId = mainViewModel.currentProjectId() ?: return
        val taskId = item.source?.taskId ?: return
        fragment.viewLifecycleOwner.lifecycleScope.launch {
            diffRepo.confirmDiffReview(projectId, taskId, UUID.randomUUID().toString())
                .onSuccess { fragment.toast("已确认交付") }
                .onFailure { e ->
                    // §27.4：分支存在未合并 MR 时引导查看 MR
                    if (e is com.example.qgent.data.model.ApiException && DiffReviewRules.isOpenMrBlocked(e.code)) {
                        OpenMrGuidance.show(fragment.requireContext(), projectId, e)
                    } else {
                        fragment.toast("确认失败：${e.message}")
                    }
                }
            onChanged()
        }
    }

    /** 拒绝交付（可填原因） */
    private fun showRejectDialog(item: DeliveryItemDto) {
        val input = EditText(fragment.requireContext()).apply { hint = "拒绝原因（可选）" }
        MaterialAlertDialogBuilder(fragment.requireContext())
            .setTitle("拒绝交付")
            .setView(input)
            .setNegativeButton("取消", null)
            .setPositiveButton("确认拒绝") { _, _ ->
                val projectId = mainViewModel.currentProjectId() ?: return@setPositiveButton
                val taskId = item.source?.taskId ?: return@setPositiveButton
                fragment.viewLifecycleOwner.lifecycleScope.launch {
                    diffRepo.rejectDiffReview(
                        projectId, taskId,
                        input.text?.toString()?.trim()?.ifEmpty { null },
                        UUID.randomUUID().toString()
                    ).onSuccess { fragment.toast("已拒绝交付") }
                        .onFailure { e ->
                            if (e is com.example.qgent.data.model.ApiException && DiffReviewRules.isOpenMrBlocked(e.code)) {
                                OpenMrGuidance.show(fragment.requireContext(), projectId, e)
                            } else {
                                fragment.toast("拒绝失败：${e.message}")
                            }
                        }
                    onChanged()
                }
            }
            .show()
    }

    /** 重试交付（部分失败/失败后） */
    private fun retryDelivery(item: DeliveryItemDto) {
        val projectId = mainViewModel.currentProjectId() ?: return
        val taskId = item.source?.taskId ?: return
        fragment.viewLifecycleOwner.lifecycleScope.launch {
            diffRepo.retryDiffDelivery(projectId, taskId, UUID.randomUUID().toString())
                .onSuccess { fragment.toast("已重新发起交付") }
                .onFailure { e ->
                    if (e is com.example.qgent.data.model.ApiException && DiffReviewRules.isOpenMrBlocked(e.code)) {
                        OpenMrGuidance.show(fragment.requireContext(), projectId, e)
                    } else {
                        fragment.toast("重试失败：${e.message}")
                    }
                }
            onChanged()
        }
    }

    private fun Fragment.toast(msg: String) =
        Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
}
