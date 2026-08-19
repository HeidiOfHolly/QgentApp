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
import com.example.qgent.viewmodel.MainViewModel
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * Agent 详情：身份卡 + 管理操作 + Skill 绑定。
 * - 管理操作（创建者或 Team Owner）：编辑 / 发布(审批占位) / 收回发布 / 下线
 * - Skill 绑定：项目内可用 Skill 多选 → 全量替换（PUT agent-skill-bindings）
 * - Memory 绑定：后端暂无接口，保持空态
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

        // 已绑定 Memory：后端暂无绑定查询接口，无数据时隐藏列表、显示空态（不展示 mock）
        binding.rvBoundMemory.addItemDecoration(DividerItemDecoration(requireContext(), DividerItemDecoration.VERTICAL))
        binding.rvBoundMemory.adapter = BoundResourceAdapter(mutableListOf()) { }
        binding.rvBoundMemory.isVisible = false
        binding.tvBoundMemoryEmpty.isVisible = true
        binding.tvDetailAddMemory.setOnClickListener {
            Toast.makeText(requireContext(), R.string.todo_placeholder, Toast.LENGTH_SHORT).show()
        }

        // 已绑定 Skill：加载真实绑定集；「管理」→ 项目可用 Skill 多选全量替换
        binding.rvBoundSkill.addItemDecoration(DividerItemDecoration(requireContext(), DividerItemDecoration.VERTICAL))
        binding.tvDetailAddSkill.setOnClickListener { showSkillManageDialog() }
        loadSkillBindings()
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
        // 发布 / 收回发布：创建者（PRIVATE→发布占位；TEAM→收回发布）
        binding.tvDetailPublish.isVisible = isCreator
        if (dto.visibility == "PRIVATE") {
            binding.tvDetailPublish.text = "发布"
            binding.tvDetailPublish.setOnClickListener { publishPlaceholder() }
        } else {
            binding.tvDetailPublish.text = "收回发布"
            binding.tvDetailPublish.setOnClickListener { confirmUnpublish() }
        }
        // 下线：创建者或 Team Owner
        binding.tvDetailArchive.setOnClickListener { confirmArchive() }
    }

    /** 发布审批占位：后端审批接口未上线，仅提示（按产品决定不调直接 publish） */
    private fun publishPlaceholder() {
        AlertDialog.Builder(requireContext())
            .setTitle("发布 Agent")
            .setMessage("发布后需项目管理员审批，Agent 才会成为团队共享资源。\n\n发布审批功能开发中，待后端审批接口上线后开通。")
            .setPositiveButton("知道了") { _, _ ->
                Toast.makeText(requireContext(), "发布审批功能开发中", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun confirmUnpublish() {
        val dto = currentAgent ?: return
        AlertDialog.Builder(requireContext())
            .setTitle("收回发布")
            .setMessage("确定将「${dto.name}」收回为私有 Agent？团队其他成员将无法使用。")
            .setPositiveButton("收回") { _, _ -> unpublish() }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun unpublish() {
        val teamId = mainViewModel.currentTeamId() ?: return
        viewLifecycleOwner.lifecycleScope.launch {
            agentRepo.unpublishAgent(teamId, agentId, UUID.randomUUID().toString())
                .onSuccess {
                    Toast.makeText(requireContext(), "已收回为私有", Toast.LENGTH_SHORT).show()
                    mainViewModel.refreshAgents()
                    findNavController().popBackStack()
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

    // ── Skill 绑定 ──

    private fun loadSkillBindings() {
        val projectId = mainViewModel.currentProjectId() ?: return
        if (agentId.isEmpty()) return
        viewLifecycleOwner.lifecycleScope.launch {
            agentRepo.getAgentSkillBindings(projectId, agentId)
                .onSuccess { resp ->
                    val names = resp.skills.orEmpty().map { it.name ?: it.id }
                    renderSkillList(names)
                }
                .onFailure { renderSkillList(emptyList()) }
        }
    }

    private fun renderSkillList(names: List<String>) {
        binding.rvBoundSkill.adapter = BoundResourceAdapter(names.toMutableList()) { }
        binding.rvBoundSkill.isVisible = names.isNotEmpty()
        binding.tvBoundSkillEmpty.isVisible = names.isEmpty()
    }

    /** 项目可用 Skill 多选（本人 PRIVATE 未归档 + 已发布 PROJECT_SHARED）→ 全量替换绑定 */
    private fun showSkillManageDialog() {
        val projectId = mainViewModel.currentProjectId() ?: run {
            Toast.makeText(requireContext(), "请先选择项目", Toast.LENGTH_SHORT).show()
            return
        }
        val app = requireActivity().application as QgentApp
        viewLifecycleOwner.lifecycleScope.launch {
            val available = app.container.skillRepository.getSkills(projectId).getOrNull().orEmpty()
                .filter { it.status != "ARCHIVED" && (it.visibility == "PRIVATE" || it.status == "PUBLISHED") }
            val boundIds = app.container.agentRepository.getAgentSkillBindings(projectId, agentId)
                .getOrNull()?.skillIds.orEmpty().toSet()
            if (available.isEmpty()) {
                Toast.makeText(requireContext(), "项目内暂无可用 Skill（本人 PRIVATE 或已发布）", Toast.LENGTH_LONG).show()
                return@launch
            }
            val checked = mutableMapOf<String, Boolean>()
            available.forEach { checked[it.id] = it.id in boundIds }
            val names = available.map { it.name }.toTypedArray()
            AlertDialog.Builder(requireContext())
                .setTitle("绑定 Skill（多选）")
                .setMultiChoiceItems(names, available.map { checked[it.id] == true }.toBooleanArray()) { _, which, isChecked ->
                    checked[available[which].id] = isChecked
                }
                .setPositiveButton("保存") { _, _ ->
                    val selected = checked.filterValues { it }.keys.toList()
                    saveSkillBindings(projectId, selected)
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }
    }

    private fun saveSkillBindings(projectId: String, skillIds: List<String>) {
        viewLifecycleOwner.lifecycleScope.launch {
            agentRepo.bindAgentSkills(projectId, agentId, skillIds, UUID.randomUUID().toString())
                .onSuccess {
                    Toast.makeText(requireContext(), "Skill 绑定已更新", Toast.LENGTH_SHORT).show()
                    loadSkillBindings()
                }
                .onFailure { Toast.makeText(requireContext(), "保存失败：${it.message}", Toast.LENGTH_SHORT).show() }
        }
    }

    // ── 身份卡渲染 ──

    private fun renderIdentity(agent: Agent) = renderIdentity(
        name = agent.name,
        desc = agent.description,
        role = agent.role.name,
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
            val chip = TextView(requireContext()).apply {
                text = cap
                textSize = 12f
                setTextColor(android.graphics.Color.WHITE)
                background = resources.getDrawable(R.drawable.bg_status_tag, null)
                setPadding(
                    resources.getDimensionPixelSize(R.dimen.chip_padding_h),
                    resources.getDimensionPixelSize(R.dimen.chip_padding_v),
                    resources.getDimensionPixelSize(R.dimen.chip_padding_h),
                    resources.getDimensionPixelSize(R.dimen.chip_padding_v)
                )
                setCompoundDrawablesRelativeWithIntrinsicBounds(0, 0, 0, 0)
            }
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = resources.getDimensionPixelSize(R.dimen.chip_margin_end) }
            binding.containerDetailCapabilities.addView(chip, lp)
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

    private fun mapRoleDisplay(role: String): String = when (role) {
        "ORCHESTRATOR" -> "调度者"
        "PLANNER" -> "规划者"
        "DEVELOPER" -> "开发者"
        "TESTER" -> "测试者"
        "REVIEWER" -> "审查者"
        "GENERAL" -> "通用"
        else -> role
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
