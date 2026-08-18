package com.example.qgent.ui.personal

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.os.bundleOf
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.navigation.NavController
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.navOptions
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.qgent.MainActivity
import com.example.qgent.QgentApp
import com.example.qgent.R
import com.example.qgent.data.SessionStore
import com.example.qgent.databinding.FragmentPersonalCenterBinding
import com.example.qgent.ui.team.TeamAdapter
import com.example.qgent.viewmodel.MainViewModel

class PersonalCenterFragment : Fragment() {

    private var _binding: FragmentPersonalCenterBinding? = null
    private val binding get() = _binding!!
    private val mainViewModel: MainViewModel by activityViewModels {
        (requireActivity().application as QgentApp).container.mainViewModelFactory
    }
    private lateinit var teamAdapter: TeamAdapter
    private lateinit var projectAdapter: ProjectAdapter
    private var onGithubPage = false

    private val navController: NavController
        get() = (requireActivity().supportFragmentManager
            .findFragmentById(R.id.navHostFragment) as NavHostFragment).navController

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentPersonalCenterBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // 左列：团队列表（点击切换当前团队）
        teamAdapter = TeamAdapter(emptyList()) { teamName, _ -> onTeamClick(teamName) }
        binding.rvTeams.layoutManager = LinearLayoutManager(requireContext())
        binding.rvTeams.adapter = teamAdapter

        // 右列：项目列表（随团队切换，点击切换当前项目）
        projectAdapter = ProjectAdapter { projectName, _ ->
            // 未选中团队时不允许选择项目，点击无效
            if (teamAdapter.selectedPosition < 0) {
                return@ProjectAdapter false
            }
            mainViewModel.setCurrentProject(projectName)
            openMainPage()
            true
        }
        binding.rvProjects.layoutManager = LinearLayoutManager(requireContext())
        binding.rvProjects.adapter = projectAdapter

        binding.tvUserName.text = SessionStore.user()?.displayName ?: getString(R.string.user_name_placeholder)

        // 团队列表加载完成后更新 adapter（复用实例，保持选中态，不重建）
        mainViewModel.teams.observe(viewLifecycleOwner) { teams ->
            teamAdapter.submitList(teams)
            refreshHighlight()
        }

        // 当前团队变化：右上团队名同步 + 高亮刷新
        mainViewModel.currentTeam.observe(viewLifecycleOwner) { team ->
            binding.tvTeamName.text = team
            refreshHighlight()
        }

        // 项目列表随团队切换
        mainViewModel.projects.observe(viewLifecycleOwner) { projects ->
            projectAdapter.submitList(projects)
            refreshHighlight()
        }

        // 切换团队加载项目期间，抽屉中央显示 ProgressBar
        mainViewModel.projectsLoading.observe(viewLifecycleOwner) { loading ->
            binding.progressBar.isVisible = loading
        }

        // 当前项目变化：右列项目高亮同步
        mainViewModel.currentProject.observe(viewLifecycleOwner) {
            refreshHighlight()
        }

        // 进入 GitHub 页时清空项目列表信息并清除团队/项目高光；离开后恢复
        navController.addOnDestinationChangedListener { _, dest, _ ->
            onGithubPage = dest.id == R.id.githubFragment
            if (onGithubPage) {
                teamAdapter.selectedPosition = NO_SELECTION
                projectAdapter.submitList(emptyList())
                projectAdapter.selectedPosition = NO_SELECTION
            } else {
                projectAdapter.submitList(mainViewModel.projects.value.orEmpty())
                refreshHighlight()
            }
        }

        // 头像 → 收起抽屉并进入个人信息页
        binding.btnAvatar.setOnClickListener { openProfile() }

        // 团队管理 → 收起抽屉并进入团队管理页
        binding.btnTeamManage.setOnClickListener { openTeamManage() }

        // GitHub 图标 → 收起抽屉并进入 GitHub 页
        binding.ivGithub.setOnClickListener { openGithub() }

        // 新建项目 → 关闭抽屉并进入新建项目页
        binding.btnNewProject.setOnClickListener { openNewProject() }

