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
import android.widget.LinearLayout
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

        // 查看聊天记录：弹出搜索弹窗，按关键词过滤消息
        binding.btnViewHistory.setOnClickListener { showSearchDialog() }

        // 退出群聊：先弹确认，确认后调接口
        binding.btnExitGroup.setOnClickListener { confirmExitGroup() }
    }

    /** 当前用户是否项目管理员（团长由后端兜底 PROJECT_ADMIN）：控制成员网格的 添加/删除 控件显隐 */
    private var isAdmin = false

    /** 判定当前用户是否为项目管理员：仅团长/管理员显示成员网格的 添加/删除 控件 */
    private fun loadAdminPermission() {
        val projectId = mainViewModel.currentProjectId() ?: return
        viewLifecycleOwner.lifecycleScope.launch {
            isAdmin = userRepository.getProject(projectId).getOrNull()?.role == "PROJECT_ADMIN"
            // 成员网格已渲染时重新填充，刷新控件格
            loadGroupData()
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

        // 刷新时重置删除模式，避免重渲染残留
        deleteMode = false

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
        // 网格 4 列 × 3 行：成员占前 N 格，末尾两个空位放 添加/删除 控件，其余空格位占位，
        // 一排不足 4 个时剩余格子留空（子项靠左）
        val cells = mutableListOf<View>()
        for (member in members.take(10)) {
            val cell = layoutInflater.inflate(R.layout.item_chat_member_grid, binding.containerMembers, false)
            bindMemberCell(cell, member)
            cells.add(cell)
        }
        // 非删除模式下，管理员/团长在末尾显示 添加/删除 控件格
        if (isAdmin && !deleteMode) {
            cells.add(buildControlCell(R.drawable.ic_add, "添加") { showAddMemberDialog() })
            cells.add(buildControlCell(R.drawable.ic_close, "删除") { enterDeleteMode() })
        }
        renderMemberGrid(cells)
    }

    /** 按 4 列固定 3 行填充：每行一个横向 LinearLayout（子项等宽、靠左），不足补空格位 */
    private fun renderMemberGrid(cells: List<View>) {
        binding.containerMembers.removeAllViews()
        // 每格宽 = 屏宽 / 4
        val cellWidth = resources.displayMetrics.widthPixels / 4
        for (row in 0 until 3) {
            val rowLayout = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            }
            for (col in 0 until 4) {
                val index = row * 4 + col
                val cell = cells.getOrNull(index)
                if (cell != null) {
                    cell.layoutParams = LinearLayout.LayoutParams(cellWidth, ViewGroup.LayoutParams.WRAP_CONTENT)
                    rowLayout.addView(cell)
                } else {
                    // 空格位占位：保持 4 列布局与靠左
                    rowLayout.addView(View(requireContext()), LinearLayout.LayoutParams(cellWidth, 1))
                }
            }
            binding.containerMembers.addView(rowLayout)
        }
    }

    /** 成员网格子项：头像 / 名称 / 身份（Agent 显示标签）；删除模式显示删除角标 */
    private fun bindMemberCell(cell: View, member: GroupMemberDto) {
        val name = member.resolvedName
        cell.findViewById<TextView>(R.id.tvMemberName)?.text = name
        // 身份：Agent 显示「Agent」，真人隐藏
        cell.findViewById<TextView>(R.id.tvMemberRole)?.isVisible = member.isAgent
        // 头像：avatar 为空显示默认占位，否则 Glide 带鉴权头加载
        val ivAvatar = cell.findViewById<ImageView>(R.id.ivMemberAvatar)
        if (member.avatar.isNullOrBlank()) {
            ivAvatar?.setImageResource(R.drawable.ic_avatar_default)
        } else {
            ivAvatar?.let { Glide.with(it).load(authedGlideUrl(member.avatar)).into(it) }
        }
        // 删除角标：仅删除模式显示
        val ivDelete = cell.findViewById<ImageView>(R.id.ivDeleteMember)
        ivDelete?.isVisible = deleteMode
        ivDelete?.setOnClickListener { confirmRemoveMember(member) }
    }

    private var deleteMode = false

    /** 构建一个控件格：圆形图标 + 底部文字，点击回调（layoutParams 由 renderMemberGrid 统一设置） */
    private fun buildControlCell(iconRes: Int, label: String, onClick: () -> Unit): View {
        val cell = layoutInflater.inflate(R.layout.item_chat_member_grid, binding.containerMembers, false)
        cell.findViewById<ImageView>(R.id.ivMemberAvatar)?.apply {
            setImageResource(iconRes)
            setBackgroundResource(R.drawable.bg_oval)
            backgroundTintList = android.content.res.ColorStateList.valueOf(
                androidx.core.content.ContextCompat.getColor(requireContext(), R.color.bg_chat_pinned)
            )
        }
        cell.findViewById<TextView>(R.id.tvMemberName)?.text = label
        cell.findViewById<TextView>(R.id.tvMemberRole)?.isVisible = false
        cell.setOnClickListener { onClick() }
        return cell
    }

    /** 进入删除模式：成员子项显示删除角标（重新渲染网格，去掉控件格、成员格显示角标） */
    private fun enterDeleteMode() {
        deleteMode = true
        val projectId = mainViewModel.currentProjectId() ?: run {
            deleteMode = false
            return
        }
        val groupId = arguments?.getString("groupId").orEmpty()
        if (projectId == null || groupId.isEmpty()) {
            deleteMode = false
            return
        }
        viewLifecycleOwner.lifecycleScope.launch {
            chatRepo.getMembers(projectId, groupId).getOrNull()?.let { renderMembers(it) }
        }
    }

    /** 确认删除群成员（v2.0.6 §9）：弹确认后调接口，成功后刷新 */
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
                            deleteMode = false
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
