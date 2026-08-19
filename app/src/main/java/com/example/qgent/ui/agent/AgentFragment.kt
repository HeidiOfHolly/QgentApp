package com.example.qgent.ui.agent

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
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

    private lateinit var agentAdapter: AgentCardAdapter
    private var workingPollJob: Job? = null

    /** 运行中视为「工作流中」的 TaskRun 状态：排队/执行/等待输入或审批 */
    private val ACTIVE_RUN_STATUSES = setOf("QUEUED", "RUNNING", "WAITING_INPUT", "WAITING_APPROVAL", "BLOCKED")

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
                    "agentCapabilities" to agent.capabilities.joinToString(", "),
                    "agentCreatedBy" to (agent.createdBy ?: ""),
                    "agentIsDefault" to false
                )
            )
        }
        binding.rvAgents.adapter = agentAdapter
        this.agentAdapter = agentAdapter
        mainViewModel.agents.observe(viewLifecycleOwner) { agents ->
            agentAdapter.submitList(agents)
        }

        // 「+ 新建」→ 新建 Agent 表单（任何人都可创建自己的 PRIVATE Agent）
        binding.tvAddAgent.setOnClickListener {
            findNavController().navigate(R.id.action_agent_to_agentEdit)
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
        binding.tvMemoryPreviewEmpty.isVisible = items.isEmpty()
    }

    private fun renderSkillPreview(items: List<SkillItem>) {
        val adapter = PreviewAdapter(items) {
            findNavController().navigate(R.id.action_agent_to_skillPool)
        }
        binding.rvSkillPreview.adapter = adapter
        binding.tvSkillPreviewEmpty.isVisible = items.isEmpty()
    }

    override fun onResume() {
        super.onResume()
        startWorkingPoll()
    }

    override fun onPause() {
        super.onPause()
        workingPollJob?.cancel()
        workingPollJob = null
    }

    /** 轮询推导各 Agent 是否在工作流中（后端 Agent 状态恒 ACTIVE，运行态看 task-runs） */
    private fun startWorkingPoll() {
        if (workingPollJob?.isActive == true) return
        workingPollJob = viewLifecycleOwner.lifecycleScope.launch {
            while (true) {
                refreshWorkingState()
                delay(POLL_INTERVAL_MS)
            }
        }
    }

    private suspend fun refreshWorkingState() {
        val projectId = mainViewModel.currentProjectId() ?: return
        val agents = mainViewModel.agents.value.orEmpty()
        if (agents.isEmpty()) return
        val app = requireActivity().application as QgentApp
        val working = mutableSetOf<String>()
        agents.forEach { agent ->
            val runs = app.container.taskRepository.getTaskRuns(projectId, agent.id).getOrNull().orEmpty()
            if (runs.any { it.status in ACTIVE_RUN_STATUSES }) working.add(agent.id)
        }
        agentAdapter.setWorkingIds(working)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        workingPollJob?.cancel()
        workingPollJob = null
        _binding = null
    }

    companion object {
        private const val POLL_INTERVAL_MS = 3_000L
    }
}
