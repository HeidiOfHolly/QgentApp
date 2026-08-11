package com.example.qgent.ui.personal

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.navigation.fragment.NavHostFragment
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.qgent.MainActivity
import com.example.qgent.R
import com.example.qgent.databinding.FragmentPersonalCenterBinding
import com.example.qgent.viewmodel.MainViewModel

class PersonalCenterFragment : Fragment() {

    private var _binding: FragmentPersonalCenterBinding? = null
    private val binding get() = _binding!!
    private val mainViewModel: MainViewModel by activityViewModels()
    private lateinit var teamAdapter: TeamAdapter
    private lateinit var projectAdapter: ProjectAdapter

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
        teamAdapter = TeamAdapter(emptyList()) { teamName, _ ->
            mainViewModel.setCurrentTeam(teamName)
        }
        binding.rvTeams.layoutManager = LinearLayoutManager(requireContext())
        binding.rvTeams.adapter = teamAdapter

        // 右列：项目列表（随团队切换，点击切换当前项目）
        projectAdapter = ProjectAdapter { projectName, _ ->
            mainViewModel.setCurrentProject(projectName)
        }
        binding.rvProjects.layoutManager = LinearLayoutManager(requireContext())
        binding.rvProjects.adapter = projectAdapter

        binding.tvUserName.setText(R.string.user_name_placeholder)

        // 团队列表加载完成后更新 adapter
        mainViewModel.teams.observe(viewLifecycleOwner) { teams ->
            if (teams.isNotEmpty()) {
                teamAdapter = TeamAdapter(teams) { teamName, _ ->
                    mainViewModel.setCurrentTeam(teamName)
                }
                binding.rvTeams.adapter = teamAdapter
                // 恢复当前团队高亮
                val curTeam = mainViewModel.currentTeam.value
                val index = teams.indexOf(curTeam)
                if (index >= 0) teamAdapter.selectedPosition = index
            }
        }

        // 当前团队变化：左列高亮同步、右上团队名同步
        mainViewModel.currentTeam.observe(viewLifecycleOwner) { team ->
            val teams = mainViewModel.teams.value ?: emptyList()
            val index = teams.indexOf(team)
            if (index >= 0 && ::teamAdapter.isInitialized) teamAdapter.selectedPosition = index
            binding.tvTeamName.text = team
        }

        // 项目列表随团队切换
        mainViewModel.projects.observe(viewLifecycleOwner) { projects ->
            projectAdapter.submitList(projects)
        }

        // 当前项目变化：右列项目高亮同步
        mainViewModel.currentProject.observe(viewLifecycleOwner) { project ->
            projectAdapter.selectedPosition = projectAdapter.indexOf(project)
        }

        // 头像 → 收起抽屉并进入个人信息页
        binding.btnAvatar.setOnClickListener { openProfile() }

        // 以下跳转均为占位，后续实现
        binding.btnTeamManage.setOnClickListener { showTodoToast() }
        binding.btnNotification.setOnClickListener { showTodoToast() }
    }

    /** 收起个人中心抽屉，并在主内容区打开个人信息页 */
    private fun openProfile() {
        (activity as? MainActivity)?.closeDrawer()
        val navController = (requireActivity().supportFragmentManager
            .findFragmentById(R.id.navHostFragment) as NavHostFragment).navController
        navController.navigate(R.id.profileFragment)
    }

    private fun showTodoToast() {
        Toast.makeText(requireContext(), R.string.todo_placeholder, Toast.LENGTH_SHORT).show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
