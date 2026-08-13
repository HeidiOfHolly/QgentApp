package com.example.qgent.ui.chat

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.example.qgent.R
import com.example.qgent.data.repository.ChatRepository
import com.example.qgent.databinding.FragmentChatSettingsBinding
import com.example.qgent.viewmodel.MainViewModel
import kotlinx.coroutines.launch

class ChatSettingsFragment : Fragment() {

    private var _binding: FragmentChatSettingsBinding? = null
    private val binding get() = _binding!!
    private val mainViewModel: MainViewModel by activityViewModels()
    private val chatRepo = ChatRepository()

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

        // 管理群
        binding.btnManageGroup.setOnClickListener {
            Toast.makeText(requireContext(), R.string.todo_placeholder, Toast.LENGTH_SHORT).show()
        }

        // 查看聊天记录
        binding.btnViewHistory.setOnClickListener {
            Toast.makeText(requireContext(), R.string.todo_placeholder, Toast.LENGTH_SHORT).show()
        }

        // 退出群聊
        binding.btnExitGroup.setOnClickListener {
            Toast.makeText(requireContext(), R.string.exit_group_placeholder, Toast.LENGTH_SHORT).show()
        }
    }

    private fun loadGroupData() {
        val mockName = "登录功能开发组"
        val mockMembers = listOf("张三", "李四", "王五", "赵六", "AgentOrchestrator")
        binding.tvGroupName.text = mockName
        renderMembers(mockMembers)

        val projectId = mainViewModel.currentProjectId()
        val groupId = arguments?.getString("groupId").orEmpty()
        if (projectId != null && groupId.isNotEmpty()) {
            viewLifecycleOwner.lifecycleScope.launch {
                chatRepo.getGroup(projectId, groupId).onSuccess { dto ->
                    binding.tvGroupName.text = dto.title
                }
                chatRepo.getMembers(projectId, groupId).onSuccess { dtos ->
                    if (dtos.isNotEmpty()) renderMembers(dtos.map { it.nickname })
                }
            }
        }
    }

    private fun renderMembers(names: List<String>) {
        binding.containerMembers.removeAllViews()
        binding.tvMemberCount.text = getString(R.string.group_member_count, names.size)
        for (name in names) {
            val row = layoutInflater.inflate(R.layout.item_chat_member, binding.containerMembers, false)
            row.findViewById<TextView>(R.id.tvMemberName)?.text = name
            row.findViewById<TextView>(R.id.tvAgentTag)?.isVisible = name == "AgentOrchestrator"
            binding.containerMembers.addView(row)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
