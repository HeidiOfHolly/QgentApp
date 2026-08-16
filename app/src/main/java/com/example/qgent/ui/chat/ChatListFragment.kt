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
import com.example.qgent.data.repository.UserRepository
import com.example.qgent.data.sse.ProjectEventStream
import com.example.qgent.databinding.BottomSheetCreateGroupBinding
import com.example.qgent.databinding.FragmentChatListBinding
import com.example.qgent.model.ChatGroup
import com.example.qgent.viewmodel.MainViewModel
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
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
        startPolling()
        startEventStream()
    }

    override fun onPause() {
        super.onPause()
        stopPolling()
        stopEventStream()
    }

    /**
     * 建立项目级 SSE 连接：任何事件到达（任务状态变化会往群写 TASK_STATUS 消息、
     * diff/delivery 事件同步刷新）都立即刷新群列表，比轮询更快感知新消息。
     * 轮询仍保留作为无事件时的兜底。
     */
    private fun startEventStream() {
        val projectId = mainViewModel.currentProjectId() ?: return
        eventStream.start(projectId)
        if (eventStreamJob == null) {
            eventStreamJob = viewLifecycleOwner.lifecycleScope.launch {
                eventStream.events.collect {
                    mainViewModel.refreshGroups()
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
                else -> Toast.makeText(requireContext(), R.string.todo_placeholder, Toast.LENGTH_SHORT).show()
            }
            true
        }
        popup.show()
    }

    /** 添加成员：列出团队中尚未加入当前项目的成员，勾选后逐个调 API-069 加入 */
    private fun showAddMemberDialog() {
        val teamId = mainViewModel.currentTeamId()
        val projectId = mainViewModel.currentProjectId()
        if (teamId == null || projectId == null) {
            val res = if (projectId == null) R.string.add_member_missing_project else R.string.new_project_missing_team
            Toast.makeText(requireContext(), res, Toast.LENGTH_SHORT).show()
            return
        }

        viewLifecycleOwner.lifecycleScope.launch {
            val teamMembers = userRepository.getTeamMembers(teamId).getOrElse {
                Toast.makeText(requireContext(), R.string.add_member_failed, Toast.LENGTH_SHORT).show()
                return@launch
            }
            val existingIds = userRepository.getProjectMembers(projectId)
                .getOrNull()?.map { it.userId }?.toSet().orEmpty()

            val candidates = teamMembers.filter { it.userId !in existingIds }
            if (candidates.isEmpty()) {
                Toast.makeText(requireContext(), R.string.add_member_empty, Toast.LENGTH_SHORT).show()
                return@launch
            }

            val names = candidates.map { "${it.displayName}（${it.email}）" }.toTypedArray()
            val checked = BooleanArray(candidates.size)
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.action_add_member)
                .setMultiChoiceItems(names, checked) { _, _, _ -> }
                .setPositiveButton(R.string.confirm) { _, _ ->
                    addMembers(projectId, candidates.filterIndexed { i, _ -> checked[i] })
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }
    }

    /** 逐个将选中成员加入项目，汇总成功数量提示 */
    private fun addMembers(projectId: String, members: List<TeamMemberDto>) {
        if (members.isEmpty()) return
        viewLifecycleOwner.lifecycleScope.launch {
            var added = 0
            members.forEach { member ->
                userRepository.addProjectMember(projectId, member.userId, UUID.randomUUID().toString())
                    .onSuccess { added++ }
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

    /** 创建群聊：输入成员邮箱发送邀请（邀请/通知/入群待后端与多用户就绪后接入） */
    private fun showCreateGroupDialog() {
        val dialog = BottomSheetDialog(requireContext())
        val sheetBinding = BottomSheetCreateGroupBinding.inflate(layoutInflater)
        dialog.setContentView(sheetBinding.root)

        sheetBinding.btnSend.setOnClickListener {
            Toast.makeText(requireContext(), R.string.invite_sent_placeholder, Toast.LENGTH_SHORT).show()
            dialog.dismiss()
        }
        sheetBinding.btnCancel.setOnClickListener { dialog.dismiss() }
        dialog.show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        private const val POLL_INTERVAL_MS = 3_000L
    }
}
