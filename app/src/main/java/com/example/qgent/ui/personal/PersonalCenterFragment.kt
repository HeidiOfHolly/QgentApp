package com.example.qgent.ui.personal

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.recyclerview.widget.LinearLayoutManager
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

        // mock 团队数据，后续接入接口
        val teams = listOf("团队A", "团队B", "团队C", "团队D")

        // 左列：团队列表（点击切换当前团队）
        teamAdapter = TeamAdapter(teams) { teamName, _ ->
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

        // 当前团队变化：左列高亮同步、右上团队名同步、项目列表跟着切换
        mainViewModel.currentTeam.observe(viewLifecycleOwner) { team ->
            val index = teams.indexOf(team)
            if (index >= 0) teamAdapter.selectedPosition = index
            binding.tvTeamName.text = team
            projectAdapter.submitList(mainViewModel.projectsOf(team))
        }

        // 当前项目变化：右列项目高亮同步
        mainViewModel.currentProject.observe(viewLifecycleOwner) { project ->
            projectAdapter.selectedPosition = projectAdapter.indexOf(project)
        }

        // 以下跳转均为占位，后续实现
        binding.btnTeamManage.setOnClickListener { showTodoToast() }
        binding.btnAvatar.setOnClickListener { showTodoToast() }
        binding.btnNotification.setOnClickListener { showTodoToast() }
    }

    private fun showTodoToast() {
        Toast.makeText(requireContext(), R.string.todo_placeholder, Toast.LENGTH_SHORT).show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
