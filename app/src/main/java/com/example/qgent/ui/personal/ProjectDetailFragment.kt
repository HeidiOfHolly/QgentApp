package com.example.qgent.ui.personal

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.example.qgent.QgentApp
import com.example.qgent.R
import com.example.qgent.data.repository.GitHubRepository
import com.example.qgent.data.repository.UserRepository
import com.example.qgent.databinding.FragmentProjectDetailBinding
import com.example.qgent.databinding.ItemChatMemberBinding
import com.example.qgent.databinding.ItemRepositoryBinding
import com.example.qgent.viewmodel.MainViewModel
import kotlinx.coroutines.launch

/**
 * 项目详情页：展示当前项目的信息（头像/名称/简介），
 * 成员与已绑定仓库为两个下拉分组（三角箭头切换，默认收起）。
 */
class ProjectDetailFragment : Fragment() {

    private var _binding: FragmentProjectDetailBinding? = null
    private val binding get() = _binding!!
    private val mainViewModel: MainViewModel by activityViewModels {
        (requireActivity().application as QgentApp).container.mainViewModelFactory
    }

    private val userRepository: UserRepository
        get() = (requireActivity().application as QgentApp).container.userRepository
    private val githubRepository: GitHubRepository
        get() = (requireActivity().application as QgentApp).container.githubRepository

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentProjectDetailBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.ivBack.setOnClickListener { findNavController().navigateUp() }
        binding.tvProjectName.text = mainViewModel.currentProject.value?.takeIf { it.isNotEmpty() }
            ?: getString(R.string.project_detail_title)

        // 两个下拉分组：默认收起，点击头部展开 / 收起（与团队详情页一致）
        bindCollapsibleSection(binding.headerMembers, binding.ivArrowMembers, binding.sectionMembers)
        bindCollapsibleSection(binding.headerRepositories, binding.ivArrowRepositories, binding.sectionRepositories)

        val projectId = mainViewModel.currentProjectId()
        if (projectId == null) {
            binding.tvProjectDetailName.text = mainViewModel.currentProject.value
            binding.tvProjectDescription.text = getString(R.string.project_desc_missing)
            return
        }

        binding.tvProjectDetailName.text = mainViewModel.currentProject.value
        loadProjectInfo(projectId)
        loadMembers(projectId)
        loadRepositories(projectId)
    }

    /** 项目简介：从团队项目列表查当前项目的 description */
    private fun loadProjectInfo(projectId: String) {
        val teamId = mainViewModel.currentTeamId() ?: return
        viewLifecycleOwner.lifecycleScope.launch {
            userRepository.getProjects(teamId).getOrNull().orEmpty()
                .firstOrNull { it.id == projectId }
                ?.description
                ?.takeIf { it.isNotBlank() }
                ?.let { binding.tvProjectDescription.text = it }
        }
    }

    /** 成员：项目成员 + 团队成员表反查显示名 */
    private fun loadMembers(projectId: String) {
        val teamId = mainViewModel.currentTeamId()
        viewLifecycleOwner.lifecycleScope.launch {
            val nameById = if (teamId != null) {
                userRepository.getTeamMembers(teamId).getOrNull().orEmpty()
                    .associate { it.userId to it.displayName }
            } else emptyMap()
            val members = userRepository.getProjectMembers(projectId).getOrNull().orEmpty()
            binding.tvMembersEmpty.isVisible = members.isEmpty()
            fillLinearLayout(binding.rvMembers, members, R.layout.item_chat_member) { view, member ->
                val item = ItemChatMemberBinding.bind(view)
                item.tvMemberName.text = nameById[member.userId] ?: getString(R.string.member_unknown)
            }
        }
    }

    /** 已绑定仓库列表（仅展示，无操作） */
    private fun loadRepositories(projectId: String) {
        viewLifecycleOwner.lifecycleScope.launch {
            val repos = githubRepository.getProjectRepositories(projectId).getOrNull().orEmpty()
            binding.tvRepositoriesEmpty.isVisible = repos.isEmpty()
            fillLinearLayout(binding.rvRepositories, repos, R.layout.item_repository) { view, repo ->
                val item = ItemRepositoryBinding.bind(view)
                item.tvRepositoryName.text = repo.fullName
                // 项目详情仅展示仓库名，隐藏「已绑定/未绑定」标签与删除按钮
                item.tvBoundStatus.isVisible = false
                item.ivDeleteRepository.isVisible = false
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
