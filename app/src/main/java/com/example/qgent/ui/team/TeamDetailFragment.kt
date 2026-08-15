package com.example.qgent.ui.team

import android.app.AlertDialog
import android.app.Dialog
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
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.qgent.QgentApp
import com.example.qgent.R
import com.example.qgent.data.model.InviteTeamMemberRequest
import com.example.qgent.data.model.TeamInvitationDto
import com.example.qgent.data.model.TeamMemberDto
import com.example.qgent.data.repository.UserRepository
import com.example.qgent.databinding.DialogInviteHistoryBinding
import com.example.qgent.databinding.DialogInviteMemberBinding
import com.example.qgent.databinding.FragmentTeamDetailBinding
import com.example.qgent.ui.github.GithubViewModel
import com.example.qgent.ui.tasks.TeamProjectAdapter
import com.example.qgent.ui.personal.bindCollapsibleSection
import com.example.qgent.ui.personal.setupRecyclerList
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
        // 我创建的团队 → 解散团队；我加入的团队 → 退出团队（默认按创建的兜底）
        val isOwner = arguments?.getBoolean(ARG_IS_OWNER, true) ?: true

        binding.ivBack.setOnClickListener { findNavController().navigateUp() }
        binding.tvTeamName.text = teamName

        // 右上角邀请记录：弹窗查看该团队近 7 天邀请，可撤销待接受邀请
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
        binding.btnRepository.setOnClickListener { showTodoToast() }

        setupRecyclerList(binding.rvProjects, projectAdapter)
        setupRecyclerList(binding.rvRepository, repositoryAdapter)
        setupRecyclerList(binding.rvMembers, memberAdapter)

        // 仅我创建的团队可移除成员；创建者行由适配器隐藏删除按钮
        memberAdapter.showDelete = isOwner
        memberAdapter.onDeleteMember = { member -> confirmRemoveMember(teamId, member) }

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

    /** 邀请记录弹窗：加载该团队邀请并支持撤销待接受邀请 */
    private fun showInviteHistoryDialog(teamId: String) {
        val dialogBinding = DialogInviteHistoryBinding.inflate(layoutInflater)
        val dialog = Dialog(requireContext()).apply {
            setContentView(dialogBinding.root)
            window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            window?.setLayout(
                (resources.displayMetrics.widthPixels * 0.9f).toInt(),
                WindowManager.LayoutParams.WRAP_CONTENT
            )
        }
        dialogBinding.btnClose.setOnClickListener { dialog.dismiss() }

        lateinit var inviteAdapter: InviteHistoryAdapter
        inviteAdapter = InviteHistoryAdapter { invitation ->
            confirmRevokeInvitation(teamId, invitation) {
                loadInvitationsInto(dialogBinding, inviteAdapter, teamId)
            }
        }
        dialogBinding.rvInvitations.layoutManager = LinearLayoutManager(requireContext())
        dialogBinding.rvInvitations.adapter = inviteAdapter

        loadInvitationsInto(dialogBinding, inviteAdapter, teamId)
        dialog.show()
    }

    /** 加载邀请记录并填充弹窗列表（空时显示空态） */
    private fun loadInvitationsInto(
        dialogBinding: DialogInviteHistoryBinding,
        adapter: InviteHistoryAdapter,
        teamId: String
    ) {
        viewLifecycleOwner.lifecycleScope.launch {
            userRepository.getTeamInvitations(teamId)
                .onSuccess { list ->
                    adapter.submitList(list)
                    dialogBinding.tvEmpty.isVisible = list.isEmpty()
                }
                .onFailure {
                    adapter.submitList(emptyList())
                    dialogBinding.tvEmpty.isVisible = true
                }
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

    /** 发送邀请请求：调 POST 接口，成功提示 */
    private fun sendInvitation(teamId: String, email: String) {
        viewLifecycleOwner.lifecycleScope.launch {
            userRepository.createInvitation(
                teamId,
                InviteTeamMemberRequest(email = email, role = "TEAM_MEMBER", expiresInDays = 7),
                UUID.randomUUID().toString()
            ).onSuccess {
                Toast.makeText(requireContext(), R.string.invite_sent_success, Toast.LENGTH_SHORT).show()
            }.onFailure {
                Toast.makeText(requireContext(), R.string.invite_send_failed, Toast.LENGTH_SHORT).show()
            }
        }
    }

    /** 确认移除团队成员：调 DELETE 接口后刷新成员列表 */
    private fun confirmRemoveMember(teamId: String, member: TeamMemberDto) {
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
                            userRepository.getTeamMembers(teamId).onSuccess { memberAdapter.submitList(it) }
                        }
                        .onFailure {
                            Toast.makeText(requireContext(), R.string.remove_member_failed, Toast.LENGTH_SHORT).show()
                        }
                }
            }
            .show()
    }

    /** 新建项目：与抽屉一致，跳转新建项目页并传入当前团队 id */
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
        const val ARG_IS_OWNER = "isOwner"
    }
}
