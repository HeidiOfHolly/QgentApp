package com.example.qgent.ui.agent

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.qgent.R
import com.example.qgent.databinding.FragmentAgentBinding
import com.example.qgent.model.Agent
import com.example.qgent.model.AgentStatus
import com.example.qgent.model.MemoryItem
import com.example.qgent.model.ResourceStatus
import com.example.qgent.model.SkillItem
import com.example.qgent.viewmodel.MainViewModel

class AgentFragment : Fragment() {

    private var _binding: FragmentAgentBinding? = null
    private val binding get() = _binding!!
    private val mainViewModel: MainViewModel by activityViewModels()

    private val mockAgents = listOf(
        Agent("1", "前端开发", "负责前端页面开发与 UI 组件实现"),
        Agent("2", "后端开发", "负责 API 接口与业务逻辑开发"),
        Agent("3", "代码审查", "负责代码质量审查与规范检查"),
        Agent("4", "测试 Agent", "负责自动化测试用例编写与执行", AgentStatus.RUNNING)
    )

    // 预览用 mock：混合 pending + approved，取前三
    private val mockMemoryPreview = listOf(
        MemoryItem("m1", "登录状态持久化方案", "跨 Activity 登录状态管理策略", ResourceStatus.PENDING),
        MemoryItem("m3", "React 组件规范", "统一项目组件命名与文件结构规范", ResourceStatus.APPROVED),
        MemoryItem("m4", "API 接口约定", "RESTful 统一返回格式与错误码约定", ResourceStatus.APPROVED)
    )

    private val mockSkillPreview = listOf(
        SkillItem("s1", "Docker 部署脚本", "自动构建并推送镜像到私有仓库", ResourceStatus.PENDING),
        SkillItem("s2", "TypeScript 检查", "对 .ts/.tsx 运行 tsc --noEmit", ResourceStatus.APPROVED),
        SkillItem("s3", "ESLint 格式化", "基于团队规则自动修复格式问题", ResourceStatus.APPROVED)
    )

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

        binding.rvAgents.adapter = AgentCardAdapter(mockAgents) { agent ->
            findNavController().navigate(
                R.id.action_agent_to_agentDetail,
                androidx.core.os.bundleOf(
                    "agentId" to agent.id,
                    "agentName" to agent.name,
                    "agentDescription" to agent.description
                )
            )
        }

        // ── Memory 预览 ──
        val memoryAdapter = PreviewAdapter(mockMemoryPreview) { item ->
            val m = item as MemoryItem
            openResourceDetail(m.name, m.description, m.status == ResourceStatus.PENDING)
        }
        binding.rvMemoryPreview.layoutManager = LinearLayoutManager(requireContext())
        binding.rvMemoryPreview.adapter = memoryAdapter
        binding.tvMemoryMore.setOnClickListener {
            findNavController().navigate(R.id.action_agent_to_memoryPool)
        }

        // ── Skill 预览 ──
        val skillAdapter = PreviewAdapter(mockSkillPreview) { item ->
            val s = item as SkillItem
            openResourceDetail(s.name, s.description, s.status == ResourceStatus.PENDING)
        }
        binding.rvSkillPreview.layoutManager = LinearLayoutManager(requireContext())
        binding.rvSkillPreview.adapter = skillAdapter
        binding.tvSkillMore.setOnClickListener {
            findNavController().navigate(R.id.action_agent_to_skillPool)
        }
    }

    private fun openResourceDetail(name: String, desc: String, isPending: Boolean) {
        ResourceDetailSheet(name, desc, isPending).show(
            childFragmentManager,
            ResourceDetailSheet.TAG
        )
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
