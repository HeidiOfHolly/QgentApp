package com.example.qgent.ui.team

import android.animation.ObjectAnimator
import android.app.AlertDialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.qgent.QgentApp
import com.example.qgent.R
import com.example.qgent.data.repository.GitHubRepository
import com.example.qgent.data.repository.UserRepository
import com.example.qgent.databinding.DialogNewProjectBinding
import com.example.qgent.databinding.FragmentTeamDetailBinding
import com.example.qgent.ui.github.GithubViewModel
import com.example.qgent.databinding.ItemRepoSelectBinding
import com.example.qgent.ui.tasks.TeamProjectAdapter
import com.example.qgent.ui.personal.bindCollapsibleSection
import com.example.qgent.ui.personal.newInputDialog
import com.example.qgent.ui.personal.setupRecyclerList
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

    private val githubRepository: GitHubRepository
        get() = (requireActivity().application as QgentApp).container.githubRepository
    private val userRepository: UserRepository
        get() = (requireActivity().application as QgentApp).container.userRepository

    private val projectAdapter = TeamProjectAdapter()
    private val repositoryAdapter = TeamRepositoryAdapter()

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
        // 我创建的团队 → 解散团队；我加入的团队 → 退出团队（默认按创建的兜底）
        val isOwner = arguments?.getBoolean(ARG_IS_OWNER, true) ?: true

        binding.ivBack.setOnClickListener { findNavController().navigateUp() }
        binding.tvTeamName.text = teamName

        // 三个三角下拉分组：默认收起，点击头部展开 / 收起
        bindCollapsibleSection(binding.headerMembers, binding.ivArrowMembers, binding.sectionMembers)
        bindCollapsibleSection(binding.headerProjects, binding.ivArrowProjects, binding.sectionProjects)
        bindCollapsibleSection(binding.headerRepository, binding.ivArrowRepository, binding.sectionRepository)

        // 展开时分组顶部显示增加按钮
        binding.btnAddMember.setOnClickListener { showTodoToast() }
        binding.btnAddProject.setOnClickListener { showNewProjectDialog() }
        binding.btnRepository.setOnClickListener { showTodoToast() }

        setupRecyclerList(binding.rvProjects, projectAdapter)
        setupRecyclerList(binding.rvRepository, repositoryAdapter)

        // 项目列表来自 MainViewModel（真实数据流）
        mainViewModel.projects.observe(viewLifecycleOwner) { projectAdapter.submitList(it) }

        // 仓库列表来自 GitHub 授权仓库（fullName）；成员列表暂无数据源
        githubViewModel.uiState.observe(viewLifecycleOwner) { state ->
            repositoryAdapter.submitList(state.repositories.map { it.fullName })
        }
        if (teamId.isNotEmpty()) {
            githubViewModel.loadRepositories(teamId)
        }

        binding.btnDissolveTeam.text =
            getString(if (isOwner) R.string.dissolve_team else R.string.exit_team)
        binding.btnDissolveTeam.setOnClickListener { confirmLeaveOrDissolve(isOwner) }
    }

    /** 底部操作：我创建的团队 → 解散；我加入的团队 → 退出。API 待后端就绪后接入 */
    private fun confirmLeaveOrDissolve(isOwner: Boolean) {
        val titleRes = if (isOwner) R.string.dissolve_team else R.string.exit_team
        val confirmRes = if (isOwner) R.string.dissolve_team_confirm else R.string.exit_team_confirm
        val toastRes = if (isOwner) R.string.dissolve_team_placeholder else R.string.exit_team_placeholder
        AlertDialog.Builder(requireContext())
            .setTitle(titleRes)
            .setMessage(confirmRes)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(titleRes) { _, _ ->
                Toast.makeText(requireContext(), toastRes, Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    /** 新建项目：弹出名称/简介输入弹窗，GitHub 仓库下拉选择已授权未绑定的仓库 */
    private fun showNewProjectDialog() {
        val dialogBinding = DialogNewProjectBinding.inflate(layoutInflater)
        val dialog = newInputDialog(dialogBinding.root)

        dialogBinding.etName.doAfterTextChanged {
            if (dialogBinding.nameLayout.error != null) dialogBinding.nameLayout.error = null
        }

        val repoAdapter = RepoSelectAdapter { repoName ->
            dialogBinding.tvRepoSelect.text = repoName
            setRepoListExpanded(dialogBinding, expanded = false)
        }
        dialogBinding.rvRepositories.layoutManager = LinearLayoutManager(requireContext())
        dialogBinding.rvRepositories.adapter = repoAdapter

        var repoLoading = false
        dialogBinding.layoutGithub.setOnClickListener {
            when {
                dialogBinding.rvRepositories.isVisible -> setRepoListExpanded(dialogBinding, expanded = false)
                repoLoading -> Unit // 加载中忽略重复点击
                else -> {
                    repoLoading = true
                    loadUnboundRepositories { names ->
                        repoLoading = false
                        if (names.isEmpty()) {
                            Toast.makeText(requireContext(), R.string.github_repo_empty, Toast.LENGTH_SHORT).show()
                        } else {
                            repoAdapter.submitList(names)
                            setRepoListExpanded(dialogBinding, expanded = true)
                        }
                    }
                }
            }
        }

        dialogBinding.bnNewTeam.setOnClickListener {
            if (dialogBinding.etName.text.toString().trim().isEmpty()) {
                dialogBinding.nameLayout.error = getString(R.string.error_project_name_required)
                return@setOnClickListener
            }
            dialog.dismiss()
            // 创建项目 API 待后端就绪后接入
            Toast.makeText(requireContext(), R.string.new_project_placeholder, Toast.LENGTH_SHORT).show()
        }

        dialog.show()
    }

    private fun setRepoListExpanded(binding: DialogNewProjectBinding, expanded: Boolean) {
        binding.rvRepositories.isVisible = expanded
        ObjectAnimator.ofFloat(
            binding.ivRepoChevron,
            View.ROTATION,
            if (expanded) -90f else 90f
        ).setDuration(180).start()
    }

    /** 加载当前团队所有已授权且未被任何项目绑定的仓库名 */
    private fun loadUnboundRepositories(onLoaded: (List<String>) -> Unit) {
        val teamName = arguments?.getString(ARG_TEAM_NAME).orEmpty()
        viewLifecycleOwner.lifecycleScope.launch {
            val teamId = mainViewModel.teamDtos.value?.find { it.name == teamName }?.id
            if (teamId == null) {
                onLoaded(emptyList())
                return@launch
            }
            val projects = userRepository.getProjects(teamId).getOrNull() ?: emptyList()
            val boundIds = projects
                .mapNotNull { githubRepository.getProjectRepositories(it.id).getOrNull() }
                .flatten()
                .map { it.repositoryId }
                .toSet()
            val authorized = githubRepository.getGithubRepositories(teamId).getOrNull() ?: emptyList()
            onLoaded(authorized.filter { it.id !in boundIds }.map { it.fullName })
        }
    }

    /** 仓库下拉列表 adapter：点击仓库回调仓库名 */
    private class RepoSelectAdapter(
        private val onSelect: (String) -> Unit
    ) : RecyclerView.Adapter<RepoSelectAdapter.VH>() {

        private val items = mutableListOf<String>()

        fun submitList(list: List<String>) {
            items.clear()
            items.addAll(list)
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val binding = ItemRepoSelectBinding.inflate(
                LayoutInflater.from(parent.context), parent, false
            )
            return VH(binding)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            holder.bind(items[position])
        }

        override fun getItemCount(): Int = items.size

        inner class VH(private val binding: ItemRepoSelectBinding) :
            RecyclerView.ViewHolder(binding.root) {

            fun bind(name: String) {
                binding.tvRepoName.text = name
                binding.root.setOnClickListener { onSelect(name) }
            }
        }
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
        const val ARG_IS_OWNER = "isOwner"
    }
}
