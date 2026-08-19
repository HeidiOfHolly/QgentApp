package com.example.qgent.ui.chat

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.bumptech.glide.Glide
import com.bumptech.glide.load.model.GlideUrl
import com.bumptech.glide.load.model.LazyHeaders
import com.example.qgent.QgentApp
import com.example.qgent.R
import com.example.qgent.data.SessionStore
import com.example.qgent.data.api.RetrofitClient
import com.example.qgent.data.model.GroupMemberDto
import com.example.qgent.data.repository.ChatRepository
import com.example.qgent.data.repository.UserRepository
import com.example.qgent.databinding.FragmentMemberListBinding
import com.example.qgent.databinding.ItemChatMemberBinding
import com.example.qgent.model.GroupType
import com.example.qgent.ui.personal.fillLinearLayout
import com.example.qgent.viewmodel.MainViewModel
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * 群成员列表页（群聊设置页「更多」跳转）：每个子项展示 头像 / 名称 / 身份，
 * 仅项目管理员与团长（后端兜底 PROJECT_ADMIN）可见删除键。
 * 身份：Agent 显示「Agent」，真人按项目成员角色显示 管理员/成员。
 * 复用 item_chat_member 行布局（与项目/团队详情页成员行同风格）。
 */
class ChatMemberListFragment : Fragment() {

    private var _binding: FragmentMemberListBinding? = null
    private val binding get() = _binding!!
    private val mainViewModel: MainViewModel by activityViewModels {
        (requireActivity().application as QgentApp).container.mainViewModelFactory
    }
    private val chatRepo: ChatRepository by lazy {
        (requireActivity().application as QgentApp).container.chatRepository
    }
    private val userRepository: UserRepository
        get() = (requireActivity().application as QgentApp).container.userRepository

    /** 当前用户是否项目管理员（团长由后端兜底 PROJECT_ADMIN） */
    private var isAdmin = false

    /** 当前需求群创建者 userId（PROJECT_MAIN 总群为 null）：群创建者也可管理成员 */
    private var groupCreatorId: String? = null

    /** 最近一次拉取的原始群成员（未合并 Agent），供 Agent 名单加载完成后重新合并渲染 */
    private var lastRawMembers = emptyList<GroupMemberDto>()

    /** 项目成员角色映射（userId → PROJECT_ADMIN / PROJECT_MEMBER），用于真人身份标签 */
    private var roleByUserId = emptyMap<String, String>()

    /** 团队团长（TEAM_OWNER）userId 集合：团长不可被移出分群 */
    private var teamOwnerIds = emptySet<String>()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentMemberListBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.ivBack.setOnClickListener { findNavController().navigateUp() }

        // Agent 名单异步加载完成后重新合并渲染成员（Agent 加载慢于群成员时避免漏显示 Agent）
        mainViewModel.agents.observe(viewLifecycleOwner) {
            remergeMembers()
        }

