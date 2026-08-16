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
import com.example.qgent.data.repository.MemoryRepository
import com.example.qgent.databinding.FragmentMemoryPoolBinding
import com.example.qgent.model.MemoryItem
import com.example.qgent.model.toMemoryItem
import com.example.qgent.viewmodel.MainViewModel
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * Memory 池：审核队列（PENDING_REVIEW）+ 共享池（APPROVED）。
 * 真实接口优先，失败由数据层 Fallback 回退 mock 保底（测试完成后移除）。
 */
class MemoryPoolFragment : Fragment() {

    private var _binding: FragmentMemoryPoolBinding? = null
    private val binding get() = _binding!!
    private val mainViewModel: MainViewModel by activityViewModels {
        (requireActivity().application as QgentApp).container.mainViewModelFactory
    }
    private val memoryRepo: MemoryRepository
        get() = (requireActivity().application as QgentApp).container.memoryRepository

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

    private fun loadMemories() {
        val projectId = mainViewModel.currentProjectId() ?: return
        viewLifecycleOwner.lifecycleScope.launch {
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
        binding.rvReviewList.adapter = PoolResourceAdapter(pending) { item ->
            onItemClick(item, isPending = true)
        }
        binding.tvReviewEmpty.isVisible = pending.isEmpty()

        binding.tvApprovedCount.text = "共 ${approved.size} 条"
        binding.rvApprovedList.adapter = PoolResourceAdapter(approved) { item ->
            onItemClick(item, isPending = false)
        }
    }

    private fun onItemClick(item: MemoryItem, isPending: Boolean) {
        if (isPending && !mainViewModel.isProjectAdmin) {
            Toast.makeText(requireContext(), R.string.review_permission_denied, Toast.LENGTH_SHORT).show()
            return
        }
        val projectId = mainViewModel.currentProjectId() ?: return
        if (isPending && mainViewModel.isProjectAdmin) {
            showReviewAction(projectId, item)
        } else {
            ResourceDetailSheet(item.name, item.description, isPending).show(
                childFragmentManager,
                ResourceDetailSheet.TAG
            )
        }
    }

    /** 审核弹窗：通过 / 拒绝 / 仅查看 */
    private fun showReviewAction(projectId: String, item: MemoryItem) {
        val options = arrayOf("通过", "拒绝")
        androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setTitle(item.name)
            .setItems(options) { _, which ->
                viewLifecycleOwner.lifecycleScope.launch {
                    val key = UUID.randomUUID().toString()
                    when (which) {
                        0 -> memoryRepo.approve(projectId, item.id, key)
                        else -> memoryRepo.reject(projectId, item.id, key)
                    }.onSuccess {
                        Toast.makeText(requireContext(), "操作成功", Toast.LENGTH_SHORT).show()
                        loadMemories()
                    }.onFailure {
                        Toast.makeText(requireContext(), "操作失败：${it.message}", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
