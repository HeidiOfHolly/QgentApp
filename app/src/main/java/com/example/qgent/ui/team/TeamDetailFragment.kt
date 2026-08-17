package com.example.qgent.ui.team

import android.app.AlertDialog
import android.app.Dialog
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Toast
import androidx.core.os.bundleOf
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.example.qgent.QgentApp
import com.example.qgent.R
import com.example.qgent.data.model.ApiException
import com.example.qgent.data.model.GitHubRepositoryDto
import com.example.qgent.ui.auth.TeamEntryActivity
import com.example.qgent.data.model.InviteTeamMemberRequest
import com.example.qgent.data.model.TeamInvitationDto
import com.example.qgent.data.model.TeamMemberDto
import com.example.qgent.data.repository.GitHubRepository
import com.example.qgent.data.repository.UserRepository
import com.example.qgent.databinding.DialogInviteHistoryBinding
import com.example.qgent.databinding.DialogInviteMemberBinding
import com.example.qgent.databinding.FragmentTeamDetailBinding
import com.example.qgent.databinding.ItemChatMemberBinding
import com.example.qgent.databinding.ItemInviteHistoryBinding
import com.example.qgent.databinding.ItemProjectBinding
import com.example.qgent.databinding.ItemRepositoryBinding
import com.example.qgent.ui.personal.bindCollapsibleSection
import com.example.qgent.ui.personal.fillLinearLayout
import com.example.qgent.viewmodel.MainViewModel
import kotlinx.coroutines.launch
import java.util.UUID

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

    private val userRepository: UserRepository
        get() = (requireActivity().application as QgentApp).container.userRepository
    private val githubRepository: GitHubRepository
        get() = (requireActivity().application as QgentApp).container.githubRepository


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

        // 仅我创建的团队（Team Owner）可使用邀请/仓库/新建项目管理；普通成员隐藏对应入口
        binding.btnInviteHistory.isVisible = isOwner
        binding.btnAddMember.isVisible = isOwner
        binding.btnAddProject.isVisible = isOwner
        binding.btnRepository.isVisible = isOwner

        // 右上角邀请记录：弹窗查看
        binding.btnInviteHistory.setOnClickListener {
            if (teamId.isNotEmpty()) showInviteHistoryDialog(teamId)
        }

        // 三个三角下拉分组：默认收起，点击头部展开 / 收起
        bindCollapsibleSection(binding.headerMembers, binding.ivArrowMembers, binding.sectionMembers)
        bindCollapsibleSection(binding.headerProjects, binding.ivArrowProjects, binding.sectionProjects)
        bindCollapsibleSection(binding.headerRepository, binding.ivArrowRepository, binding.sectionRepository)

        // 展开时分组顶部显示增加按钮
        binding.btnAddMember.setOnClickListener {
            if (teamId.isNotEmpty()) showInviteMemberDialog(teamId)
        }
        binding.btnAddProject.setOnClickListener { openNewProject(teamId) }
        binding.btnRepository.setOnClickListener { openGitHubAuthorize() }

        // 项目列表来自 MainViewModel（真实数据流）
        mainViewModel.projects.observe(viewLifecycleOwner) { projects ->
            fillLinearLayout(binding.rvProjects, projects, R.layout.item_project) { view, name ->
                ItemProjectBinding.bind(view).tvProjectName.text = name
            }
        }

        if (teamId.isNotEmpty()) {
            // 仓库列表 = 团队授权仓库（与 GitHub 页计数口径一致）
            loadAuthorizedRepositories(teamId)
            loadMembers(teamId, isOwner)
        }

        binding.btnDissolveTeam.text =
            getString(if (isOwner) R.string.dissolve_team else R.string.exit_team)
        binding.btnDissolveTeam.setOnClickListener { confirmLeaveOrDissolve(isOwner) }
    }

    /** 加载并填充团队成员列表；isOwner 控制是否显示删除按钮。
     *  排序：创建者（TEAM_OWNER）置顶，其余保持后端返回顺序（产品约定，见 docs/product-notes.md）。 */
    private fun loadMembers(teamId: String, isOwner: Boolean) {
        viewLifecycleOwner.lifecycleScope.launch {
            userRepository.getTeamMembers(teamId).onSuccess { members ->
                val sorted = members.sortedByDescending { it.role == "TEAM_OWNER" }
                fillLinearLayout(binding.rvMembers, sorted, R.layout.item_chat_member) { view, member ->
                    val item = ItemChatMemberBinding.bind(view)
                    item.tvMemberName.text = member.displayName
                    item.tvAgentTag.isVisible = member.role == "TEAM_OWNER"
                    item.tvAgentTag.text = "创建者"
                    // 仅我创建的团队可移除成员；创建者行不显示删除按钮
                    item.ivDeleteMember.isVisible = isOwner && member.role != "TEAM_OWNER"
                    item.ivDeleteMember.setOnClickListener { confirmRemoveMember(teamId, member, isOwner) }
                }
            }
        }
    }

    /**
     * 加载团队授权仓库并渲染列表，两套能力合并：
     * - 展示 AUTHORIZED 仓库 + 「已被项目绑定但授权已撤销」的死绑定仓库（REVOKED），后者标红并点击提示；
     * - 每个仓库标注「已绑定项目 / 未绑定项目」，未绑定且仍授权的提供撤销授权删除入口。
     */
    private fun loadAuthorizedRepositories(teamId: String) {
        viewLifecycleOwner.lifecycleScope.launch {
            val repos = githubRepository.getGithubRepositories(teamId).getOrNull().orEmpty()
            // 授权仓库 → 项目绑定列表：文档 §6 对 ProjectRepository.repositoryId 的语义与示例冲突
            //（§6.612 示例为 github_repositories.id，§6.440 通用规则又要求下游 repositoryId 表示绑定 id），
            // 改用两边都含的 providerRepositoryId（GitHub 仓库全局唯一数字 ID）关联，规避语义歧义导致的绑定状态错乱
            val bindingsByRepository = userRepository.getProjects(teamId).getOrNull().orEmpty()
                .flatMap { project ->
                    githubRepository.getProjectRepositories(project.id).getOrNull().orEmpty()
                        .map { it.providerRepositoryId to (project.id to it.id) }
                }
                .groupBy({ it.first }, { it.second })
            // 展示集：AUTHORIZED 全部展示；REVOKED 仅展示仍被项目绑定的死绑定（未绑定的已无意义，不展示）
            val displayRepos = repos.filter {
                it.authorizationStatus == "AUTHORIZED" ||
                    (it.authorizationStatus == "REVOKED" && bindingsByRepository[it.providerRepositoryId].orEmpty().isNotEmpty())
            }
            fillLinearLayout(binding.rvRepository, displayRepos, R.layout.item_repository) { view, repo ->
                val item = ItemRepositoryBinding.bind(view)
                item.tvRepositoryName.text = repo.fullName
                val bindings = bindingsByRepository[repo.providerRepositoryId].orEmpty()
                val bound = bindings.isNotEmpty()
                val revoked = repo.authorizationStatus == "REVOKED"
                item.tvBoundStatus.text = getString(
                    if (bound) R.string.github_repo_bound else R.string.github_repo_unbound
                )
                item.tvRepositoryStatus.isVisible = revoked
                if (revoked) {
                    // 死绑定标红，点击提示原因
                    item.tvRepositoryName.setTextColor(
                        androidx.core.content.ContextCompat.getColor(requireContext(), R.color.exit_red)
                    )
                    item.root.setOnClickListener {
                        Toast.makeText(requireContext(), R.string.repo_revoked_hint, Toast.LENGTH_LONG).show()
                    }
                } else {
                    // 未绑定项目且仍授权 → 提供撤销授权删除入口；已绑定保护项目引用，不显示
                    item.ivDeleteRepository.isVisible = !bound
                    item.ivDeleteRepository.setOnClickListener {
                        confirmDeleteRepository(teamId, repo)
                    }
                }
            }
        }
    }

    /** 确认撤销仓库授权：从团队授权列表移除该仓库 */
    private fun confirmDeleteRepository(teamId: String, repo: GitHubRepositoryDto) {
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.github_repo_delete)
            .setMessage(getString(R.string.github_repo_delete_confirm, repo.fullName))
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.github_repo_delete) { _, _ ->
                deleteRepository(teamId, repo)
            }
            .show()
    }

    /** 撤销仓库授权（DELETE /teams/{teamId}/integrations/github/repositories/{repositoryId}）并刷新 */
    private fun deleteRepository(teamId: String, repo: GitHubRepositoryDto) {
        viewLifecycleOwner.lifecycleScope.launch {
            githubRepository.revokeGithubRepository(teamId, repo.id, UUID.randomUUID().toString())
                .onSuccess {
                    Toast.makeText(requireContext(), R.string.github_repo_delete_success, Toast.LENGTH_SHORT).show()
                    loadAuthorizedRepositories(teamId)
                }
                .onFailure { e ->
                    Toast.makeText(
                        requireContext(),
                        e.message ?: getString(R.string.github_repo_delete_failed),
                        Toast.LENGTH_SHORT
                    ).show()
                }
        }
    }

    /** 底部操作：我创建的团队 → 解散（契约 §5.1）；我加入的团队 → 退出（待接入） */
    private fun confirmLeaveOrDissolve(isOwner: Boolean) {
        val titleRes = if (isOwner) R.string.dissolve_team else R.string.exit_team
        val confirmRes = if (isOwner) R.string.dissolve_team_confirm else R.string.exit_team_confirm
        if (!isOwner) {
            Toast.makeText(requireContext(), R.string.exit_team_placeholder, Toast.LENGTH_SHORT).show()
            return
        }
        AlertDialog.Builder(requireContext())
            .setTitle(titleRes)
            .setMessage(confirmRes)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(titleRes) { _, _ ->
                dissolveTeam(arguments?.getString(ARG_TEAM_ID).orEmpty())
            }
            .show()
    }

    /** 解散团队：调用契约 §5.1 DELETE /teams/{teamId}，成功后刷新团队列表并返回上一页 */
    private fun dissolveTeam(teamId: String) {
        if (teamId.isEmpty()) return
        viewLifecycleOwner.lifecycleScope.launch {
            userRepository.deleteTeam(teamId, UUID.randomUUID().toString())
                .onSuccess {
                    Toast.makeText(requireContext(), R.string.dissolve_team_success, Toast.LENGTH_SHORT).show()
                    routeAfterDissolve()
                }
                .onFailure { e ->
                    Toast.makeText(
                        requireContext(),
                        e.message ?: getString(R.string.dissolve_team_failed),
                        Toast.LENGTH_SHORT
                    ).show()
                }
        }
    }

    /** 解散后路由：重新拉取团队列表，无其他团队 → 团队引导页（创建/加入）；有 → 返回团队管理页 */
    private fun routeAfterDissolve() {
        viewLifecycleOwner.lifecycleScope.launch {
            userRepository.getTeams()
                .onSuccess { teams ->
                    if (teams.isEmpty()) {
                        startActivity(Intent(requireContext(), TeamEntryActivity::class.java))
                        requireActivity().finish()
                    } else {
                        mainViewModel.refreshTeams()
                        findNavController().popBackStack()
                    }
                }
                .onFailure {
                    mainViewModel.refreshTeams()
                    findNavController().popBackStack()
                }
        }
    }

    /** 邀请记录弹窗：加载该团队邀请并支持撤销待接受邀请 */
    private fun showInviteHistoryDialog(teamId: String) {
        val dialogBinding = DialogInviteHistoryBinding.inflate(layoutInflater)
        val dialog = Dialog(requireContext()).apply {
            setContentView(dialogBinding.root)
            window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            window?.setLayout(
                (resources.displayMetrics.widthPixels * 0.9f).toInt(),
                (resources.displayMetrics.heightPixels * 0.8f).toInt()
            )
        }
        dialogBinding.btnClose.setOnClickListener { dialog.dismiss() }

        // 两个三角下拉分组：待接收 / 已处理，点击头部收起 / 展开
        bindCollapsibleSection(dialogBinding.headerPending, dialogBinding.ivArrowPending, dialogBinding.rvPending)
        bindCollapsibleSection(dialogBinding.headerProcessed, dialogBinding.ivArrowProcessed, dialogBinding.rvProcessed)

        loadInvitationsInto(dialogBinding, teamId)
        dialog.show()
    }

    /** 加载邀请记录并按状态分组填充：PENDING → 待接收组，其余（已接受/已撤销/已过期）→ 已处理组 */
    private fun loadInvitationsInto(
        dialogBinding: DialogInviteHistoryBinding,
        teamId: String
    ) {
        viewLifecycleOwner.lifecycleScope.launch {
            userRepository.getTeamInvitations(teamId)
                .onSuccess { list ->
                    val pending = list.filter { it.status == "PENDING" }
                    val processed = list.filter { it.status != "PENDING" }
                    fillLinearLayout(dialogBinding.rvPending, pending, R.layout.item_invite_history) { view, invitation ->
                        bindInviteItem(view, teamId, invitation) {
                            loadInvitationsInto(dialogBinding, teamId)
                        }
                    }
                    fillLinearLayout(dialogBinding.rvProcessed, processed, R.layout.item_invite_history) { view, invitation ->
                        bindInviteItem(view, teamId, invitation) {
                            loadInvitationsInto(dialogBinding, teamId)
                        }
                    }
                    dialogBinding.tvEmpty.isVisible = list.isEmpty()
                }
                .onFailure {
                    dialogBinding.rvPending.removeAllViews()
                    dialogBinding.rvProcessed.removeAllViews()
                    dialogBinding.tvEmpty.isVisible = true
                }
        }
    }

    /** 填充单个邀请记录项：邮箱 + 状态；仅待接收显示撤销按钮，撤销成功回调 onRevoked 刷新弹窗 */
    private fun bindInviteItem(
        view: View,
        teamId: String,
        invitation: TeamInvitationDto,
        onRevoked: () -> Unit
    ) {
        val item = ItemInviteHistoryBinding.bind(view)
        item.tvEmail.text = invitation.email
        item.tvStatus.text = when (invitation.status) {
            "PENDING" -> getString(R.string.invite_status_pending)
            "ACCEPTED" -> getString(R.string.invite_status_accepted)
            "REVOKED" -> getString(R.string.invite_status_revoked)
            "EXPIRED" -> getString(R.string.invite_status_expired)
            else -> invitation.status
        }
        // 仅 PENDING（待接收）可撤销
        item.btnRevoke.isVisible = invitation.status == "PENDING"
        item.btnRevoke.setOnClickListener {
            confirmRevokeInvitation(teamId, invitation, onRevoked)
        }
    }

    /** 确认撤销邀请，成功后回调以刷新弹窗列表 */
    private fun confirmRevokeInvitation(
        teamId: String,
        invitation: TeamInvitationDto,
        onRevoked: () -> Unit
    ) {
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.revoke_invitation)
            .setMessage(R.string.revoke_invitation_confirm)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.revoke_invitation) { _, _ ->
                viewLifecycleOwner.lifecycleScope.launch {
                    userRepository.revokeInvitation(teamId, invitation.id, UUID.randomUUID().toString())
                        .onSuccess {
                            Toast.makeText(requireContext(), R.string.revoke_invitation_success, Toast.LENGTH_SHORT).show()
                            onRevoked()
                        }
                        .onFailure {
                            Toast.makeText(requireContext(), R.string.revoke_invitation_failed, Toast.LENGTH_SHORT).show()
                        }
                }
            }
            .show()
    }

    /** 邀请成员弹窗：输入邮箱后发送邀请请求 */
    private fun showInviteMemberDialog(teamId: String) {
        val dialogBinding = DialogInviteMemberBinding.inflate(layoutInflater)
        val dialog = Dialog(requireContext()).apply {
            setContentView(dialogBinding.root)
            window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            window?.setLayout(
                (resources.displayMetrics.widthPixels * 0.85f).toInt(),
                WindowManager.LayoutParams.WRAP_CONTENT
            )
        }

        dialogBinding.etEmail.doAfterTextChanged {
            if (dialogBinding.emailLayout.error != null) dialogBinding.emailLayout.error = null
        }

        dialogBinding.btnSend.setOnClickListener {
            val email = dialogBinding.etEmail.text?.toString()?.trim().orEmpty()
            when {
                email.isEmpty() -> dialogBinding.emailLayout.error = getString(R.string.error_invite_email_required)
                !android.util.Patterns.EMAIL_ADDRESS.matcher(email).matches() ->
                    dialogBinding.emailLayout.error = getString(R.string.error_invite_email_invalid)
                else -> {
                    dialog.dismiss()
                    sendInvitation(teamId, email)
                }
            }
        }

        dialog.show()
    }

    /** 发送邀请请求：调 POST 接口；失败时区分网络错误与后端业务错误（如邮箱已加入团队） */
    private fun sendInvitation(teamId: String, email: String) {
        viewLifecycleOwner.lifecycleScope.launch {
            userRepository.createInvitation(
                teamId,
                InviteTeamMemberRequest(email = email, role = "TEAM_MEMBER", expiresInDays = 7),
                UUID.randomUUID().toString()
            ).onSuccess {
                Toast.makeText(requireContext(), R.string.invite_sent_success, Toast.LENGTH_SHORT).show()
            }.onFailure { e ->
                val message = when {
                    // 网络层异常：无法连接/超时等，与后端业务错误区分
                    e is java.io.IOException -> getString(R.string.invite_send_network_error)
                    // 后端业务错误：直接透出后端中文提示（如「该邮箱已加入团队」），后端未带提示时用通用文案
                    e is ApiException -> e.message?.takeIf { it.isNotBlank() } ?: getString(R.string.invite_send_failed)
                    else -> getString(R.string.invite_send_failed)
                }
                Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
            }
        }
    }

    /** 确认移除团队成员：调 DELETE 接口后刷新成员列表 */
    private fun confirmRemoveMember(teamId: String, member: TeamMemberDto, isOwner: Boolean) {
        if (teamId.isEmpty()) return
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.remove_member_title)
            .setMessage(getString(R.string.remove_member_confirm, member.displayName))
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.remove_member) { _, _ ->
                viewLifecycleOwner.lifecycleScope.launch {
                    userRepository.removeTeamMember(teamId, member.userId, UUID.randomUUID().toString())
                        .onSuccess {
                            Toast.makeText(requireContext(), R.string.remove_member_success, Toast.LENGTH_SHORT).show()
                            loadMembers(teamId, isOwner)
                        }
                        .onFailure {
                            Toast.makeText(requireContext(), R.string.remove_member_failed, Toast.LENGTH_SHORT).show()
                        }
                }
            }
            .show()
    }

    /** 新增仓库：跳转 GitHub 授权页，传入当前团队 id / 名称 */
    private fun openGitHubAuthorize() {
        val teamId = arguments?.getString(ARG_TEAM_ID).orEmpty()
        val teamName = arguments?.getString(ARG_TEAM_NAME).orEmpty()
        findNavController().navigate(
            R.id.githubAuthorizeFragment,
            bundleOf("teamId" to teamId, "teamName" to teamName)
        )
    }

    /** 新建项目：与抽屉一致，跳转新建项目页并传入当前团队 id（无创建权限时拦截提示） */
    private fun openNewProject(teamId: String) {
        if (teamId.isEmpty()) {
            Toast.makeText(requireContext(), R.string.new_project_missing_team, Toast.LENGTH_SHORT).show()
            return
        }
        if (!mainViewModel.canCreateProject(arguments?.getString(ARG_TEAM_NAME).orEmpty())) {
            Toast.makeText(requireContext(), R.string.new_project_no_permission, Toast.LENGTH_SHORT).show()
            return
        }
        findNavController().navigate(R.id.newProjectFragment, bundleOf("teamId" to teamId))
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