        loadAdminPermission()
    }

    /** 当前用户是否可管理成员（团长/管理员/分群创建者）：控制删除键显隐 */
    private fun canManageMembers(): Boolean =
        isAdmin || (groupCreatorId?.let { it == SessionStore.user()?.id } == true)

    /** 判定当前用户是否为项目管理员：仅团长/管理员/分群创建者显示删除键；随后加载成员 */
    private fun loadAdminPermission() {
        val projectId = mainViewModel.currentProjectId() ?: return
        viewLifecycleOwner.lifecycleScope.launch {
            isAdmin = userRepository.getProject(projectId).getOrNull()?.role == "PROJECT_ADMIN"
            loadGroupData()
        }
    }

    /** 加载群成员 + 项目成员角色 + 团队团长，合并 Agent 后渲染 */
    private fun loadGroupData() {
        val projectId = mainViewModel.currentProjectId() ?: return
        val groupId = arguments?.getString("groupId").orEmpty()
        if (groupId.isEmpty()) return
        viewLifecycleOwner.lifecycleScope.launch {
            chatRepo.getGroup(projectId, groupId).onSuccess { dto ->
                groupCreatorId = dto.createdBy
            }
            roleByUserId = userRepository.getProjectMembers(projectId).getOrNull().orEmpty()
                .associate { it.userId to it.role }
            // 团长 = 团队 TEAM_OWNER；团长被后端兜底为 PROJECT_ADMIN，但可能无 project_members 行，故从团队成员表独立识别
            teamOwnerIds = mainViewModel.currentTeamId()?.let { teamId ->
                userRepository.getTeamMembers(teamId).getOrNull().orEmpty()
                    .filter { it.role == "TEAM_OWNER" }.map { it.userId }.toSet()
            }.orEmpty()
            chatRepo.getMembers(projectId, groupId).onSuccess { dtos ->
                lastRawMembers = dtos
                renderMembers(mergeAgents(projectId, groupId, dtos))
            }
        }
    }

    /** 用缓存的原始群成员重新合并 Agent 并渲染（不重新拉接口） */
    private fun remergeMembers() {
        val projectId = mainViewModel.currentProjectId() ?: return
        val groupId = arguments?.getString("groupId").orEmpty()
        if (groupId.isEmpty() || lastRawMembers.isEmpty()) return
        renderMembers(mergeAgents(projectId, groupId, lastRawMembers))
    }

    /**
     * 合并团队 Agent 到群成员（与群聊设置页一致）：
     * 仅需求群合并；Agent 以团队 Agent 名单为准，按 id 去重（群成员里同 id 的 Agent 条目丢弃）。
     */
    private fun mergeAgents(projectId: String, groupId: String, dtos: List<GroupMemberDto>): List<GroupMemberDto> {
        val isMainGroup = mainViewModel.groups.value.orEmpty()
            .firstOrNull { it.id == groupId }
            ?.type == GroupType.PROJECT_MAIN
        if (isMainGroup) return dtos
        val allAgents = mainViewModel.agents.value.orEmpty()
        val teamAgents = allAgents
            .filter { it.status.name != "ARCHIVED" }
            .map { GroupMemberDto(id = it.id, nickname = it.name, displayName = it.name, avatar = it.avatar, memberType = "AGENT") }
        val agentIds = teamAgents.map { it.id }.toSet()
        // 群成员中与 Agent 名单同 id 的条目以名单为准；其余（真人 + 名单缺失的 Agent）保留
        return dtos.filter { it.id !in agentIds } + teamAgents
    }

    private fun renderMembers(members: List<GroupMemberDto>) {
        binding.tvMemberCount.text = getString(R.string.group_member_count, members.size)
        fillLinearLayout(binding.rvMembers, members, R.layout.item_chat_member) { view, member ->
            bindMemberRow(view, member)
        }
    }

    /** 成员行：头像 / 名称 / 身份标签；删除键仅对普通项目成员显示 */
    private fun bindMemberRow(view: View, member: GroupMemberDto) {
        val item = ItemChatMemberBinding.bind(view)
        item.tvMemberName.text = member.resolvedName
        // 身份：Agent 显示「Agent」，真人显示项目角色（管理员/成员）
        item.tvAgentTag.isVisible = member.isAgent
        item.tvMemberRole.isVisible = !member.isAgent && roleByUserId.containsKey(member.id)
        if (!member.isAgent) {
            item.tvMemberRole.text = roleLabel(roleByUserId[member.id])
            item.tvMemberRole.setTextColor(requireContext().getColor(
                if (roleByUserId[member.id] == "PROJECT_ADMIN") R.color.teal else R.color.text_secondary
            ))
        }
        // 头像：avatar 为空显示默认占位，否则 Glide 带鉴权头加载
        if (member.avatar.isNullOrBlank()) {
            item.ivMemberAvatar.setImageResource(R.drawable.ic_avatar_default)
        } else {
            Glide.with(item.ivMemberAvatar).load(authedGlideUrl(member.avatar)).into(item.ivMemberAvatar)
        }
        // 删除键：操作者须为 团长/管理员/分群创建者（canManageMembers），且目标为可移出的普通成员：
        // 项目管理员、分群创建者、团长（团队 TEAM_OWNER）与 Agent 均不可移除
        val protected = member.isAgent ||
            roleByUserId[member.id] == "PROJECT_ADMIN" ||
            member.id == groupCreatorId ||
            member.id in teamOwnerIds
        item.ivDeleteMember.isVisible = canManageMembers() && !protected
        item.ivDeleteMember.setOnClickListener { confirmRemoveMember(member) }
    }

    private fun roleLabel(role: String?): String = when (role) {
        "PROJECT_ADMIN" -> "管理员"
        else -> "成员"
    }

    /** 确认移出群成员（v2.0.6 §9）：弹确认后调接口，成功后刷新 */
    private fun confirmRemoveMember(member: GroupMemberDto) {
        val projectId = mainViewModel.currentProjectId() ?: return
        val groupId = arguments?.getString("groupId").orEmpty()
        if (groupId.isEmpty()) return
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("移出群聊")
            .setMessage("确定将 ${member.resolvedName} 移出该群？")
            .setNegativeButton("取消", null)
            .setPositiveButton("移出") { _, _ ->
                viewLifecycleOwner.lifecycleScope.launch {
                    chatRepo.removeGroupMember(projectId, groupId, member.id, UUID.randomUUID().toString())
                        .onSuccess {
                            Toast.makeText(requireContext(), "已移出 ${member.resolvedName}", Toast.LENGTH_SHORT).show()
                            loadGroupData()
                        }
                        .onFailure { e ->
                            Toast.makeText(requireContext(), "移出失败：${e.message}", Toast.LENGTH_LONG).show()
                        }
                }
            }
            .show()
    }

    private fun authedGlideUrl(uri: String): GlideUrl {
        val token = SessionStore.accessToken()
        val headers = LazyHeaders.Builder().apply {
            if (!token.isNullOrEmpty()) addHeader("Authorization", "Bearer $token")
        }.build()
        return GlideUrl(RetrofitClient.resolveMediaUrl(uri), headers)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
