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
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavController
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.navOptions
import androidx.navigation.ui.setupWithNavController
import com.example.qgent.data.SessionStore
import com.example.qgent.databinding.ActivityMainBinding
import com.example.qgent.ui.auth.LoginActivity
import com.example.qgent.ui.auth.TeamEntryActivity
import com.example.qgent.ui.personal.PersonalCenterFragment
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var navController: NavController

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
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (::navController.isInitialized) {
            handleDeepLink(intent)
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
     * 启动路由门控：
     * - 无团队 → 团队引导页（创建/加入）
     * - 有团队但无项目 → GitHub 仓库绑定页
     * - 两者都有 → 默认群聊列表页
     */
    private fun routeInitialDestination() {
        val container = (application as QgentApp).container
        lifecycleScope.launch {
            val teams = container.userRepository.getTeams().getOrNull().orEmpty()
            if (teams.isEmpty()) {
                startActivity(Intent(this@MainActivity, TeamEntryActivity::class.java))
                finish()
                return@launch
            }
            val projects = container.realUserRepository.getProjects(teams.first().id).getOrNull().orEmpty()
            val hasGroups = projects.firstOrNull()?.let { project ->
                container.realChatRepository.getGroups(project.id).getOrNull().orEmpty().isNotEmpty()
            } ?: false
            val destinationId = if (projects.isEmpty() || !hasGroups) R.id.githubFragment else R.id.chatListFragment
            navController.navigate(
                destinationId,
                null,
                navOptions {
                    popUpTo(R.id.splashFragment) { inclusive = true }
                }
            )
        }
    }

    /** 群聊列表页左上角头像点击时调用，打开个人中心抽屉 */
    fun openDrawer() {
        binding.drawerLayout.openDrawer(GravityCompat.START)
    }

    /** 抽屉内跳转主内容页前调用，收起个人中心抽屉 */
    fun closeDrawer() {
        binding.drawerLayout.closeDrawer(GravityCompat.START)
    }
}
