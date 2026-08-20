package com.example.qgent

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.util.Log
import android.widget.Toast
import com.example.qgent.data.DndStore
import com.example.qgent.data.SessionExpiryNotifier
import com.example.qgent.data.SessionStore
import com.example.qgent.data.ws.RealtimeFrame
import com.example.qgent.di.AppContainer
import com.example.qgent.ui.auth.LoginActivity
import com.example.qgent.ui.notify.NotificationHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import java.lang.ref.WeakReference

class QgentApp : Application() {

    /**
     * AppContainer（后台装配，见 [containerReady]）。
     * 未就绪时同步访问会抛异常——所有入口 Activity 必须经 [containerReady] 等待后再访问。
     */
    val container: AppContainer
        get() = containerDeferred.getCompleted()

    private val containerDeferred = CompletableDeferred<AppContainer>()
    private val containerLock = Any()
    private var containerAssemblyStarted = false

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /** 应用级协程作用域：不随 Activity/Fragment 生命周期取消，用于必须完成的网络请求（如通知已读） */
    val applicationScope: CoroutineScope get() = appScope

    /** 当前处于前台（resumed）的 Activity，用于弹 Toast 的上下文 */
    private var resumedActivity: WeakReference<Activity>? = null

    /** 前台 Activity 计数（onActivityStarted/Stopped 维护），决定后台是否弹通知 */
    private var startedActivities = 0

    /**
     * 等待 AppContainer 装配完成（只装配一次，多调用方共享同一实例）。
     * 装配在后台线程执行（Room 建库 / Retrofit / OkHttp / WS 客户端及全部依赖类的 JIT 校验），
     * 避免慢设备（模拟器等）主线程被占满导致启动 ANR「已停止运行」/ 长时间假死；
     * 等待期间导航图正常显示 SplashFragment，主线程保持响应。
     */
    suspend fun containerReady(): AppContainer {
        startContainerAssembly()
        return containerDeferred.await()
    }

    private fun startContainerAssembly() {
        synchronized(containerLock) {
            if (containerAssemblyStarted) return
            containerAssemblyStarted = true
            CoroutineScope(Dispatchers.Default + SupervisorJob()).launch {
                try {
                    val created = AppContainer(applicationContext)
                    // WS 常驻连接（Application 级）：进程活着就一直连着，登录后 token 生效自动连上
                    created.realtimeClient.start()
                    containerDeferred.complete(created)
                } catch (e: Throwable) {
                    containerDeferred.completeExceptionally(e)
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        SessionStore.init(this)
        DndStore.init(this)
        NotificationHelper.ensureChannel(this)

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

        // AppContainer 后台装配；依赖它的常驻逻辑（WS 通知广播 / 会话过期登出）就绪后启动
        appScope.launch {
            val container = containerReady()
            appScope.launch {
                container.realtimeClient.events.collect { frame ->
                    if (frame.type == "message.created" && !NotificationHelper.isAppForeground) {
                        notifyMessageCreated(frame)
                    }
                }
            }
            appScope.launch {
                SessionExpiryNotifier.expired.filter { it }.collect {
                    forceLogout()
                }
            }
        }
    }

    /** 后台收到 message.created：拉该群最新消息弹通知（前台界面已刷新，不弹） */
    private suspend fun notifyMessageCreated(frame: RealtimeFrame) {
        val projectId = frame.projectId ?: return
        val groupId = frame.groupId ?: return
        // 群免打扰（本地）：该群后台不弹系统通知，未读红点照常累计
        if (DndStore.isMuted(groupId)) return
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
        // 通知右侧图标 = 群聊头像（成员拼图）；生成失败/无头像时 NotificationHelper 兜底品牌 Logo
        val largeIcon = buildGroupAvatarBitmap(projectId, groupId)
        NotificationHelper.showChatNotification(this, projectId, groupId, groupName, body, mentioned, unread, largeIcon)
    }

    /** 群聊头像拼图（通知大图标）：拉群成员头像（前 9 个）按 1/2x2/3x3 网格拼成白底 Bitmap，与 GroupAvatarView 一致 */
    private suspend fun buildGroupAvatarBitmap(projectId: String, groupId: String): android.graphics.Bitmap? =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val members = container.chatRepository.getMembers(projectId, groupId).getOrNull().orEmpty()
            val urls = members.mapNotNull { it.avatar?.takeIf { u -> u.isNotBlank() } }.take(9)
            if (urls.isEmpty()) return@withContext null
            val size = 192
            val grid = when (urls.size) { 1 -> 1; in 2..4 -> 2; else -> 3 }
            val gap = 2
            val cell = (size - gap * (grid - 1)) / grid
            val bmp = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888)
            val canvas = android.graphics.Canvas(bmp)
            canvas.drawColor(android.graphics.Color.WHITE)
            val token = SessionStore.accessToken()
            val headers = com.bumptech.glide.load.model.LazyHeaders.Builder().apply {
                if (!token.isNullOrEmpty()) addHeader("Authorization", "Bearer $token")
            }.build()
            urls.forEachIndexed { index, url ->
                val row = index / grid
                val col = index % grid
                val left = col * (cell + gap)
                val top = row * (cell + gap)
                val avatar = runCatching {
                    com.bumptech.glide.Glide.with(this@QgentApp)
                        .asBitmap()
                        .load(com.bumptech.glide.load.model.GlideUrl(com.example.qgent.data.api.RetrofitClient.resolveMediaUrl(url), headers))
                        .submit(cell, cell)
                        .get(5, java.util.concurrent.TimeUnit.SECONDS)
                }.getOrNull()
                if (avatar != null) {
                    canvas.drawBitmap(avatar, null, android.graphics.Rect(left, top, left + cell, top + cell), null)
                }
            }
            bmp
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
