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
import com.example.qgent.databinding.DialogSearchMessagesBinding
import com.example.qgent.databinding.FragmentChatSettingsBinding
import com.example.qgent.viewmodel.MainViewModel
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

        // 查看聊天记录：弹出搜索弹窗，按关键词过滤消息
        binding.btnViewHistory.setOnClickListener { showSearchDialog() }

        // 退出群聊：先弹确认，确认后调接口
        binding.btnExitGroup.setOnClickListener { confirmExitGroup() }
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
            // 后端群成员 DTO 无 type 字段，Agent 标签用昵称启发式判断
            row.findViewById<TextView>(R.id.tvAgentTag)?.isVisible = name.startsWith("Agent", ignoreCase = true)
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
