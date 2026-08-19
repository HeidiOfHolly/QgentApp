package com.example.qgent.ui.message

import android.os.Bundle
import android.view.View
import androidx.fragment.app.activityViewModels
import com.example.qgent.QgentApp
import com.example.qgent.R
import com.example.qgent.viewmodel.MainViewModel

/**
 * 任务界面铃铛入口的消息列表页：展示当前项目下的任务类通知（除“被邀请加入团队”外）。
 * 跟随抽屉切换的当前团队/项目：过滤条件按当前项目 id 限定，顶部标题显示「团队名 · 项目名」。
 */
class TaskMessageListFragment : BaseMessageListFragment() {

    private val mainViewModel: MainViewModel by activityViewModels {
        (requireActivity().application as QgentApp).container.mainViewModelFactory
    }

    /** 任务铃铛页跳任务详情的导航动作（TASK_FAILED 通知） */
    override val taskDetailActionRes: Int
        get() = R.id.action_taskMessageList_to_taskDetail

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        refreshForCurrentContext()
    }

    override fun onResume() {
        super.onResume()
        // 从抽屉切换团队/项目返回时，重新按当前项目过滤并刷新标题
        refreshForCurrentContext()
    }

    /** 按当前项目更新过滤条件，并重新加载列表 */
    private fun refreshForCurrentContext() {
        val projectId = mainViewModel.currentProjectId()
        // 任务消息列表不接收「有人@我」通知（MESSAGE_MENTION）：仅保留当前项目的任务类通知（非邀请、非@我）
        notificationsFilter = {
            it.kind != "INVITED" && it.kind != "MESSAGE_MENTION" &&
                projectId != null && it.projectId == projectId
        }
        reloadNotifications()
    }
}
