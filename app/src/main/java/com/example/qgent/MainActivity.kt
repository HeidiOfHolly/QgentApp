package com.example.qgent

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.os.bundleOf
import androidx.core.view.GravityCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.ViewModelProvider
import androidx.navigation.NavController
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.navOptions
import androidx.navigation.ui.setupWithNavController
import com.google.android.material.badge.BadgeDrawable
import com.example.qgent.data.SessionStore
import com.example.qgent.databinding.ActivityMainBinding
import com.example.qgent.ui.auth.LoginActivity
import com.example.qgent.ui.auth.TeamEntryActivity
import com.example.qgent.ui.personal.PersonalCenterFragment
import com.example.qgent.viewmodel.MainViewModel

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var navController: NavController

    // 与抽屉等 Fragment 共用同一 Activity 级 MainViewModel，避免冷启动重复拉取
    private val mainViewModel: MainViewModel by lazy {
        ViewModelProvider(this, (application as QgentApp).container.mainViewModelFactory)[MainViewModel::class.java]
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 未登录时跳转登录页
        if (!SessionStore.isLoggedIn()) {
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
            return
        }

        enableEdgeToEdge()
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 抽屉宽度 = 屏幕宽度 85%
        val drawerWidth = (resources.displayMetrics.widthPixels * 0.85f).toInt()
        binding.drawerPersonalCenter.layoutParams.width = drawerWidth

        // 个人中心（抽屉内容）注入抽屉容器
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.drawerPersonalCenter, PersonalCenterFragment())
                .commit()
        }

        // 状态栏 / 手势导航栏 insets：内容区顶部避让状态栏，底部导航避让手势条，抽屉同样避让
        ViewCompat.setOnApplyWindowInsetsListener(binding.drawerLayout) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            binding.contentContainer.setPadding(0, bars.top, 0, 0)
            binding.bottomNav.setPadding(0, 0, 0, bars.bottom)
            binding.drawerPersonalCenter.setPadding(0, bars.top, 0, bars.bottom)
            insets
        }

        // NavHostFragment 的视图在 onCreate 时可能尚未创建完成，
        // 延迟到视图创建并挂载后再绑定导航，避免 "does not have a NavController set"。
        binding.root.post {
            navController = (supportFragmentManager
                .findFragmentById(R.id.navHostFragment) as NavHostFragment).navController
            binding.bottomNav.setupWithNavController(navController)

            // 未读任务类通知 → 底部任务 tab 图标右上角红点
            mainViewModel.unreadTaskNotifications.observe(this) { hasUnread ->
                if (hasUnread) {
                    binding.bottomNav.getOrCreateBadge(R.id.tasksFragment).apply {
                        isVisible = true
                        badgeGravity = BadgeDrawable.TOP_END
                    }
                } else {
                    binding.bottomNav.removeBadge(R.id.tasksFragment)
                }
            }

            // 非三 Tab 页面（如群聊详情）隐藏底部导航栏
            navController.addOnDestinationChangedListener { _, destination, _ ->
                val isTabPage = destination.id == R.id.chatListFragment ||
                    destination.id == R.id.tasksFragment ||
                    destination.id == R.id.agentFragment
                binding.bottomNav.visibility = if (isTabPage) View.VISIBLE else View.GONE
            }

            // 仅冷启动路由一次；旋转等配置变更不重复跳转
            if (savedInstanceState == null) {
                if (!handleDeepLink(intent)) {
                    routeInitialDestination()
                }
            }
            // 通知权限引导（Android 13+ 未授权时提示去开启，否则后台广播收不到）
            maybePromptNotificationPermission()
            handleNotificationIntent(intent)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (::navController.isInitialized) {
            handleDeepLink(intent)
            handleNotificationIntent(intent)
        }
    }

    /** 通知权限未授予（Android 13+）→ 弹窗引导去系统设置开启 */
    private fun maybePromptNotificationPermission() {
        if (com.example.qgent.ui.notify.NotificationHelper.hasPermission(this)) return
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("开启通知权限")
            .setMessage("需要通知权限才能在应用外收到群聊消息提醒。请前往系统设置允许通知。")
            .setNegativeButton("以后再说", null)
            .setPositiveButton("去设置") { _, _ ->
                com.example.qgent.ui.notify.NotificationHelper.openSettings(this)
            }
            .show()
    }

    /** 通知点击（extras: groupId/groupName）→ 跳进对应群聊 */
    private fun handleNotificationIntent(intent: Intent?) {
        val groupId = intent?.getStringExtra("groupId") ?: return
        val groupName = intent?.getStringExtra("groupName").orEmpty()
        intent.removeExtra("groupId")
        if (!::navController.isInitialized) return
        navController.navigate(
            R.id.chatListFragment,
            null,
            navOptions { popUpTo(R.id.chatListFragment) { inclusive = false } }
        )
        navController.navigate(
            R.id.chatDetailFragment,
            bundleOf("groupName" to groupName, "groupId" to groupId)
        )
    }

    override fun onResume() {
        super.onResume()
        // 会话过期兜底：token 失效触发自动登出后，本页可能仍存活在返回栈中，
        // resume 时发现未登录则强制回登录页（防后台 Activity 启动被系统拦截后停在旧界面）
        if (!SessionStore.isLoggedIn()) {
            startActivity(
                Intent(this, LoginActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                }
            )
            finish()
        }
    }

    /**
     * 处理 GitHub 安装回调的 App Link（后端 302 回跳）：
     * https://mobile.qgents.dpdns.org/app/integrations/github?teamId=...&installed=1
     * 解析 teamId 跳授权页，installed=1 时提示安装完成。
     * 返回是否命中深链；未命中时走常规路由门控。
     */
    private fun handleDeepLink(intent: Intent?): Boolean {
        val data = intent?.data ?: return false
        if (data.scheme != "https" || data.host != "mobile.qgents.dpdns.org") return false
        val teamId = data.getQueryParameter("teamId").orEmpty()
        if (teamId.isEmpty()) return false

        navController.navigate(
            R.id.githubFragment,
            null,
            navOptions { popUpTo(R.id.splashFragment) { inclusive = true } }
        )
        navController.navigate(
            R.id.githubAuthorizeFragment,
            bundleOf("teamId" to teamId, "teamName" to "")
        )
        if (data.getQueryParameter("installed") == "1") {
            Toast.makeText(this, R.string.github_install_success, Toast.LENGTH_SHORT).show()
        }
        return true
    }

    /**
     * 启动路由门控（基于 MainViewModel 已就绪的数据，不再单独拉取）：
     * - 无团队 → 团队引导页（创建/加入）
     * - 有团队但无项目 → GitHub 仓库绑定页
     * - 两者都有 → 默认群聊列表页
     */
    private fun routeInitialDestination() {
        var routed = false
        mainViewModel.initialDataLoaded.observe(this) { loaded ->
            if (routed || !loaded) return@observe
            routed = true
            if (mainViewModel.teams.value.isNullOrEmpty()) {
                startActivity(Intent(this@MainActivity, TeamEntryActivity::class.java))
                finish()
                return@observe
            }
            val hasProjects = mainViewModel.projects.value?.isNotEmpty() == true
            val destinationId = if (hasProjects) R.id.chatListFragment else R.id.githubFragment
            // 因团队未创建项目而跳转 GitHub 页：弹提示
            if (!hasProjects) {
                Toast.makeText(this@MainActivity, R.string.github_enter_no_project, Toast.LENGTH_SHORT).show()
            }
            navController.navigate(
                destinationId,
                null,
                navOptions { popUpTo(R.id.splashFragment) { inclusive = true } }
            )
        }
    }

    /** 群聊列表页左上角头像点击时调用，打开个人中心抽屉 */
    fun openDrawer() {
        // 打开抽屉时全量刷新：团队列表 + 当前团队项目 + 未读红点
        mainViewModel.refreshDrawer()
        binding.drawerLayout.openDrawer(GravityCompat.START)
    }

    /** 抽屉内跳转主内容页前调用，收起个人中心抽屉 */
    fun closeDrawer() {
        binding.drawerLayout.closeDrawer(GravityCompat.START)
    }
}
