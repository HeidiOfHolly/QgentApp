package com.example.qgent

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.util.Log
import android.widget.Toast
import com.example.qgent.data.SessionExpiryNotifier
import com.example.qgent.data.SessionStore
import com.example.qgent.data.ws.RealtimeFrame
import com.example.qgent.di.AppContainer
import com.example.qgent.ui.auth.LoginActivity
import com.example.qgent.ui.notify.NotificationHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import java.lang.ref.WeakReference

class QgentApp : Application() {

    lateinit var container: AppContainer
        private set

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /** 应用级协程作用域：不随 Activity/Fragment 生命周期取消，用于必须完成的网络请求（如通知已读） */
    val applicationScope: CoroutineScope get() = appScope

    /** 当前处于前台（resumed）的 Activity，用于弹 Toast 的上下文 */
    private var resumedActivity: WeakReference<Activity>? = null

    /** 前台 Activity 计数（onActivityStarted/Stopped 维护），决定后台是否弹通知 */
    private var startedActivities = 0

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        SessionStore.init(this)
        NotificationHelper.ensureChannel(this)

        // WS 常驻连接（Application 级）：进程活着就一直连着，登录后 token 生效自动连上；
        // 后台收到 message.created 事件直接弹通知（事件驱动，不依赖协程轮询，App Standby 冻结不到）
        container.realtimeClient.start()
        appScope.launch {
            container.realtimeClient.events.collect { frame ->
                if (frame.type == "message.created" && !NotificationHelper.isAppForeground) {
                    notifyMessageCreated(frame)
                }
            }
        }

        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) {
                resumedActivity = WeakReference(activity)
            }

            override fun onActivityPaused(activity: Activity) {
                if (resumedActivity?.get() === activity) resumedActivity = null
            }

            override fun onActivityStarted(activity: Activity) {
                startedActivities++
                NotificationHelper.isAppForeground = true
            }

            override fun onActivityStopped(activity: Activity) {
                startedActivities--
                if (startedActivities <= 0) {
                    startedActivities = 0
                    NotificationHelper.isAppForeground = false
                }
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: android.os.Bundle?) {}
            override fun onActivityDestroyed(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: android.os.Bundle) {}
        })

        // 会话过期 → 自动退出登录：清空会话、弹提示、回登录页
        appScope.launch {
            SessionExpiryNotifier.expired.filter { it }.collect {
                forceLogout()
            }
        }
    }

    /** 后台收到 message.created：拉该群最新消息弹通知（前台界面已刷新，不弹） */
    private suspend fun notifyMessageCreated(frame: RealtimeFrame) {
        val projectId = frame.projectId ?: return
        val groupId = frame.groupId ?: return
        val myId = SessionStore.user()?.id
        val repo = container.chatRepository
        // 群名 + 未读数：群列表里找（找不到兜底「群聊」/无未读数）
        val group = repo.getGroups(projectId).getOrNull()?.firstOrNull { it.id == groupId }
        val groupName = group?.title ?: "群聊"
        val unread = group?.unreadCount
        // 最新一条消息摘要（后端新在前，第一条最新）
        val latest = repo.getMessagesPage(projectId, groupId, limit = 5).getOrNull()?.messages?.firstOrNull()
        val body = latest?.let { dto ->
            val name = dto.senderName?.takeIf { it.isNotBlank() } ?: "成员"
            val text = dto.content?.text ?: dto.replyText
                ?: dto.content?.url?.takeIf { it.isNotBlank() }?.let { "[图片]" }
                ?: "[${dto.type}]"
            "$name：$text"
        } ?: "新消息"
        val mentioned = latest?.mentions?.any { it.type == "USER" && it.id == myId } == true
        NotificationHelper.showChatNotification(this, groupId, groupName, body, mentioned, unread)
    }

    private fun forceLogout() {
        if (!SessionStore.isLoggedIn()) return
        SessionStore.clear()
        val context = resumedActivity?.get()?.takeIf { !it.isFinishing }
        val toastContext = context ?: this
        Toast.makeText(toastContext, getString(R.string.session_expired), Toast.LENGTH_SHORT).show()
        // 优先用前台 Activity context 启动：Android 12+ 对后台启动 Activity 有限制，
        // 从 Application context 启动会被静默拦截，导致停留在旧界面（如团队引导页）。
        // 前台 Activity 启动不受该限制，且 NEW_TASK|CLEAR_TASK 会清空整个返回栈。
        val intent = Intent(this, LoginActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        if (context != null) {
            context.startActivity(intent)
        } else {
            startActivity(intent)
        }
    }
}
