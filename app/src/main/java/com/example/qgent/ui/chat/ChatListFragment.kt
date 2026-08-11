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
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.qgent.MainActivity
import com.example.qgent.R
import com.example.qgent.databinding.BottomSheetCreateGroupBinding
import com.example.qgent.databinding.FragmentChatListBinding
import com.example.qgent.model.ChatGroup
import com.example.qgent.viewmodel.MainViewModel
import com.google.android.material.bottomsheet.BottomSheetDialog

class ChatListFragment : Fragment() {

    private var _binding: FragmentChatListBinding? = null
    private val binding get() = _binding!!
    private val mainViewModel: MainViewModel by activityViewModels()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentChatListBinding.inflate(inflater, container, false)
        return binding.root
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

        // TODO: 用户名等数据接入后替换；团队名与个人中心抽屉同步
        binding.tvUserName.setText(R.string.user_name_placeholder)
        mainViewModel.currentTeam.observe(viewLifecycleOwner) { team ->
            binding.tvTeamName.text = team
        }

        binding.rvChatList.layoutManager = LinearLayoutManager(requireContext())
        binding.rvChatList.addItemDecoration(
            DividerItemDecoration(requireContext(), DividerItemDecoration.VERTICAL)
        )

        // 群聊列表随项目切换而变化
        mainViewModel.currentProject.observe(viewLifecycleOwner) { project ->
            val groups = mainViewModel.groupsOf(project)
            binding.rvChatList.adapter = ChatListAdapter(groups) { group ->
                findNavController().navigate(
                    R.id.action_chatList_to_chatDetail,
                    bundleOf("groupName" to group.name)
                )
            }
            binding.tvChatListEmpty.isVisible = groups.isEmpty()
        }
    }

    private fun showMoreMenu() {
        val popup = PopupMenu(requireContext(), binding.btnAdd)
        popup.menuInflater.inflate(R.menu.menu_chat_list_more, popup.menu)
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_create_group -> showCreateGroupDialog()
                else -> Toast.makeText(requireContext(), R.string.todo_placeholder, Toast.LENGTH_SHORT).show()
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
}
