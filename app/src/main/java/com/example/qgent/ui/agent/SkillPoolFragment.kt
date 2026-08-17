package com.example.qgent.ui.agent

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.qgent.QgentApp
import com.example.qgent.R
import com.example.qgent.data.SessionStore
import com.example.qgent.data.repository.SkillRepository
import com.example.qgent.databinding.FragmentSkillPoolBinding
import com.example.qgent.model.SkillItem
import com.example.qgent.model.toSkillItem
import com.example.qgent.viewmodel.MainViewModel
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * Skill 池：审核队列（PENDING_REVIEW）+ 共享池（PUBLISHED）。
 * - 点击条目：弹纯文本详情（ResourceDetailSheet）；
 * - 审核队列条目：长按弹出 通过/拒绝（仅项目 Admin，权限从项目成员角色实时判断）；
 * - 真实接口优先，失败由数据层 Fallback 回退 mock 保底（测试完成后移除）。
 */
class SkillPoolFragment : Fragment() {

    private var _binding: FragmentSkillPoolBinding? = null
    private val binding get() = _binding!!
    private val mainViewModel: MainViewModel by activityViewModels {
        (requireActivity().application as QgentApp).container.mainViewModelFactory
    }
    private val skillRepo: SkillRepository
        get() = (requireActivity().application as QgentApp).container.skillRepository

    private var isAdmin = false

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSkillPoolBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.btnBack.setOnClickListener { findNavController().popBackStack() }

        binding.rvReviewList.layoutManager = LinearLayoutManager(requireContext())
        binding.rvApprovedList.layoutManager = LinearLayoutManager(requireContext())

        checkAdminRole()
        loadSkills()
    }

    /**
     * 审核权限判断：项目 Admin 或 当前团队 Owner（文档 §3.1：Team Owner 对本团队项目有兜底管理权限）。
     * 从项目成员角色 + 团队成员角色实时读取，不依赖写死的 isProjectAdmin。
     */
    private fun checkAdminRole() {
        val projectId = mainViewModel.currentProjectId() ?: return
        val teamId = mainViewModel.currentTeamId() ?: return
        val myId = SessionStore.user()?.id ?: return
        viewLifecycleOwner.lifecycleScope.launch {
            val app = requireActivity().application as QgentApp
            val projectAdmin = app.container.userRepository.getProjectMembers(projectId)
                .getOrNull().orEmpty()
                .any { it.userId == myId && it.role == "PROJECT_ADMIN" }
            val teamOwner = app.container.userRepository.getTeamMembers(teamId)
                .getOrNull().orEmpty()
                .any { it.userId == myId && it.role == "TEAM_OWNER" }
            isAdmin = projectAdmin || teamOwner
        }
    }

    private fun loadSkills() {
        val projectId = mainViewModel.currentProjectId() ?: return
        viewLifecycleOwner.lifecycleScope.launch {
            skillRepo.getSkills(projectId).onSuccess { dtos ->
                val pending = dtos.filter { it.status == "PENDING_REVIEW" }.map { it.toSkillItem() }
                val approved = dtos.filter { it.status == "PUBLISHED" }.map { it.toSkillItem() }
                render(pending, approved)
            }.onFailure {
                render(emptyList(), emptyList())
            }
        }
    }

    private fun render(pending: List<SkillItem>, approved: List<SkillItem>) {
        // 审核队列：点击弹详情卡片，右上角带 通过/拒绝（Admin 可操作；非 Admin 只读）
        binding.rvReviewList.adapter = PoolResourceAdapter(pending) { item ->
            showReviewSheet(item)
        }
        binding.tvReviewEmpty.isVisible = pending.isEmpty()

        // 共享池：点击看纯文本详情
        binding.tvApprovedCount.text = "共 ${approved.size} 条"
        binding.rvApprovedList.adapter = PoolResourceAdapter(approved) { item ->
            onItemClick(item)
        }
    }

    /** 审核队列详情卡片：显示内容 + 右上角通过/拒绝（非 Admin 无按钮） */
    private fun showReviewSheet(item: SkillItem) {
        ResourceDetailSheet(
            name = item.name,
            description = item.description,
            isPending = true,
            onApprove = if (isAdmin) { { approveItem(item) } } else null,
            onReject = if (isAdmin) { { rejectItem(item) } } else null
        ).show(childFragmentManager, ResourceDetailSheet.TAG)
    }

    private fun approveItem(item: SkillItem) {
        val projectId = mainViewModel.currentProjectId() ?: return
        viewLifecycleOwner.lifecycleScope.launch {
            skillRepo.approve(projectId, item.id, UUID.randomUUID().toString())
                .onSuccess { Toast.makeText(requireContext(), "已通过", Toast.LENGTH_SHORT).show(); loadSkills() }
                .onFailure { Toast.makeText(requireContext(), "操作失败：${it.message}", Toast.LENGTH_SHORT).show() }
        }
    }

    private fun rejectItem(item: SkillItem) {
        val projectId = mainViewModel.currentProjectId() ?: return
        viewLifecycleOwner.lifecycleScope.launch {
            skillRepo.reject(projectId, item.id, UUID.randomUUID().toString())
                .onSuccess { Toast.makeText(requireContext(), "已驳回", Toast.LENGTH_SHORT).show(); loadSkills() }
                .onFailure { Toast.makeText(requireContext(), "操作失败：${it.message}", Toast.LENGTH_SHORT).show() }
        }
    }

    /** 点击共享条目：纯文本详情（只读展示） */
    private fun onItemClick(item: SkillItem) {
        ResourceDetailSheet(item.name, item.description, isPending = false).show(
            childFragmentManager,
            ResourceDetailSheet.TAG
        )
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
