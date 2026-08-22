package com.example.qgent.ui.common

import android.content.Context
import android.os.Bundle
import androidx.fragment.app.FragmentActivity
import androidx.navigation.Navigation
import com.example.qgent.R
import com.example.qgent.data.model.ApiException
import com.example.qgent.ui.diffreview.DiffReviewRules
import com.example.qgent.ui.tasks.MergeRequestDetailFragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * 分支存在未合并 MR 的 409 引导（§27.3/§27.4 Workspace 续作限制）：
 * 创建/续作 Task 或 Diff 交付被阻断时，弹窗提示并引导用户查看 MR（列表或详情），
 * 而非当作普通失败 toast / DELIVERY_FAILED。
 * 接收 Context（FragmentActivity 派生，如 requireContext()），从 NavHostFragment 解析 NavController。
 */
object OpenMrGuidance {

    /** 弹「存在未合并的合并请求」对话框；可带 mergeRequestId 直接跳 MR 详情，否则跳 MR 列表 */
    fun show(context: Context, projectId: String, e: ApiException) {
        val activity = context as? FragmentActivity ?: return
        val mrId = DiffReviewRules.openMrMergeRequestId(e)
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.open_mr_blocked_title)
            .setMessage(e.message)
            .setPositiveButton(R.string.open_mr_blocked_view) { _, _ ->
                navigateToMr(activity, projectId, mrId)
            }
            .setNegativeButton(R.string.close, null)
            .show()
    }

    /** 有 mergeRequestId 跳 MR 详情，否则跳 MR 列表（按 destination id 直接导航，无跨图 action） */
    private fun navigateToMr(activity: FragmentActivity, projectId: String, mergeRequestId: String?) {
        val nav = Navigation.findNavController(activity, R.id.navHostFragment)
        if (!mergeRequestId.isNullOrBlank()) {
            nav.navigate(
                R.id.mergeRequestDetailFragment,
                Bundle().apply {
                    putString(MergeRequestDetailFragment.ARG_MR_ID, mergeRequestId)
                    putString(MergeRequestDetailFragment.ARG_PROJECT_ID, projectId)
                }
            )
        } else {
            nav.navigate(R.id.mergeRequestListFragment)
        }
    }
}
