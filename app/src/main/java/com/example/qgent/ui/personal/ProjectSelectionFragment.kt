package com.example.qgent.ui.personal

import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.qgent.QgentApp
import com.example.qgent.R
import com.example.qgent.data.model.GitHubRepositoryDto
import com.example.qgent.data.model.PersonalGithubOAuthDto
import com.example.qgent.data.model.TeamMemberDto
import com.example.qgent.databinding.FragmentProjectSelectionBinding
import com.example.qgent.ui.github.PersonalGithubOAuthFragment
import com.example.qgent.viewmodel.NewProjectViewModel

/**
 * 新建项目共享多选页：按 mode 参数区分「邀请成员」或「绑定仓库」。
 * 选中结果写回 NewProjectViewModel，点「完成」返回表单页。
 * 自动建仓区受个人 GitHub OAuth 状态控制（§50.4）：未授权/非 READY 置灰并引导去绑定；
 * 仓库可见性按 canCreatePrivatePersonalRepository 控制私有开关。
 */
class ProjectSelectionFragment : Fragment() {

    private var _binding: FragmentProjectSelectionBinding? = null
    private val binding get() = _binding!!

    private val newProjectViewModel: NewProjectViewModel by activityViewModels {
        (requireActivity().application as QgentApp).container.newProjectViewModelFactory
    }

    private val adapter = SelectionAdapter { id, checked -> onToggle(id, checked) }
    private val selectedIds = mutableSetOf<String>()

    private var mode = MODE_MEMBERS
    private var memberItems = emptyList<TeamMemberDto>()
    private var repoItems = emptyList<GitHubRepositoryDto>()

    // 自动建仓仓库名列表（与勾选已有仓库二选一，清单一）
    private val newRepoNames = mutableListOf<String>()
    private val newRepoNameRegex = Regex("^[a-z0-9._-]+$")

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentProjectSelectionBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        mode = arguments?.getString(ARG_MODE) ?: MODE_MEMBERS
        val isMembers = mode == MODE_MEMBERS

        binding.ivBack.setOnClickListener { findNavController().navigateUp() }
        binding.tvTitle.text = getString(if (isMembers) R.string.selection_members_title else R.string.selection_repos_title)
        binding.tvEmpty.text = getString(if (isMembers) R.string.selection_members_empty else R.string.selection_repos_empty)
        binding.rvItems.layoutManager = LinearLayoutManager(requireContext())
        binding.rvItems.adapter = adapter
        binding.bnDone.setOnClickListener { confirm() }

        // 预选状态来自向导草稿
        val draft = newProjectViewModel.draft.value
        if (draft != null) {
            selectedIds.clear()
            selectedIds.addAll(if (isMembers) draft.selectedMembers.map { it.userId } else draft.selectedRepos.map { it.id })
        }

        // 自动建仓区块仅绑定仓库模式显示；从草稿恢复已添加的仓库名与可见性
        binding.autoCreateSection.isVisible = !isMembers
        if (!isMembers) {
            binding.bnAddRepo.setOnClickListener { addNewRepoName() }
            binding.bnBindGithub.setOnClickListener {
                openPersonalGithubOAuth()
            }
            binding.swPrivate.isChecked = draft?.isPrivate ?: true
            draft?.let { newRepoNames.addAll(it.newRepoNames) }
            renderNewRepoList()
            // 个人 OAuth 状态驱动自动建仓区可用性（§50.4）
            newProjectViewModel.oauthStatus.observe(viewLifecycleOwner) { status ->
                renderAutoCreateState(status)
            }
        }

        if (isMembers) {
            newProjectViewModel.members.observe(viewLifecycleOwner) { list ->
                memberItems = list
                refresh()
            }
        } else {
            newProjectViewModel.repos.observe(viewLifecycleOwner) { list ->
                repoItems = list
                refresh()
            }
        }

