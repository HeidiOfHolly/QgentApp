package com.example.qgent.ui.message

import com.example.qgent.data.model.NotificationDto

/**
 * 抽屉铃铛入口的消息列表页：仅展示“被邀请加入团队”的 INVITED 通知，
 * 其余任务/审批类通知归 TaskMessageListFragment。
 */
class MessageListFragment : BaseMessageListFragment() {

    override val notificationsFilter: (NotificationDto) -> Boolean = { it.kind == "INVITED" }
}
