package com.example.qgent.ui.agent

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
import com.example.qgent.data.model.toAgent
import com.example.qgent.data.repository.AgentRepository
import com.example.qgent.databinding.FragmentAgentDetailBinding
import com.example.qgent.model.Agent
import com.example.qgent.viewmodel.MainViewModel
import kotlinx.coroutines.launch

class AgentDetailFragment : Fragment() {

    private var _binding: FragmentAgentDetailBinding? = null
    private val binding get() = _binding!!
    private val mainViewModel: MainViewModel by activityViewModels {
        (requireActivity().application as QgentApp).container.mainViewModelFactory
    }
    private val agentRepo: AgentRepository by lazy {
        (requireActivity().application as QgentApp).container.agentRepository
    }

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

        val agentId = arguments?.getString("agentId") ?: ""
        binding.btnBack.setOnClickListener { findNavController().popBackStack() }

        // 先用 nav args（mock）渲染身份卡，再尝试真实接口覆盖
        val mockName = arguments?.getString("agentName") ?: ""
        val mockDesc = arguments?.getString("agentDescription") ?: ""
        val mockRole = arguments?.getString("agentRole") ?: ""
        val mockCapabilities = arguments?.getString("agentCapabilities") ?: ""
        renderIdentity(mockName, mockDesc, mockRole, mockCapabilities)

        val teamId = mainViewModel.currentTeamId()
        if (teamId != null && agentId.isNotEmpty()) {
            viewLifecycleOwner.lifecycleScope.launch {
                agentRepo.getAgent(teamId, agentId).onSuccess { dto ->
                    renderIdentity(dto.toAgent())
                }
            }
        }

        // 已绑定 Memory：暂无可查询的绑定接口，无数据时隐藏列表、显示空态文案（不展示 mock 数据）
        memoryAdapter = BoundResourceAdapter(mutableListOf()) { }
        binding.rvBoundMemory.addItemDecoration(
            DividerItemDecoration(requireContext(), DividerItemDecoration.VERTICAL)
        )
        binding.rvBoundMemory.adapter = memoryAdapter
        binding.rvBoundMemory.isVisible = false
        binding.tvBoundMemoryEmpty.isVisible = true

        binding.tvDetailAddMemory.setOnClickListener {
            Toast.makeText(requireContext(), R.string.todo_placeholder, Toast.LENGTH_SHORT).show()
        }

        // 已绑定 Skill：同上，无数据时不展示 mock 数据
        skillAdapter = BoundResourceAdapter(mutableListOf()) { }
        binding.rvBoundSkill.addItemDecoration(
            DividerItemDecoration(requireContext(), DividerItemDecoration.VERTICAL)
        )
        binding.rvBoundSkill.adapter = skillAdapter
        binding.rvBoundSkill.isVisible = false
        binding.tvBoundSkillEmpty.isVisible = true

        binding.tvDetailAddSkill.setOnClickListener {
            Toast.makeText(requireContext(), R.string.todo_placeholder, Toast.LENGTH_SHORT).show()
        }
    }

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

        // 能力标签组：chips 样式，无能力时隐藏
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

        // 头像：有 URL 用 Glide 带鉴权头加载，否则默认占位
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
