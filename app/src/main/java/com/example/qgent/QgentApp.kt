package com.example.qgent

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.widget.Toast
import com.example.qgent.data.SessionExpiryNotifier
import com.example.qgent.data.SessionStore
import com.example.qgent.di.AppContainer
import com.example.qgent.ui.auth.LoginActivity
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

    /** 当前处于前台（resumed）的 Activity，用于弹 Toast 的上下文 */
    private var resumedActivity: WeakReference<Activity>? = null

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        SessionStore.init(this)

        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) {
                resumedActivity = WeakReference(activity)
            }

            override fun onActivityPaused(activity: Activity) {
                if (resumedActivity?.get() === activity) resumedActivity = null
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: android.os.Bundle?) {}
            override fun onActivityStarted(activity: Activity) {}
            override fun onActivityStopped(activity: Activity) {}
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
