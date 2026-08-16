package com.example.qgent.ui.message

import com.example.qgent.data.model.NotificationDto

/**
 * 任务界面铃铛入口的消息列表页：展示除“被邀请加入团队”外的其余所有通知
 * （任务完成/失败、需要输入、审批、MR 等；INVITED 归 MessageListFragment）。
 */
class TaskMessageListFragment : BaseMessageListFragment() {

    override val notificationsFilter: (NotificationDto) -> Boolean = { it.kind != "INVITED" }
}
