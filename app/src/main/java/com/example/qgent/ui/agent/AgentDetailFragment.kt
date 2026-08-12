package com.example.qgent.ui.agent

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.DividerItemDecoration
import com.example.qgent.R
import com.example.qgent.databinding.FragmentAgentDetailBinding

class AgentDetailFragment : Fragment() {

    private var _binding: FragmentAgentDetailBinding? = null
    private val binding get() = _binding!!

    private val mockMemory = mutableListOf("React 组件规范", "API 接口约定", "Git 提交规范")
    private val mockSkill = mutableListOf("TypeScript 检查", "ESLint 格式化")
    private lateinit var memoryAdapter: BoundResourceAdapter
    private lateinit var skillAdapter: BoundResourceAdapter

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentAgentDetailBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val name = arguments?.getString("agentName") ?: ""
        val desc = arguments?.getString("agentDescription") ?: ""

        binding.btnBack.setOnClickListener { findNavController().popBackStack() }

        binding.tvDetailAgentName.text = name
        binding.tvDetailAgentDesc.text = desc

        // Memory 列表
        memoryAdapter = BoundResourceAdapter(mockMemory) { resource ->
            mockMemory.remove(resource)
            memoryAdapter.notifyDataSetChanged()
        }
        binding.rvBoundMemory.addItemDecoration(
            DividerItemDecoration(requireContext(), DividerItemDecoration.VERTICAL)
        )
        binding.rvBoundMemory.adapter = memoryAdapter

        binding.tvDetailAddMemory.setOnClickListener {
            Toast.makeText(requireContext(), R.string.todo_placeholder, Toast.LENGTH_SHORT).show()
        }

        // Skill 列表
        skillAdapter = BoundResourceAdapter(mockSkill) { resource ->
            mockSkill.remove(resource)
            skillAdapter.notifyDataSetChanged()
        }
        binding.rvBoundSkill.addItemDecoration(
            DividerItemDecoration(requireContext(), DividerItemDecoration.VERTICAL)
        )
        binding.rvBoundSkill.adapter = skillAdapter

        binding.tvDetailAddSkill.setOnClickListener {
            Toast.makeText(requireContext(), R.string.todo_placeholder, Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
