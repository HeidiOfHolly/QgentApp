package com.example.qgent.ui.agent

import android.app.AlertDialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.DividerItemDecoration
import com.bumptech.glide.Glide
import com.bumptech.glide.load.model.GlideUrl
import com.bumptech.glide.load.model.LazyHeaders
import com.example.qgent.QgentApp
import com.example.qgent.R
import com.example.qgent.data.SessionStore
import com.example.qgent.data.api.RetrofitClient
import com.example.qgent.data.model.AgentDto
import com.example.qgent.data.model.toAgent
import com.example.qgent.data.repository.AgentRepository
import com.example.qgent.databinding.FragmentAgentDetailBinding
import com.example.qgent.model.Agent
import com.example.qgent.model.mapAgentRoleDisplay
import com.example.qgent.viewmodel.MainViewModel
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * Agent 详情：身份卡 + 管理操作。
 * - 管理操作（创建者或 Team Owner）：编辑 / 发布(审批占位) / 收回发布 / 下线
 * - Skill / Memory 在池中共享、执行时按需调用（后端），无需手动绑定（已移除绑定 UI）
 */
class AgentDetailFragment : Fragment() {

    private var _binding: FragmentAgentDetailBinding? = null
    private val binding get() = _binding!!
    private val mainViewModel: MainViewModel by activityViewModels {
        (requireActivity().application as QgentApp).container.mainViewModelFactory
    }
    private val agentRepo: AgentRepository by lazy {
        (requireActivity().application as QgentApp).container.agentRepository
    }

    private val agentId: String by lazy { arguments?.getString("agentId").orEmpty() }
    private var currentAgent: AgentDto? = null
    private var isCreator = false
    private var isManager = false   // 创建者 或 Team Owner

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

        binding.btnBack.setOnClickListener { findNavController().popBackStack() }

        // 先用 nav args（列表页快照）渲染身份卡，再尝试真实接口覆盖
        renderIdentity(
            arguments?.getString("agentName").orEmpty(),
            arguments?.getString("agentDescription").orEmpty(),
            arguments?.getString("agentRole").orEmpty(),
            arguments?.getString("agentCapabilities").orEmpty()
        )

