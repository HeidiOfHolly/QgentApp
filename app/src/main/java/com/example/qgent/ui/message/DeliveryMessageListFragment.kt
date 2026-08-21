package com.example.qgent.ui.message

import android.os.Bundle
import android.view.View
import androidx.fragment.app.activityViewModels
import com.example.qgent.QgentApp
import com.example.qgent.viewmodel.MainViewModel

/**
 * 交付中心管理员铃铛入口的消息列表页：仅接收当前项目下的 MR 申请审批通知（MR_PENDING）。
 * 其他消息类型（任务类 / 邀请 / @我）不进此列表，由任务铃铛（TaskMessageListFragment）与抽屉铃铛（MessageListFragment）分流。
 */
class DeliveryMessageListFragment : BaseMessageListFragment() {

    private val mainViewModel: MainViewModel by activityViewModels {
        (requireActivity().application as QgentApp).container.mainViewModelFactory
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        refreshForCurrentContext()
    }

    override fun onResume() {
        super.onResume()
        // 从抽屉切换团队/项目返回时，重新按当前项目过滤并刷新列表
        refreshForCurrentContext()
    }

    /** 按当前项目更新过滤条件，并重新加载列表 */
    private fun refreshForCurrentContext() {
        val projectId = mainViewModel.currentProjectId()
        notificationsFilter = {
            it.kind == "MR_PENDING" &&
                projectId != null && it.projectId == projectId
        }
        reloadNotifications()
    }
}
