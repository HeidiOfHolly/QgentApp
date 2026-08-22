package com.example.qgent.ui.delivery

import android.widget.EditText
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
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
    override fun onContinueModify(item: DeliveryItemDto) = continueModify(item)

    /** 交付被拒绝 → 回需求群引用 DIFF 根据拒绝意见继续修改（跳群聊） */
    private fun continueModify(item: DeliveryItemDto) {
        val group = item.requirementGroup
        val groupId = group?.id?.takeIf { it.isNotBlank() }
        if (groupId == null) {
            fragment.toast("缺少需求群信息")
            return
        }
        fragment.findNavController().navigate(
            com.example.qgent.R.id.chatDetailFragment,
            android.os.Bundle().apply {
                putString("groupName", group?.name)
                putString("groupId", groupId)
            }
        )
    }

    /** 查看 Diff：拉取文件列表弹窗（文件名 + 增删统计），顶部带「通过 Diff / 拒绝」审核按钮。
     *  确认/拒绝权限在动作执行前再次校验（见 confirmDelivery / showRejectDialog），
     *  按钮显示以交付物能力位为准（后端已按「任务发起人或 Project Admin」派生）。 */
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
            val caps = item.capabilities
            // diff 已确认（ACCEPTED）：不再显示确认交付/拒绝入口（已确认的不再重复提示）
            val diffConfirmed = item.reviewStatus == "ACCEPTED"
            if (!diffConfirmed && taskId != null && caps?.canApprove == true) {
                builder.setPositiveButton("通过 Diff") { _, _ ->
                    confirmDelivery(item)
                }
            }
            if (!diffConfirmed && taskId != null && caps?.canReject == true) {
                builder.setNegativeButton("拒绝") { _, _ ->
                    showRejectDialog(item)
                }
            }
            builder.setNeutralButton("关闭", null)
            builder.show()
        }
    }

    /** 确认交付（MR_FIRST 已自动授权；DIFF_FIRST 手动确认后进入交付）。
     *  仅任务创建者或项目管理员可操作（客户端自判：拉任务详情取创建者 + 管理员校验）。 */
    private fun confirmDelivery(item: DeliveryItemDto) {
        val projectId = mainViewModel.currentProjectId() ?: return
        val taskId = item.source?.taskId ?: return
        fragment.viewLifecycleOwner.lifecycleScope.launch {
            if (!canOperate(projectId, taskId)) {
                fragment.toast("仅任务创建者或项目管理员可确认交付")
                return@launch
            }
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
                    // 仅任务创建者或项目管理员可拒绝（客户端自判）
                    if (!canOperate(projectId, taskId)) {
                        fragment.toast("仅任务创建者或项目管理员可拒绝交付")
                        return@launch
                    }
                    diffRepo.rejectDiffReview(
                        projectId, taskId,
                        input.text?.toString()?.trim()?.ifEmpty { null },
                        UUID.randomUUID().toString()
                    ).onSuccess { fragment.toast("已拒绝，请回需求群根据拒绝意见继续修改") }
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

    /** 当前用户是否可对某任务确认/拒绝交付：管理员恒可；否则要求是任务创建者（拉任务详情取创建者） */
    private suspend fun canOperate(projectId: String, taskId: String): Boolean {
        val taskRepo = (fragment.requireActivity().application as com.example.qgent.QgentApp).container.taskRepository
        val creatorId = taskRepo.getTaskDetail(projectId, taskId).getOrNull()?.createdByUser?.id
        return DeliveryPermission.canDecide(
            com.example.qgent.data.SessionStore.user()?.id,
            creatorId,
            mainViewModel.isProjectAdmin(projectId)
        )
    }

    private fun Fragment.toast(msg: String) =
        Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
}
