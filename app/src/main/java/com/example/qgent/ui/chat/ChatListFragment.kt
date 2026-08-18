package com.example.qgent.ui.chat

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.PopupMenu
import android.widget.Toast
import androidx.core.os.bundleOf
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.qgent.MainActivity
import com.example.qgent.QgentApp
import com.example.qgent.R
import com.example.qgent.data.SessionStore
import com.example.qgent.data.model.TeamMemberDto
import com.example.qgent.data.repository.ChatRepository
import com.example.qgent.data.repository.UserRepository
import com.example.qgent.data.sse.ProjectEventStream
import com.example.qgent.data.sse.SseEventType
import com.example.qgent.databinding.BottomSheetCreateGroupBinding
import com.example.qgent.databinding.FragmentChatListBinding
import com.example.qgent.model.ChatGroup
import com.example.qgent.viewmodel.MainViewModel
import com.google.android.material.bottomsheet.BottomSheetDialog
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.UUID

class ChatListFragment : Fragment() {

    private var _binding: FragmentChatListBinding? = null
    private val binding get() = _binding!!
    private val mainViewModel: MainViewModel by activityViewModels {
        (requireActivity().application as QgentApp).container.mainViewModelFactory
    }

    private val userRepository: UserRepository
        get() = (requireActivity().application as QgentApp).container.userRepository

    /** 项目级 SSE 事件流：收到事件立即刷新群列表（摘要/未读/新消息），替代部分轮询延迟 */
    private val eventStream: ProjectEventStream
        get() = (requireActivity().application as QgentApp).container.projectEventStream

