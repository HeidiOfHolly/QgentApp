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
import com.example.qgent.data.model.ProjectMemberDto
import com.example.qgent.data.repository.MemoryRepository
import com.example.qgent.databinding.FragmentMemoryPoolBinding
import com.example.qgent.model.MemoryItem
import com.example.qgent.model.toMemoryItem
import com.example.qgent.viewmodel.MainViewModel
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * Memory 池：审核队列（PENDING_REVIEW）+ 共享池（APPROVED）。
 * - 点击条目：弹纯文本详情（ResourceDetailSheet）；
 * - 审核队列条目：点击弹详情卡片，右上角 通过/拒绝（仅项目 Admin，权限从项目成员角色实时判断；
 *   非 Admin 点击只能查看内容，无操作按钮）；
 * - 共享池条目：点击弹详情卡片，底部 删除 入口（archive 归档，从共享池移除；仅项目 Admin 可见）；
 * - 真实接口优先，失败由数据层 Fallback 回退 mock 保底（测试完成后移除）。
 */
class MemoryPoolFragment : Fragment() {

    private var _binding: FragmentMemoryPoolBinding? = null
    private val binding get() = _binding!!
    private val mainViewModel: MainViewModel by activityViewModels {
        (requireActivity().application as QgentApp).container.mainViewModelFactory
    }
    private val memoryRepo: MemoryRepository
        get() = (requireActivity().application as QgentApp).container.memoryRepository

    private var isAdmin = false

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentMemoryPoolBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.btnBack.setOnClickListener { findNavController().popBackStack() }

        binding.rvReviewList.layoutManager = LinearLayoutManager(requireContext())
        binding.rvApprovedList.layoutManager = LinearLayoutManager(requireContext())

        loadMemories()
    }

    /**
     * 审核权限判断（权限方案 v1.1）：只看项目详情返回的当前用户有效角色 role。
     * Team Owner 的兜底管理员角色已由后端在 GET /projects/{id} 的 role 中体现，
     * 客户端不再用成员列表/团队成员表兜底（后端对 Team Owner 有规范校验，本地兜底可能展示会被拒绝的操作）。
     */
    private suspend fun resolveAdminRole(): Boolean {
        val projectId = mainViewModel.currentProjectId() ?: return false
        val app = requireActivity().application as QgentApp
        return app.container.userRepository.getProject(projectId)
            .getOrNull()
            ?.role == "PROJECT_ADMIN"
    }

    private fun loadMemories() {
        val projectId = mainViewModel.currentProjectId() ?: return
        viewLifecycleOwner.lifecycleScope.launch {
            // 先确认审核权限再渲染列表，避免权限未就绪时（或普通成员）误显示 通过/拒绝 按钮
            isAdmin = resolveAdminRole()
            memoryRepo.getMemories(projectId).onSuccess { dtos ->
                val pending = dtos.filter { it.status == "PENDING_REVIEW" }.map { it.toMemoryItem() }
                val approved = dtos.filter { it.status == "APPROVED" }.map { it.toMemoryItem() }
                render(pending, approved)
            }.onFailure {
                render(emptyList(), emptyList())
            }
        }
    }

    private fun render(pending: List<MemoryItem>, approved: List<MemoryItem>) {
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
    private fun showReviewSheet(item: MemoryItem) {
        ResourceDetailSheet(
            name = item.name,
            description = item.description,
            isPending = true,
            onApprove = if (isAdmin) { { approveItem(item) } } else null,
            onReject = if (isAdmin) { { rejectItem(item) } } else null
        ).show(childFragmentManager, ResourceDetailSheet.TAG)
    }

    private fun approveItem(item: MemoryItem) {
        val projectId = mainViewModel.currentProjectId() ?: return
        viewLifecycleOwner.lifecycleScope.launch {
            memoryRepo.approve(projectId, item.id, UUID.randomUUID().toString())
                .onSuccess { Toast.makeText(requireContext(), "已通过", Toast.LENGTH_SHORT).show(); loadMemories() }
                .onFailure { Toast.makeText(requireContext(), "操作失败：${it.message}", Toast.LENGTH_SHORT).show() }
        }
    }

    private fun rejectItem(item: MemoryItem) {
        val projectId = mainViewModel.currentProjectId() ?: return
        viewLifecycleOwner.lifecycleScope.launch {
            memoryRepo.reject(projectId, item.id, UUID.randomUUID().toString())
                .onSuccess { Toast.makeText(requireContext(), "已驳回", Toast.LENGTH_SHORT).show(); loadMemories() }
                .onFailure { Toast.makeText(requireContext(), "操作失败：${it.message}", Toast.LENGTH_SHORT).show() }
        }
    }

    /** 点击共享条目：详情展示（只读 + 删除入口，删除仅项目 Admin 可见），删除后从共享池移除 */
    private fun onItemClick(item: MemoryItem) {
        ResourceDetailSheet(
            name = item.name,
            description = item.description,
            isPending = false,
            onDelete = if (isAdmin) { { deleteItem(item) } } else null
        ).show(
            childFragmentManager,
            ResourceDetailSheet.TAG
        )
    }

    /** 删除（归档）共享 Memory：调用 archive 接口后刷新列表 */
    private fun deleteItem(item: MemoryItem) {
        val projectId = mainViewModel.currentProjectId() ?: return
        viewLifecycleOwner.lifecycleScope.launch {
            memoryRepo.archive(projectId, item.id, UUID.randomUUID().toString())
                .onSuccess {
                    Toast.makeText(requireContext(), R.string.memory_deleted, Toast.LENGTH_SHORT).show()
                    loadMemories()
                }
                .onFailure { Toast.makeText(requireContext(), "删除失败：${it.message}", Toast.LENGTH_SHORT).show() }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
