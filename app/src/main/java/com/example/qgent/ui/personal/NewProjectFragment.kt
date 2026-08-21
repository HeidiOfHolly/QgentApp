package com.example.qgent.ui.personal

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.os.bundleOf
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.navigation.fragment.findNavController
import androidx.navigation.navOptions
import com.example.qgent.QgentApp
import com.example.qgent.R
import com.example.qgent.databinding.FragmentNewProjectBinding
import com.example.qgent.viewmodel.CreateProjectState
import com.example.qgent.viewmodel.MainViewModel
import com.example.qgent.viewmodel.NewProjectViewModel

/** 新建项目表单页：名称/简介 + 邀请成员/绑定仓库入口 + 新建按钮 */
class NewProjectFragment : Fragment() {

    private var _binding: FragmentNewProjectBinding? = null
    private val binding get() = _binding!!

    private val mainViewModel: MainViewModel by activityViewModels {
        (requireActivity().application as QgentApp).container.mainViewModelFactory
    }
    private val newProjectViewModel: NewProjectViewModel by activityViewModels {
        (requireActivity().application as QgentApp).container.newProjectViewModelFactory
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentNewProjectBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        newProjectViewModel.init(arguments?.getString(ARG_TEAM_ID).orEmpty())

        // 从草稿恢复输入（选择页返回后不丢失）
        val draft = newProjectViewModel.draft.value ?: NewProjectViewModel.Draft()
        binding.etName.setText(draft.name)
        binding.etDescription.setText(draft.description)

        binding.etName.doAfterTextChanged {
            if (binding.nameLayout.error != null) binding.nameLayout.error = null
            newProjectViewModel.updateName(it?.toString().orEmpty())
        }
        binding.etDescription.doAfterTextChanged {
            newProjectViewModel.updateDescription(it?.toString().orEmpty())
        }

        binding.ivBack.setOnClickListener { findNavController().navigateUp() }

        binding.layoutInviteMembers.setOnClickListener {
            findNavController().navigate(R.id.projectSelectionFragment, bundleOf(ProjectSelectionFragment.ARG_MODE to ProjectSelectionFragment.MODE_MEMBERS))
        }
        binding.layoutBindRepos.setOnClickListener {
            findNavController().navigate(R.id.projectSelectionFragment, bundleOf(ProjectSelectionFragment.ARG_MODE to ProjectSelectionFragment.MODE_REPOS))
        }

        binding.bnCreate.setOnClickListener { createProject() }

        newProjectViewModel.draft.observe(viewLifecycleOwner) { d ->
            binding.tvInviteMembers.text = if (d.selectedMembers.isEmpty()) getString(R.string.invite_members)
            else getString(R.string.selected_members_count, d.selectedMembers.size)
            // 自动建仓 / 绑定已有仓库二选一（清单一）
            binding.tvBindRepos.text = when {
                d.newRepoNames.isNotEmpty() -> getString(R.string.selected_new_repos_count, d.newRepoNames.size)
                d.selectedRepos.isNotEmpty() -> getString(R.string.selected_repos_count, d.selectedRepos.size)
                else -> getString(R.string.bind_repos)
            }
        }

        mainViewModel.createProjectState.observe(viewLifecycleOwner) { state ->
            when (state) {
                is CreateProjectState.Success -> {
                    Toast.makeText(requireContext(), R.string.project_create_success, Toast.LENGTH_SHORT).show()
                    findNavController().navigate(
                        R.id.chatDetailFragment,
                        bundleOf("groupName" to state.groupName, "groupId" to state.groupId),
                        navOptions { popUpTo(R.id.newProjectFragment) { inclusive = true } }
                    )
                }
                is CreateProjectState.Error -> {
                    Toast.makeText(requireContext(), state.message, Toast.LENGTH_SHORT).show()
                }
                else -> Unit
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // 从 GitHub 绑定页返回后刷新个人 OAuth 状态（自动建仓可用性以此为准，§50.4）
        newProjectViewModel.refreshOAuthStatus()
    }

    private fun createProject() {
        val name = binding.etName.text?.toString()?.trim().orEmpty()
        if (name.isEmpty()) {
            binding.nameLayout.error = getString(R.string.error_project_name_required)
            return
        }
        val draft = newProjectViewModel.draft.value ?: return
        val rawDescription = binding.etDescription.text?.toString()?.trim().orEmpty()
        val description = if (rawDescription.isEmpty()) null else rawDescription

        // 自动建仓 vs 绑定已有仓库二选一（清单一）：自动建仓支持多个仓库名，逐次创建项目
        if (draft.newRepoNames.isNotEmpty()) {
            // §50.4：个人建仓需 OAuth 已授权且 READY，未就绪时引导去绑定（后端仍会强校验）
            if (!newProjectViewModel.canAutoCreateRepo()) {
                Toast.makeText(requireContext(), R.string.auto_create_repo_oauth_locked, Toast.LENGTH_SHORT).show()
                findNavController().navigate(R.id.personalGithubOAuthFragment)
                return
            }
            mainViewModel.createProjectAutoRepos(
                name = name,
                description = description,
                memberIds = draft.selectedMembers.map { it.userId },
                newRepoNames = draft.newRepoNames,
                isPrivate = draft.isPrivate
            )
        } else {
            mainViewModel.createProject(
                name = name,
                description = description,
                memberIds = draft.selectedMembers.map { it.userId },
                repos = draft.selectedRepos
            )
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        const val ARG_TEAM_ID = "teamId"
    }
}