        loadAgentDetail()
    }

    /** 从「编辑」返回后重新加载详情（onResume 刷新，保证改名/换头像/改描述即时生效） */
    override fun onResume() {
        super.onResume()
        if (agentId.isNotEmpty() && isResumed) loadAgentDetail()
    }

    private fun loadAgentDetail() {
        val teamId = mainViewModel.currentTeamId() ?: return
        if (agentId.isEmpty()) return
        viewLifecycleOwner.lifecycleScope.launch {
            agentRepo.getAgent(teamId, agentId)
                .onSuccess { dto ->
                    currentAgent = dto
                    renderIdentity(dto.toAgent())
                    setupActions(teamId, dto)
                }
                .onFailure {
                    // 详情接口失败：用列表快照（含 createdBy）兜底渲染身份卡与管理按钮，
                    // 保证自定义 Agent 创建后仍能看到 编辑/发布/下线
                    val fallback = AgentDto(
                        id = agentId,
                        name = arguments?.getString("agentName").orEmpty(),
                        avatar = null,
                        role = arguments?.getString("agentRole").orEmpty(),
                        capabilities = emptyList(),
                        prompt = null,
                        description = arguments?.getString("agentDescription").orEmpty(),
                        visibility = "PRIVATE",
                        status = "ACTIVE",
                        isDefault = arguments?.getBoolean("agentIsDefault") ?: false,
                        createdBy = arguments?.getString("agentCreatedBy").orEmpty()
                    )
                    setupActions(teamId, fallback)
                }
        }
    }

    // ── 管理操作 ──

    private suspend fun setupActions(teamId: String, dto: AgentDto) {
        val myId = SessionStore.user()?.id
        isCreator = dto.createdBy == myId
        // Team Owner 兜底管理（文档 §3.1）
        val isTeamOwner = myId != null && (requireActivity().application as QgentApp).container.userRepository
            .getTeamMembers(teamId).getOrNull().orEmpty()
            .any { it.userId == myId && it.role == "TEAM_OWNER" }
        isManager = isCreator || isTeamOwner
        if (!isManager) return

        binding.llAgentActions.isVisible = true
        // 编辑：仅创建者 且 非系统预置（v2.0.6 §5.1：isDefault=true 不可编辑）
        binding.tvDetailEdit.isVisible = isCreator && dto.isDefault != true
        binding.tvDetailEdit.setOnClickListener { openEdit(dto) }
        val active = dto.status != "ARCHIVED"
        binding.tvDetailPublish.isVisible = isCreator && active
        when (dto.visibility) {
            "PRIVATE" -> {
                binding.tvDetailPublish.text = "发布"
                binding.tvDetailPublish.isEnabled = true
                binding.tvDetailPublish.setOnClickListener { publish() }
            }
            "PENDING" -> {
                binding.tvDetailPublish.text = "等待审核"
                binding.tvDetailPublish.isEnabled = false
                binding.tvDetailPublish.setOnClickListener(null)
            }
            "TEAM", "TEAM_SHARED" -> {
                binding.tvDetailPublish.text = "团队已可用"
                binding.tvDetailPublish.isEnabled = false
                binding.tvDetailPublish.setOnClickListener(null)
            }
            else -> binding.tvDetailPublish.isVisible = false
        }
        binding.tvDetailArchive.isVisible = active
        binding.tvDetailArchive.setOnClickListener { confirmArchive() }
    }

    private fun publish() {
        val teamId = mainViewModel.currentTeamId() ?: return
        viewLifecycleOwner.lifecycleScope.launch {
            agentRepo.publishAgent(teamId, agentId, UUID.randomUUID().toString())
                .onSuccess { dto ->
                    currentAgent = dto
                    setupActions(teamId, dto)
                    val message = when (dto.visibility) {
                        "PENDING" -> "已提交发布审核"
                        "TEAM", "TEAM_SHARED" -> "已发布为团队可用"
                        else -> "Agent 发布状态已更新"
                    }
                    Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
                    mainViewModel.refreshAgents()
                }
                .onFailure { Toast.makeText(requireContext(), "操作失败：${it.message}", Toast.LENGTH_SHORT).show() }
        }
    }

    private fun confirmArchive() {
        val dto = currentAgent ?: return
        AlertDialog.Builder(requireContext())
            .setTitle("下线 Agent")
            .setMessage("确定下线「${dto.name}」？已运行的任务不受影响，下线后不再参与调度。")
            .setPositiveButton("下线") { _, _ -> archive() }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun archive() {
        val teamId = mainViewModel.currentTeamId() ?: return
        viewLifecycleOwner.lifecycleScope.launch {
            agentRepo.archiveAgent(teamId, agentId, UUID.randomUUID().toString())
                .onSuccess {
                    Toast.makeText(requireContext(), "已下线", Toast.LENGTH_SHORT).show()
                    mainViewModel.refreshAgents()
                    findNavController().popBackStack()
                }
                .onFailure { Toast.makeText(requireContext(), "操作失败：${it.message}", Toast.LENGTH_SHORT).show() }
        }
    }

    private fun openEdit(dto: AgentDto) {
        findNavController().navigate(
            com.example.qgent.R.id.action_agentDetail_to_agentEdit,
            androidx.core.os.bundleOf(
                "agentId" to dto.id,
                "agentName" to (dto.name ?: ""),
                "agentRole" to (dto.role ?: ""),
                "agentDescription" to (dto.description ?: ""),
                "agentAvatar" to (dto.avatar ?: ""),
                "agentPrompt" to (dto.prompt ?: "")
            )
        )
    }

    // ── 身份卡渲染 ──

    private fun renderIdentity(agent: Agent) = renderIdentity(
        name = agent.name,
        desc = agent.description,
        role = agent.roleWire?.takeIf { it.isNotBlank() } ?: agent.role.name,
        capabilities = agent.capabilities,
        avatar = agent.avatar
    )

    private fun renderIdentity(name: String, desc: String, role: String, capabilities: String) =
        renderIdentity(
            name = name,
            desc = desc,
            role = role,
            capabilities = capabilities.split(",").map { it.trim() }.filter { it.isNotEmpty() },
            avatar = null
        )

    private fun renderIdentity(
        name: String,
        desc: String,
        role: String,
        capabilities: List<String>,
        avatar: String?
    ) {
        binding.tvDetailAgentName.text = name
        binding.tvDetailAgentDesc.text = desc

        binding.tvDetailRole.text = mapRoleDisplay(role)
        binding.tvDetailRole.isVisible = role.isNotEmpty()

        binding.containerDetailCapabilities.removeAllViews()
        binding.containerDetailCapabilities.isVisible = capabilities.isNotEmpty()
        capabilities.forEach { cap ->
            val chip = layoutInflater.inflate(R.layout.item_capability_chip, binding.containerDetailCapabilities, false) as TextView
            chip.text = cap
            binding.containerDetailCapabilities.addView(chip)
        }

        if (avatar.isNullOrBlank()) {
            binding.ivDetailAvatar.setImageResource(R.drawable.ic_person)
        } else {
            val token = SessionStore.accessToken()
            val headers = LazyHeaders.Builder().apply {
                if (!token.isNullOrEmpty()) addHeader("Authorization", "Bearer $token")
            }.build()
            Glide.with(binding.ivDetailAvatar)
                .load(GlideUrl(RetrofitClient.resolveMediaUrl(avatar), headers))
                .placeholder(R.drawable.ic_person)
                .into(binding.ivDetailAvatar)
        }
    }

    private fun mapRoleDisplay(role: String): String = mapAgentRoleDisplay(role)

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