        newProjectViewModel.loadError.observe(viewLifecycleOwner) { msg ->
            msg?.let {
                Toast.makeText(requireContext(), it, Toast.LENGTH_SHORT).show()
                newProjectViewModel.consumeLoadError()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // 从个人 GitHub 绑定页返回后重新查询状态，再决定是否解除置灰（§50.4：不依赖回跳参数/本地缓存）
        if (mode == MODE_REPOS) {
            newProjectViewModel.refreshOAuthStatus()
        }
    }

    /**
     * 按个人 GitHub OAuth 状态渲染自动建仓区（§50.4）：
     * - 未授权/非 READY → 置灰输入与添加按钮 + 「去绑定 GitHub」入口；
     * - READY 但 scope 不足建私有 → 私有开关置灰并提示仅可建公开。
     */
    private fun renderAutoCreateState(status: PersonalGithubOAuthDto?) {
        val setup = status?.personalRepositorySetup
        val ready = status?.authorized == true && setup == "READY"
        val canPublic = ready && status?.canCreatePublicPersonalRepository == true
        val canPrivate = canPublic && status?.canCreatePrivatePersonalRepository == true
        Log.d(TAG, "Auto-create UI: status=${status != null}, authorized=${status?.authorized}, setup=$setup, canPublic=$canPublic, canPrivate=$canPrivate")
        val hint = when {
            !canPublic -> when (setup) {
                "NOT_OWNER" -> getString(R.string.personal_github_oauth_setup_not_owner)
                "NEED_INSTALLATION" -> getString(R.string.personal_github_oauth_setup_need_installation)
                "NEED_OAUTH" -> getString(R.string.personal_github_oauth_setup_need_oauth)
                "ACCOUNT_MISMATCH" -> getString(
                    R.string.personal_github_oauth_setup_account_mismatch,
                    status?.expectedInstallationLogin ?: getString(R.string.personal_github_oauth_unknown_account)
                )
                else -> getString(R.string.auto_create_repo_scope_public_hint)
            }
            !canPrivate -> getString(R.string.auto_create_repo_scope_private_hint)
            else -> null
        }
        binding.tvOauthHint.isVisible = hint != null
        binding.tvOauthHint.text = hint
        // 仅 OAuth 未绑定、账号不一致或 scope 不足时引导个人 OAuth；未安装 App/非 Owner 不误导到 OAuth。
        binding.bnBindGithub.isVisible = !canPublic && (
            status == null || !status.authorized || setup == "NEED_OAUTH" ||
                setup == "ACCOUNT_MISMATCH" || setup == "READY"
            )
        binding.etNewRepoName.isEnabled = canPublic
        binding.bnAddRepo.isEnabled = canPublic
        binding.swPrivate.isEnabled = canPrivate
        if (!canPrivate) {
            binding.swPrivate.isChecked = false
        } else if (ready) {
            binding.swPrivate.isChecked = newProjectViewModel.draft.value?.isPrivate ?: true
        }
    }

    private fun openPersonalGithubOAuth() {
        val forceReauthorization = newProjectViewModel.oauthStatus.value?.authorized == true
        findNavController().navigate(
            R.id.personalGithubOAuthFragment,
            androidx.core.os.bundleOf(PersonalGithubOAuthFragment.ARG_FORCE_REAUTH to forceReauthorization)
        )
    }

    private fun onToggle(id: String, checked: Boolean) {
        if (checked) selectedIds.add(id) else selectedIds.remove(id)
        // 二选一：勾选已有仓库时清空自动建仓仓库名
        if (checked && newRepoNames.isNotEmpty()) {
            newRepoNames.clear()
            renderNewRepoList()
        }
        refresh()
    }

    /** 添加一个自动建仓仓库名：校验命名约束；添加后清空已选已有仓库（二选一） */
    private fun addNewRepoName() {
        val name = binding.etNewRepoName.text?.toString()?.trim().orEmpty()
        when {
            name.isEmpty() -> {
                binding.newRepoInputLayout.error = getString(R.string.new_repo_name_required)
                return
            }
            !newRepoNameRegex.matches(name) -> {
                binding.newRepoInputLayout.error = getString(R.string.new_repo_name_invalid)
                return
            }
            name in newRepoNames -> {
                binding.newRepoInputLayout.error = getString(R.string.new_repo_duplicate)
                return
            }
        }
        binding.newRepoInputLayout.error = null
        binding.etNewRepoName.text?.clear()
        newRepoNames.add(name)
        if (selectedIds.isNotEmpty()) {
            selectedIds.clear()
            refresh()
        }
        renderNewRepoList()
    }

    /** 渲染已添加的自动建仓仓库名列表（每行带删除按钮） */
    private fun renderNewRepoList() {
        binding.newRepoList.removeAllViews()
        newRepoNames.forEach { name ->
            val row = com.example.qgent.databinding.ItemNewRepoRowBinding.inflate(
                layoutInflater, binding.newRepoList, false
            )
            row.tvRepoName.text = name
            row.ivDeleteRepo.setOnClickListener {
                newRepoNames.remove(name)
                renderNewRepoList()
            }
            binding.newRepoList.addView(row.root)
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun refresh() {
        val items = if (mode == MODE_MEMBERS) {
            memberItems.map { SelectionAdapter.Item(it.userId, it.displayName, it.userId in selectedIds) }
        } else {
            repoItems.map { SelectionAdapter.Item(it.id, it.fullName, it.id in selectedIds) }
        }
        adapter.submitList(items)
        binding.tvEmpty.isVisible = items.isEmpty()
        binding.rvItems.isVisible = items.isNotEmpty()
    }

    private fun confirm() {
        if (mode == MODE_MEMBERS) {
            newProjectViewModel.setSelectedMembers(memberItems.filter { it.userId in selectedIds })
        } else {
            newProjectViewModel.setSelectedRepos(repoItems.filter { it.id in selectedIds })
            newProjectViewModel.setNewRepoNames(newRepoNames.toList())
            newProjectViewModel.setRepoVisibility(binding.swPrivate.isChecked)
        }
        findNavController().navigateUp()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        const val ARG_MODE = "mode"
        const val MODE_MEMBERS = "members"
        const val MODE_REPOS = "repos"
        private const val TAG = "NewProjectGitHub"
    }
}
