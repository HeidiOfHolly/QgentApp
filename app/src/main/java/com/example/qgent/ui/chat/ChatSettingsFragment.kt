package com.example.qgent.ui.chat

import android.app.Dialog
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import com.bumptech.glide.Glide
import com.bumptech.glide.load.model.GlideUrl
import com.bumptech.glide.load.model.LazyHeaders
import com.example.qgent.QgentApp
import com.example.qgent.R
import com.example.qgent.data.SessionStore
import com.example.qgent.data.api.RetrofitClient
import com.example.qgent.data.model.GroupMemberDto
import com.example.qgent.data.model.GroupMessageDto
import com.example.qgent.data.repository.ChatRepository
import com.example.qgent.data.repository.UserRepository
import com.example.qgent.databinding.BottomSheetCreateGroupBinding
import com.example.qgent.databinding.DialogSearchMessagesBinding
import com.example.qgent.databinding.FragmentChatSettingsBinding
import com.example.qgent.viewmodel.MainViewModel
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.launch
import java.util.UUID

class ChatSettingsFragment : Fragment() {

    private var _binding: FragmentChatSettingsBinding? = null
    private val binding get() = _binding!!
    private val mainViewModel: MainViewModel by activityViewModels {
        (requireActivity().application as QgentApp).container.mainViewModelFactory
    }
    private val chatRepo: ChatRepository by lazy {
        (requireActivity().application as QgentApp).container.chatRepository
    }
    private val userRepository: UserRepository
        get() = (requireActivity().application as QgentApp).container.userRepository

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentChatSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.btnBack.setOnClickListener { findNavController().popBackStack() }

        loadGroupData()
        loadAdminPermission()

        // 添加成员：从团队成员中拉人进当前项目（与群列表页一致，含身份选择）
        binding.btnAddMember.setOnClickListener { showAddMemberDialog() }

        // 查看聊天记录：弹出搜索弹窗，按关键词过滤消息
        binding.btnViewHistory.setOnClickListener { showSearchDialog() }

