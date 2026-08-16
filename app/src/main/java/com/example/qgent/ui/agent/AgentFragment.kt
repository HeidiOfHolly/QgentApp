package com.example.qgent.ui.agent

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.qgent.QgentApp
import com.example.qgent.R
import com.example.qgent.databinding.FragmentAgentBinding
import com.example.qgent.model.MemoryItem
import com.example.qgent.model.SkillItem
import com.example.qgent.model.toMemoryItem
import com.example.qgent.model.toSkillItem
import com.example.qgent.viewmodel.MainViewModel
import kotlinx.coroutines.launch

/**
 * Agent 页：Agent 卡片列表 + Memory / Skill 预览（真实接口，失败由数据层 mock 保底）。
 */
class AgentFragment : Fragment() {

    private var _binding: FragmentAgentBinding? = null
    private val binding get() = _binding!!
    private val mainViewModel: MainViewModel by activityViewModels {
        (requireActivity().application as QgentApp).container.mainViewModelFactory
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentAgentBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val agentAdapter = AgentCardAdapter(emptyList()) { agent ->
            findNavController().navigate(
                R.id.action_agent_to_agentDetail,
                androidx.core.os.bundleOf(
                    "agentId" to agent.id,
                    "agentName" to agent.name,
                    "agentDescription" to agent.description,
                    "agentRole" to agent.role.name,
                    "agentCapabilities" to agent.capabilities.joinToString(", ")
                )
            )
        }
        binding.rvAgents.adapter = agentAdapter
        mainViewModel.agents.observe(viewLifecycleOwner) { agents ->
            agentAdapter.submitList(agents)
        }

        // ── Memory 预览（真实接口，取已共享前 3 条） ──
        binding.rvMemoryPreview.layoutManager = LinearLayoutManager(requireContext())
        binding.tvMemoryMore.setOnClickListener {
            findNavController().navigate(R.id.action_agent_to_memoryPool)
        }

        // ── Skill 预览（真实接口，取已共享前 3 条） ──
        binding.rvSkillPreview.layoutManager = LinearLayoutManager(requireContext())
        binding.tvSkillMore.setOnClickListener {
            findNavController().navigate(R.id.action_agent_to_skillPool)
        }

        loadPreviews()
    }

    private fun loadPreviews() {
        val projectId = mainViewModel.currentProjectId() ?: return
        val app = requireActivity().application as QgentApp
        viewLifecycleOwner.lifecycleScope.launch {
            val memoryRepo = app.container.memoryRepository
            val skillRepo = app.container.skillRepository

            val approvedMemories = memoryRepo.getMemories(projectId).getOrNull().orEmpty()
                .filter { it.status == "APPROVED" }
                .take(3)
                .map { it.toMemoryItem() }
            renderMemoryPreview(approvedMemories)

            val publishedSkills = skillRepo.getSkills(projectId).getOrNull().orEmpty()
                .filter { it.status == "PUBLISHED" }
                .take(3)
                .map { it.toSkillItem() }
            renderSkillPreview(publishedSkills)
        }
    }

    private fun renderMemoryPreview(items: List<MemoryItem>) {
        val adapter = PreviewAdapter(items) {
            findNavController().navigate(R.id.action_agent_to_memoryPool)
        }
        binding.rvMemoryPreview.adapter = adapter
    }

    private fun renderSkillPreview(items: List<SkillItem>) {
        val adapter = PreviewAdapter(items) {
            findNavController().navigate(R.id.action_agent_to_skillPool)
        }
        binding.rvSkillPreview.adapter = adapter
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
