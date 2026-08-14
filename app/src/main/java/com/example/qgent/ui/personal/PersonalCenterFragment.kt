package com.example.qgent.ui.personal

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.navigation.NavController
import androidx.navigation.fragment.NavHostFragment
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.qgent.MainActivity
import com.example.qgent.QgentApp
import com.example.qgent.R
import com.example.qgent.data.SessionStore
import com.example.qgent.databinding.FragmentPersonalCenterBinding
import com.example.qgent.viewmodel.MainViewModel

class PersonalCenterFragment : Fragment() {

    private var _binding: FragmentPersonalCenterBinding? = null
    private val binding get() = _binding!!
    private val mainViewModel: MainViewModel by activityViewModels {
        (requireActivity().application as QgentApp).container.mainViewModelFactory
    }
    private lateinit var teamAdapter: TeamAdapter
    private lateinit var projectAdapter: ProjectAdapter
    /** 当前主内容页是否为 GitHub 页：是则抽屉不显示团队/项目高光 */
    private var isOnGithub = false

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
            mainViewModel.setCurrentProject(projectName)
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

        // 当前项目变化：右列项目高亮同步
        mainViewModel.currentProject.observe(viewLifecycleOwner) {
            refreshHighlight()
        }

        // 在 GitHub 页时清空抽屉团队/项目高光，离开后恢复当前选中
        navController.addOnDestinationChangedListener { _, dest, _ ->
            isOnGithub = dest.id == R.id.githubFragment
            refreshHighlight()
        }

        // 头像 → 收起抽屉并进入个人信息页
        binding.btnAvatar.setOnClickListener { openProfile() }

        // 团队管理 → 收起抽屉并进入团队管理页
        binding.btnTeamManage.setOnClickListener { openTeamManage() }

        // GitHub 图标 → 收起抽屉并进入 GitHub 页
        binding.ivGithub.setOnClickListener { openGithub() }

        // 铃铛 → 消息列表
        binding.btnNotification.setOnClickListener { openMessageList() }
    }

    /** 点击团队切换：若该团队未创建任何项目，则收起抽屉并进入 GitHub 页 */
    private fun onTeamClick(teamName: String) {
        mainViewModel.setCurrentTeam(teamName) { hasProjects ->
            if (!hasProjects) openGithub()
        }
    }

    /** 刷新抽屉团队/项目高光：GitHub 页不显示高光，其余页面恢复当前团队/项目选中 */
    private fun refreshHighlight() {
        if (!::teamAdapter.isInitialized) return
        if (isOnGithub) {
            teamAdapter.selectedPosition = NO_SELECTION
            projectAdapter.selectedPosition = NO_SELECTION
        } else {
            val teams = mainViewModel.teams.value ?: emptyList()
            val curTeam = mainViewModel.currentTeam.value
            if (curTeam in teams) teamAdapter.selectedPosition = teams.indexOf(curTeam)
            val curProject = mainViewModel.currentProject.value
            if (!curProject.isNullOrEmpty()) {
                projectAdapter.selectedPosition = projectAdapter.indexOf(curProject)
            }
        }
    }

    /** 收起个人中心抽屉，并在主内容区打开 GitHub 页 */
    private fun openGithub() {
        (activity as? MainActivity)?.closeDrawer()
        navController.navigate(R.id.githubFragment)
    }

    /** 收起个人中心抽屉，并在主内容区打开消息列表页 */
    private fun openMessageList() {
        (activity as? MainActivity)?.closeDrawer()
        navController.navigate(R.id.messageListFragment)
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

    companion object {
        private const val NO_SELECTION = -1
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