        // 退出群聊：先弹确认，确认后调接口
        binding.btnExitGroup.setOnClickListener { confirmExitGroup() }
    }

    /** 判定当前用户是否为项目管理员（团长由后端兜底 PROJECT_ADMIN）：仅团长/管理员可见添加成员 */
    private fun loadAdminPermission() {
        val projectId = mainViewModel.currentProjectId() ?: return
        viewLifecycleOwner.lifecycleScope.launch {
            val isAdmin = userRepository.getProject(projectId).getOrNull()?.role == "PROJECT_ADMIN"
            binding.btnAddMember.isVisible = isAdmin
        }
    }

    /**
     * 添加成员：列出团队中尚未加入当前项目的成员，勾选后可设置身份（项目成员/项目管理员），
     * POST 加入后选管理员的再 PATCH 升级（§5.2）。与群列表页「添加成员」共用交互。
     */
    private fun showAddMemberDialog() {
        val projectId = mainViewModel.currentProjectId() ?: run {
            Toast.makeText(requireContext(), R.string.add_member_missing_project, Toast.LENGTH_SHORT).show()
            return
        }
        val teamId = mainViewModel.currentTeamId() ?: run {
            Toast.makeText(requireContext(), R.string.new_project_missing_team, Toast.LENGTH_SHORT).show()
            return
        }
        val dialog = BottomSheetDialog(requireContext())
        val sheetBinding = BottomSheetCreateGroupBinding.inflate(layoutInflater)
        dialog.setContentView(sheetBinding.root)
        sheetBinding.tvSheetTitle.text = getString(R.string.action_add_member)
        sheetBinding.tvSheetSubtitle.text = getString(R.string.add_member_subtitle)
        sheetBinding.etGroupDescription.visibility = View.GONE
        sheetBinding.btnSelectAll.visibility = View.GONE
        sheetBinding.etGroupName.hint = getString(R.string.add_member_select_hint)

        lateinit var pickAdapter: GroupMemberPickAdapter
        pickAdapter = GroupMemberPickAdapter(
            onItemClick = { pickAdapter.toggle(it) },
            onRoleClick = { pickAdapter.toggleRole(it) }
        )
        sheetBinding.rvGroupMembers.layoutManager = LinearLayoutManager(requireContext())
        sheetBinding.rvGroupMembers.adapter = pickAdapter

        viewLifecycleOwner.lifecycleScope.launch {
            val teamMembers = userRepository.getTeamMembers(teamId).getOrElse {
                Toast.makeText(requireContext(), R.string.add_member_failed, Toast.LENGTH_SHORT).show()
                dialog.dismiss()
                return@launch
            }
            // 当前用户始终视为已在项目内（后端 GET /projects/{id}/members 可能不返回自己/Team Owner 兜底无成员行），
            // 否则候选 = 团队成员 − 项目成员 会把「自己」误当成唯一可添加的人
            val existingIds = userRepository.getProjectMembers(projectId)
                .getOrNull()?.map { it.userId }?.toSet().orEmpty() + listOfNotNull(SessionStore.user()?.id)
            val candidates = teamMembers.filter { it.userId !in existingIds }
            if (candidates.isEmpty()) {
                Toast.makeText(requireContext(), R.string.add_member_empty, Toast.LENGTH_SHORT).show()
                dialog.dismiss()
                return@launch
            }
            pickAdapter.submitList(
                candidates.map { GroupMemberPick(it.userId, it.displayName, "PROJECT_MEMBER") }
            )
        }

        sheetBinding.btnSend.setOnClickListener {
            val selected = pickAdapter.checkedIds()
            if (selected.isEmpty()) {
                Toast.makeText(requireContext(), R.string.add_member_empty, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val roles = pickAdapter.roleById()
            dialog.dismiss()
            addMembers(projectId, selected, roles)
        }
        sheetBinding.btnCancel.setOnClickListener { dialog.dismiss() }
        dialog.show()
    }

    /** 逐个将选中成员加入项目：POST 加入后按所选身份决定是否 PATCH 升级，汇总成功数量提示 */
    private fun addMembers(projectId: String, userIds: List<String>, roles: Map<String, String>) {
        if (userIds.isEmpty()) return
        viewLifecycleOwner.lifecycleScope.launch {
            var added = 0
            userIds.forEach { userId ->
                userRepository.addProjectMember(projectId, userId, UUID.randomUUID().toString())
                    .onSuccess {
                        added++
                        if (roles[userId] == "PROJECT_ADMIN") {
                            userRepository.updateProjectMemberRole(projectId, userId, "PROJECT_ADMIN", UUID.randomUUID().toString())
                        }
                    }
            }
            if (added == 0) {
                Toast.makeText(requireContext(), R.string.add_member_failed, Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(requireContext(), getString(R.string.add_member_success, added), Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun loadGroupData() {
        val projectId = mainViewModel.currentProjectId()
        val groupId = arguments?.getString("groupId").orEmpty()

        if (projectId == null || groupId.isEmpty()) {
            renderMembers(emptyList())
            return
        }

        viewLifecycleOwner.lifecycleScope.launch {
            chatRepo.getGroup(projectId, groupId).onSuccess { dto ->
                binding.tvGroupName.text = dto.title
            }
            chatRepo.getMembers(projectId, groupId).onSuccess { dtos ->
                Log.d("ChatSettings", "getMembers raw: $dtos")
                renderMembers(dtos)
            }
        }
    }

    private fun renderMembers(members: List<GroupMemberDto>) {
        binding.containerMembers.removeAllViews()
        binding.tvMemberCount.text = getString(R.string.group_member_count, members.size)
        for (member in members) {
            val row = layoutInflater.inflate(R.layout.item_chat_member, binding.containerMembers, false)
            val name = member.resolvedName
            row.findViewById<TextView>(R.id.tvMemberName)?.text = name
            // 群成员 DTO 含 memberType（USER/AGENT），Agent 显示标签（文档 §7）
            row.findViewById<TextView>(R.id.tvAgentTag)?.isVisible = member.isAgent
            // 头像：avatar 为空显示默认占位，否则 Glide 带鉴权头加载
            val ivAvatar = row.findViewById<ImageView>(R.id.ivMemberAvatar)
            if (member.avatar.isNullOrBlank()) {
                ivAvatar?.setImageResource(R.drawable.ic_avatar_default)
            } else {
                ivAvatar?.let { Glide.with(it).load(authedGlideUrl(member.avatar)).into(it) }
            }
            binding.containerMembers.addView(row)
        }
    }

    /** 聊天记录搜索弹窗：加载当前群消息后按关键词本地过滤（后端暂无消息搜索接口） */
    private fun showSearchDialog() {
        val dialog = Dialog(requireContext())
        val searchBinding = DialogSearchMessagesBinding.inflate(layoutInflater)
        dialog.setContentView(searchBinding.root)
        dialog.window?.setLayout(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT
        )

        val adapter = SearchMessageAdapter()
        searchBinding.rvSearchResults.layoutManager = LinearLayoutManager(requireContext())
        searchBinding.rvSearchResults.adapter = adapter

        val projectId = mainViewModel.currentProjectId()
        val groupId = arguments?.getString("groupId").orEmpty()
        var allMessages = emptyList<GroupMessageDto>()
        if (projectId != null && groupId.isNotEmpty()) {
            viewLifecycleOwner.lifecycleScope.launch {
                chatRepo.getMessages(projectId, groupId).onSuccess { dtos -> allMessages = dtos }
            }
        }

        searchBinding.etSearchQuery.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val q = s?.toString()?.trim().orEmpty()
                val results = if (q.isEmpty()) emptyList() else allMessages.filter {
                    it.type == "TEXT" && it.content?.text?.contains(q, ignoreCase = true) == true
                }
                adapter.submitList(results)
                searchBinding.tvSearchEmpty.isVisible = results.isEmpty()
            }
        })

        searchBinding.btnSearchClose.setOnClickListener { dialog.dismiss() }
        searchBinding.btnSearchCancel.setOnClickListener { dialog.dismiss() }
        dialog.show()
    }

    private fun confirmExitGroup() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.exit_group)
            .setMessage("退出后你将不再接收该群消息")
            .setNegativeButton("取消", null)
            .setPositiveButton("退出") { _, _ -> exitGroup() }
            .show()
    }

    private fun exitGroup() {
        val projectId = mainViewModel.currentProjectId()
        val groupId = arguments?.getString("groupId").orEmpty()
        if (projectId == null || groupId.isEmpty()) return

        viewLifecycleOwner.lifecycleScope.launch {
            chatRepo.leaveGroup(projectId, groupId, UUID.randomUUID().toString())
                .onSuccess {
                    Toast.makeText(requireContext(), "已退出群聊", Toast.LENGTH_SHORT).show()
                    // 回到群列表页；列表页 onResume 会自动刷新，移除已退出的群
                    findNavController().popBackStack(R.id.chatListFragment, false)
                }
                .onFailure { e ->
                    Toast.makeText(requireContext(), "退出失败：${e.message}", Toast.LENGTH_LONG).show()
                }
        }
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