        // 右上角铃铛 → 关闭抽屉并进入消息列表页
        binding.tvAddTeam.setOnClickListener { openMessageList() }

        // 未读团队邀请 → 铃铛右上角红点
        mainViewModel.unreadInvitations.observe(viewLifecycleOwner) { hasUnread ->
            binding.ivInviteBadge.isVisible = hasUnread
        }
    }

    /** 收起个人中心抽屉，并在主内容区打开消息列表页 */
    private fun openMessageList() {
        (activity as? MainActivity)?.closeDrawer()
        mainViewModel.refreshUnreadInvitations()
        navController.navigate(R.id.messageListFragment)
    }

    /** 点击团队切换：若该团队未创建任何项目，则收起抽屉并进入 GitHub 页，并弹提示 */
    private fun onTeamClick(teamName: String) {
        // 在 GitHub 页时项目列表已被清空，重复点选同一团队也强制重载
        mainViewModel.setCurrentTeam(
            teamName,
            force = onGithubPage,
            onProjectsLoaded = { hasProjects ->
                if (!hasProjects) {
                    // 每次切换到无项目团队都提示：已在 GitHub 页则只弹不重复导航
                    if (!onGithubPage) openGithub()
                    Toast.makeText(requireContext(), R.string.github_enter_no_project, Toast.LENGTH_SHORT).show()
                }
            }
        )
    }

    /** 刷新抽屉团队/项目高光：始终恢复当前团队/项目选中 */
    private fun refreshHighlight() {
        if (!::teamAdapter.isInitialized) return
        val teams = mainViewModel.teams.value ?: emptyList()
        val curTeam = mainViewModel.currentTeam.value
        if (curTeam in teams) teamAdapter.selectedPosition = teams.indexOf(curTeam)
        val curProject = mainViewModel.currentProject.value
        if (!curProject.isNullOrEmpty()) {
            projectAdapter.selectedPosition = projectAdapter.indexOf(curProject)
        }
    }

    /** 收起个人中心抽屉，并在主内容区打开 GitHub 页 */
    private fun openGithub() {
        (activity as? MainActivity)?.closeDrawer()
        // 每次从抽屉点击都强制刷新团队列表，即使已停留在 GitHub 页
        mainViewModel.refreshTeams()
        // 已在 GitHub 页时避免重复压栈（团队无项目时 onTeamClick 回调会再次进入）
        if (onGithubPage) return
        navController.navigate(R.id.githubFragment)
    }

    /** 收起个人中心抽屉，并跳转至主界面（群聊 / 任务 / Agent 三 Tab） */
    private fun openMainPage() {
        (activity as? MainActivity)?.closeDrawer()
        navController.navigate(
            R.id.chatListFragment,
            null,
            navOptions {
                popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                launchSingleTop = true
            }
        )
    }

    /** 收起个人中心抽屉，并在主内容区打开个人信息页 */
    private fun openProfile() {
        (activity as? MainActivity)?.closeDrawer()
        navController.navigate(R.id.profileFragment)
    }

    /** 收起个人中心抽屉，并在主内容区打开团队管理页 */
    private fun openTeamManage() {
        (activity as? MainActivity)?.closeDrawer()
        navController.navigate(R.id.teamManageFragment)
    }

    /** 收起个人中心抽屉，并在主内容区打开新建项目页（无创建权限时拦截提示） */
    private fun openNewProject() {
        val teamId = mainViewModel.currentTeamId()
        if (teamId == null) {
            Toast.makeText(requireContext(), R.string.new_project_missing_team, Toast.LENGTH_SHORT).show()
            return
        }
        if (!mainViewModel.canCreateProject(mainViewModel.currentTeam.value.orEmpty())) {
            Toast.makeText(requireContext(), R.string.new_project_no_permission, Toast.LENGTH_SHORT).show()
            return
        }
        (activity as? MainActivity)?.closeDrawer()
        navController.navigate(R.id.newProjectFragment, bundleOf("teamId" to teamId))
    }

    companion object {
        private const val NO_SELECTION = -1
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
