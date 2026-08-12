package com.example.qgent.ui.chat

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.example.qgent.R
import com.example.qgent.databinding.FragmentChatSettingsBinding

class ChatSettingsFragment : Fragment() {

    private var _binding: FragmentChatSettingsBinding? = null
    private val binding get() = _binding!!

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

        // mock 群名
        binding.tvGroupName.text = "登录功能开发组"

        // mock 群成员（群聊内所有成员平等，均为 member）+ AgentOrchestrator
        val members = listOf("张三", "李四", "王五", "赵六", "AgentOrchestrator")
        binding.tvMemberCount.text = getString(R.string.group_member_count, members.size)
        for (name in members) {
            val row = layoutInflater.inflate(R.layout.item_chat_member, binding.containerMembers, false)
            row.findViewById<TextView>(R.id.tvMemberName)?.text = name
            row.findViewById<TextView>(R.id.tvAgentTag)?.isVisible = name == "AgentOrchestrator"
            binding.containerMembers.addView(row)
        }

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

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
