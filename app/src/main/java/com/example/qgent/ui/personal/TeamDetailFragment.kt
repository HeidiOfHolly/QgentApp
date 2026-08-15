package com.example.qgent.ui.personal

import android.app.AlertDialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.os.bundleOf
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.example.qgent.QgentApp
import com.example.qgent.R
import com.example.qgent.data.repository.UserRepository
import com.example.qgent.databinding.FragmentTeamDetailBinding
import com.example.qgent.ui.github.GithubViewModel
import com.example.qgent.viewmodel.MainViewModel
import kotlinx.coroutines.launch

/**
 * 团队详情页：管理成员 / 管理项目 / 管理仓库三个下拉分组（默认收起，展开时顶部有增加按钮），底部解散团队。
 * 成员与仓库列表暂无 API 数据源，等待接口接入；项目列表观察 ViewModel 数据流。
 */
class TeamDetailFragment : Fragment() {

    private var _binding: FragmentTeamDetailBinding? = null
    private val binding get() = _binding!!
    private val mainViewModel: MainViewModel by activityViewModels {
        (requireActivity().application as QgentApp).container.mainViewModelFactory
    }
    private val githubViewModel: GithubViewModel by activityViewModels {
        (requireActivity().application as QgentApp).container.githubViewModelFactory
    }

    private val userRepository: UserRepository
        get() = (requireActivity().application as QgentApp).container.userRepository

    private val projectAdapter = TeamProjectAdapter()
    private val repositoryAdapter = TeamRepositoryAdapter()
    private val memberAdapter = TeamMemberAdapter()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentTeamDetailBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val teamName = arguments?.getString(ARG_TEAM_NAME).orEmpty()
        val teamId = arguments?.getString(ARG_TEAM_ID).orEmpty()

        binding.ivBack.setOnClickListener { findNavController().navigateUp() }
        binding.tvTeamName.text = teamName

        // 三个三角下拉分组：默认收起，点击头部展开 / 收起
        bindCollapsibleSection(binding.headerMembers, binding.ivArrowMembers, binding.sectionMembers)
        bindCollapsibleSection(binding.headerProjects, binding.ivArrowProjects, binding.sectionProjects)
        bindCollapsibleSection(binding.headerRepository, binding.ivArrowRepository, binding.sectionRepository)

        // 展开时分组顶部显示增加按钮
        binding.btnAddMember.setOnClickListener { showTodoToast() }
        binding.btnAddProject.setOnClickListener { openNewProject(teamId) }
        binding.btnRepository.setOnClickListener { showTodoToast() }

        setupRecyclerList(binding.rvProjects, projectAdapter)
        setupRecyclerList(binding.rvRepository, repositoryAdapter)
        setupRecyclerList(binding.rvMembers, memberAdapter)

        // 项目列表来自 MainViewModel（真实数据流）
        mainViewModel.projects.observe(viewLifecycleOwner) { projectAdapter.submitList(it) }

        // 仓库列表来自 GitHub 授权仓库（fullName）；成员列表暂无数据源
        githubViewModel.uiState.observe(viewLifecycleOwner) { state ->
            repositoryAdapter.submitList(state.repositories.map { it.fullName })
        }
        if (teamId.isNotEmpty()) {
            githubViewModel.loadRepositories(teamId)
            viewLifecycleOwner.lifecycleScope.launch {
                userRepository.getTeamMembers(teamId).onSuccess { memberAdapter.submitList(it) }
            }
        }

        binding.btnDissolveTeam.setOnClickListener { confirmDissolveTeam() }
    }

    private fun confirmDissolveTeam() {
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.dissolve_team)
            .setMessage(R.string.dissolve_team_confirm)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.dissolve_team) { _, _ ->
                Toast.makeText(requireContext(), R.string.dissolve_team_placeholder, Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    /** 新建项目：与抽屉页一致，进入新建项目页 */
    private fun openNewProject(teamId: String) {
        if (teamId.isEmpty()) {
            Toast.makeText(requireContext(), R.string.new_project_missing_team, Toast.LENGTH_SHORT).show()
            return
        }
        findNavController().navigate(R.id.newProjectFragment, bundleOf("teamId" to teamId))
    }

    private fun showTodoToast() {
        Toast.makeText(requireContext(), R.string.todo_placeholder, Toast.LENGTH_SHORT).show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        const val ARG_TEAM_NAME = "teamName"
        const val ARG_TEAM_ID = "teamId"
    }
}
