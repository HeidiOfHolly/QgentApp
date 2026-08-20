package com.example.qgent.ui.agent

import android.app.AlertDialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.qgent.QgentApp
import com.example.qgent.R
import com.example.qgent.data.model.CreateSkillRequest
import com.example.qgent.data.repository.SkillRepository
import com.example.qgent.databinding.FragmentSkillPoolBinding
import com.example.qgent.model.SkillItem
import com.example.qgent.model.toSkillItem
import com.example.qgent.viewmodel.MainViewModel
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * Skill 池：审核队列（PENDING_REVIEW）+ 共享池（PUBLISHED）。
 * - 新建（顶部「+ 新建」，创建草稿后自行提交审核）；
 * - 点击条目：弹纯文本详情（ResourceDetailSheet）；
 * - 审核队列条目：长按弹出 通过/拒绝（仅项目 Admin）；
 * - 共享池条目：长按归档删除（仅项目 Admin）。
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
        // 新建 Skill：输入名称/内容创建草稿（创建后可在审核队列/详情提交审核）
        binding.btnAddSkill.setOnClickListener { showCreateSkillDialog() }

        binding.rvReviewList.layoutManager = LinearLayoutManager(requireContext())
        binding.rvApprovedList.layoutManager = LinearLayoutManager(requireContext())

        loadSkills()
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

    private fun loadSkills() {
        val projectId = mainViewModel.currentProjectId() ?: return
        viewLifecycleOwner.lifecycleScope.launch {
            // 先确认审核权限再渲染列表，避免权限未就绪时（或普通成员）误显示 通过/拒绝 按钮
            isAdmin = resolveAdminRole()
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

        // 共享池：点击看纯文本详情；长按归档删除（仅 Admin）
        binding.tvApprovedCount.text = "共 ${approved.size} 条"
        val approvedAdapter = PoolResourceAdapter(approved) { item -> onItemClick(item) }
        approvedAdapter.setOnItemLongClick { item, _ ->
            if (isAdmin) confirmDeleteSkill(item)
        }
        binding.rvApprovedList.adapter = approvedAdapter
        binding.tvApprovedEmpty.isVisible = approved.isEmpty()
    }

    /** 新建 Skill 弹窗：名称必填 + 内容可选（创建草稿后可在审核队列处理） */
    private fun showCreateSkillDialog() {
        val projectId = mainViewModel.currentProjectId() ?: run {
            Toast.makeText(requireContext(), "请先选择项目", Toast.LENGTH_SHORT).show()
            return
        }
        val dialogBinding = com.example.qgent.databinding.DialogCreateSkillBinding.inflate(layoutInflater)
        AlertDialog.Builder(requireContext())
            .setTitle("新建 Skill")
            .setView(dialogBinding.root)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton("创建") { _, _ ->
                val name = dialogBinding.etName.text.toString().trim()
                if (name.isEmpty()) {
                    Toast.makeText(requireContext(), "请输入 Skill 名称", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                createSkill(projectId, name, dialogBinding.etContent.text.toString().trim().ifEmpty { null })
            }
            .show()
    }

    private fun createSkill(projectId: String, name: String, content: String?) {
        viewLifecycleOwner.lifecycleScope.launch {
            skillRepo.createSkill(projectId, CreateSkillRequest(name = name, content = content), UUID.randomUUID().toString())
                .onSuccess {
                    Toast.makeText(requireContext(), "已创建 Skill（草稿）", Toast.LENGTH_SHORT).show()
                    loadSkills()
                }
                .onFailure { Toast.makeText(requireContext(), "创建失败：${it.message}", Toast.LENGTH_SHORT).show() }
        }
    }

    private fun confirmDeleteSkill(item: SkillItem) {
        AlertDialog.Builder(requireContext())
            .setTitle("删除 Skill")
            .setMessage("确定归档删除「${item.name}」？")
            .setPositiveButton("删除") { _, _ -> deleteSkill(item) }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun deleteSkill(item: SkillItem) {
        val projectId = mainViewModel.currentProjectId() ?: return
        viewLifecycleOwner.lifecycleScope.launch {
            skillRepo.archive(projectId, item.id, UUID.randomUUID().toString())
                .onSuccess {
                    Toast.makeText(requireContext(), "已删除", Toast.LENGTH_SHORT).show()
                    loadSkills()
                }
                .onFailure { Toast.makeText(requireContext(), "删除失败：${it.message}", Toast.LENGTH_SHORT).show() }
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
