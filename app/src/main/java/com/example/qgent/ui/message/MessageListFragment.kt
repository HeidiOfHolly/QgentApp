package com.example.qgent.ui.message

import android.os.Bundle
import android.view.View


/**
 * 抽屉铃铛入口的消息列表页：个人通知中心 = 「被邀请加入团队」(INVITED) + 「有人@我」(MESSAGE_MENTION)。
 * 其余任务/审批类通知归 TaskMessageListFragment。
 * MESSAGE_MENTION 点击跳转与 @ 消息定位由 BaseMessageListFragment 处理（targetMessageId/fromMention）。
 */
class MessageListFragment : BaseMessageListFragment() {

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        notificationsFilter = { it.kind == "INVITED" || it.kind == "MESSAGE_MENTION" }
        super.onViewCreated(view, savedInstanceState)
    }
}