    private var pollingJob: Job? = null
    private var eventStreamJob: Job? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentChatListBinding.inflate(inflater, container, false)
        return binding.root
    }

    // 从详情页返回时刷新最新消息摘要（图片/文件显示 [图片]/[文件]）
    override fun onResume() {
        super.onResume()
        mainViewModel.refreshGroups()
        mainViewModel.refreshUnreadInvitations()
        startPolling()
        startEventStream()
    }

    override fun onPause() {
        super.onPause()
        stopPolling()
        stopEventStream()
    }

    /**
     * 项目级 SSE 事件流（文档 §12.1 + message.created 补充）：
     * 仅当事件影响群列表（新消息、群变更、成员变动）时刷新群列表摘要/未读，
     * 任务/Diff 类事件不触发全量刷新，避免事件风暴导致列表频繁重建。
     * 轮询仍保留作为无事件时的兜底。
     */
    private fun startEventStream() {
        val projectId = mainViewModel.currentProjectId() ?: return
        eventStream.startProject(projectId)
        if (eventStreamJob == null) {
            eventStreamJob = viewLifecycleOwner.lifecycleScope.launch {
                eventStream.events.collect { event ->
                    when (event.type) {
                        SseEventType.MESSAGE_CREATED,
                        SseEventType.GROUP_CREATED,
                        SseEventType.GROUP_UPDATED,
                        SseEventType.GROUP_ARCHIVED,
                        SseEventType.GROUP_MEMBER_UPDATED,
                        SseEventType.PROJECT_MEMBER_ADDED -> mainViewModel.refreshGroups()
                        else -> Unit // 任务/Diff/交付等事件不影响群列表，跳过
                    }
                }
            }
        }
    }

    private fun stopEventStream() {
        eventStreamJob?.cancel()
        eventStreamJob = null
        eventStream.stop()
    }

    /** 轮询群聊列表：后端暂无聊天推送，用定时 refreshGroups 兜底实现别人发消息红点实时显示 */
    private fun startPolling() {
        if (pollingJob?.isActive == true) return
        pollingJob = viewLifecycleOwner.lifecycleScope.launch {
            while (true) {
                delay(POLL_INTERVAL_MS)
                mainViewModel.refreshGroups()
            }
        }
    }

    private fun stopPolling() {
        pollingJob?.cancel()
        pollingJob = null
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // 头像 → 打开个人中心抽屉
        binding.btnAvatar.setOnClickListener {
            (activity as? MainActivity)?.openDrawer()
        }

        // 搜索 → 进入搜索页
        binding.btnSearch.setOnClickListener {
            findNavController().navigate(R.id.action_chatList_to_search)
        }

        // 加号 → 弹出操作列表
        binding.btnAdd.setOnClickListener { showMoreMenu() }

        binding.tvUserName.text = SessionStore.user()?.displayName ?: getString(R.string.user_name_placeholder)
        mainViewModel.currentTeam.observe(viewLifecycleOwner) { team ->
            binding.tvTeamName.text = team
        }

        // 未读团队邀请 → 头像右上角红点
        mainViewModel.unreadInvitations.observe(viewLifecycleOwner) { hasUnread ->
            binding.ivInviteBadge.isVisible = hasUnread
        }

        binding.rvChatList.layoutManager = LinearLayoutManager(requireContext())
        binding.rvChatList.addItemDecoration(
            DividerItemDecoration(requireContext(), DividerItemDecoration.VERTICAL)
        )

        val adapter = ChatListAdapter(
            items = emptyList(),
            onGroupClick = { group ->
                mainViewModel.markGroupRead(group.id)
                findNavController().navigate(
                    R.id.action_chatList_to_chatDetail,
                    bundleOf("groupName" to group.name, "groupId" to group.id)
                )
            },
            onGroupLongClick = { anchor, group ->
                showLongPressMenu(anchor, group)
            }
        )
        binding.rvChatList.adapter = adapter

        // 群聊列表随项目切换而变化（API → mock fallback）
        mainViewModel.groups.observe(viewLifecycleOwner) { groups ->
            adapter.submitList(groups)
            binding.tvChatListEmpty.isVisible = groups.isEmpty()
        }
    }

    private fun showMoreMenu() {
        val popup = PopupMenu(requireContext(), binding.btnAdd)
        popup.menuInflater.inflate(R.menu.menu_chat_list_more, popup.menu)
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_create_group -> showCreateGroupDialog()
                R.id.action_add_member -> showAddMemberDialog()
                R.id.action_project_detail -> {
                    if (mainViewModel.currentProjectId() == null) {
                        Toast.makeText(requireContext(), R.string.add_member_missing_project, Toast.LENGTH_SHORT).show()
                    } else {
                        findNavController().navigate(R.id.action_chatList_to_projectDetail)
                    }
                }
                else -> Toast.makeText(requireContext(), R.string.todo_placeholder, Toast.LENGTH_SHORT).show()
            }
            true
        }
        popup.show()
    }

    /**
     * 添加成员：列出团队中尚未加入当前项目的成员，勾选后逐个调 API-069 加入；
     * 每个成员可选择身份（项目成员/项目管理员，默认项目成员），
     * 选管理员的加入后再 PATCH 升级（§5.2）。
     */
    private fun showAddMemberDialog() {
        val teamId = mainViewModel.currentTeamId()
        val projectId = mainViewModel.currentProjectId()
        if (teamId == null || projectId == null) {
            val res = if (projectId == null) R.string.add_member_missing_project else R.string.new_project_missing_team
            Toast.makeText(requireContext(), res, Toast.LENGTH_SHORT).show()
            return
        }

        val dialog = BottomSheetDialog(requireContext())
        val sheetBinding = BottomSheetCreateGroupBinding.inflate(layoutInflater)
        dialog.setContentView(sheetBinding.root)
        // 添加成员复用创建群弹窗布局：标题改为添加成员，隐藏全选按钮与描述输入
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
            // 当前用户始终视为已在项目内（后端 GET /projects/{id}/members 可能不返回自己），
            // 否则候选 = 团队成员 − 项目成员 会把「自己」误当成唯一可添加的人
            val myId = SessionStore.user()?.id
            val existingIds = userRepository.getProjectMembers(projectId)
                .getOrNull()?.map { it.userId }?.toSet().orEmpty() + listOfNotNull(myId)
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

    /** 逐个将选中成员加入项目：先 POST 加入（初始 PROJECT_MEMBER），选管理员的再 PATCH 升级，汇总成功数量提示 */
    private fun addMembers(projectId: String, userIds: List<String>, roles: Map<String, String>) {
        if (userIds.isEmpty()) return
        viewLifecycleOwner.lifecycleScope.launch {
            var added = 0
            userIds.forEach { userId ->
                userRepository.addProjectMember(projectId, userId, UUID.randomUUID().toString())
                    .onSuccess {
                        added++
                        // 期望身份为管理员 → 加入后 PATCH 升级（§5.2）
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

    private fun showLongPressMenu(anchor: View, group: ChatGroup) {
        val popup = PopupMenu(requireContext(), anchor)
        popup.menuInflater.inflate(R.menu.menu_chat_long_press, popup.menu)
        // 切换置顶 / 取消置顶文案
        popup.menu.findItem(R.id.action_pin).isVisible = !group.isPinned
        popup.menu.findItem(R.id.action_unpin).isVisible = group.isPinned
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_pin, R.id.action_unpin -> {
                    mainViewModel.togglePin(group.id)
                    Toast.makeText(
                        requireContext(),
                        if (group.isPinned) "已置顶" else "已取消置顶",
                        Toast.LENGTH_SHORT
                    ).show()
                }
                R.id.action_delete_chat,
                R.id.action_hide_chat -> {
                    Toast.makeText(requireContext(), R.string.todo_placeholder, Toast.LENGTH_SHORT).show()
                }
            }
            true
        }
        popup.show()
    }

    /**
     * 创建需求群弹窗：群名 + 描述 + 从项目成员多选群成员（默认全不选，可全选）。
     * 需求群只包含选中的成员，群内成员权限平等（产品约定，见 docs/product-notes.md）。
     */
    private fun showCreateGroupDialog() {
        val projectId = mainViewModel.currentProjectId() ?: run {
            Toast.makeText(requireContext(), R.string.add_member_missing_project, Toast.LENGTH_SHORT).show()
            return
        }
        val dialog = BottomSheetDialog(requireContext())
        val sheetBinding = BottomSheetCreateGroupBinding.inflate(layoutInflater)
        dialog.setContentView(sheetBinding.root)

        lateinit var memberAdapter: GroupMemberPickAdapter
        memberAdapter = GroupMemberPickAdapter(
            onItemClick = { member -> memberAdapter.toggle(member) }
        )
        sheetBinding.rvGroupMembers.layoutManager = LinearLayoutManager(requireContext())
        sheetBinding.rvGroupMembers.adapter = memberAdapter

        // 全选 / 取消全选
        var allSelected = false
        sheetBinding.btnSelectAll.setOnClickListener {
            allSelected = !allSelected
            memberAdapter.setAllChecked(allSelected)
            sheetBinding.btnSelectAll.text = getString(if (allSelected) R.string.cancel_select_all else R.string.select_all)
        }

        // 加载项目成员（关联团队成员显示名字）+ 团队 Agent（需求群自动带 Agent，默认勾选）
        viewLifecycleOwner.lifecycleScope.launch {
            val teamId = mainViewModel.currentTeamId()
            val picks = mutableListOf<GroupMemberPick>()
            if (teamId != null) {
                val teamMembers = userRepository.getTeamMembers(teamId).getOrNull().orEmpty()
                val nameById = teamMembers.associate { it.userId to it.displayName }
                userRepository.getProjectMembers(projectId).getOrNull().orEmpty().forEach {
                    picks.add(GroupMemberPick(it.userId, nameById[it.userId] ?: "成员", it.role))
                }
                // 后端项目成员接口可能不返回当前用户：把自己补进可选列表（默认不勾选，可自行勾选进群）
                val myId = SessionStore.user()?.id
                val myName = SessionStore.user()?.displayName
                if (myId != null && picks.none { it.userId == myId }) {
                    picks.add(GroupMemberPick(myId, myName ?: "我", "PROJECT_MEMBER"))
                }
                // 团队 Agent 并入（isAgent 标记，默认勾选 = 自动加入需求群）
                agentRepository().getAgents(teamId).getOrNull().orEmpty()
                    .filter { it.status == "ACTIVE" }
                    .forEach { picks.add(GroupMemberPick(it.id, it.name, "AGENT", checked = true, isAgent = true)) }
            }
            memberAdapter.submitList(picks)
        }

        sheetBinding.btnSend.setOnClickListener {
            val name = sheetBinding.etGroupName.text?.toString()?.trim().orEmpty()
            if (name.isEmpty()) {
                Toast.makeText(requireContext(), R.string.create_group_name_required, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val description = sheetBinding.etGroupDescription.text?.toString()?.trim().orEmpty().ifEmpty { null }
            // 只提交真实用户（Agent 是团队级，入群靠 sendAsAgent 回消息，不随创建群提交）
            val memberIds = memberAdapter.checkedUserIds()
            dialog.dismiss()
            createGroup(projectId, name, description, memberIds)
        }
        sheetBinding.btnCancel.setOnClickListener { dialog.dismiss() }
        dialog.show()
    }

    /** 提交创建需求群：调 POST /groups（携带选中成员），成功后刷新群列表 */
    private fun createGroup(projectId: String, name: String, description: String?, memberIds: List<String>) {
        viewLifecycleOwner.lifecycleScope.launch {
            chatRepository().createGroup(
                projectId, name, description, memberIds,
                UUID.randomUUID().toString()
            ).onSuccess {
                Toast.makeText(requireContext(), R.string.create_group_success, Toast.LENGTH_SHORT).show()
                mainViewModel.refreshGroups()
            }.onFailure {
                Toast.makeText(requireContext(), R.string.create_group_failed, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun chatRepository(): ChatRepository =
        (requireActivity().application as QgentApp).container.chatRepository

    private fun agentRepository(): com.example.qgent.data.repository.AgentRepository =
        (requireActivity().application as QgentApp).container.agentRepository

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        private const val POLL_INTERVAL_MS = 3_000L
    }
}
