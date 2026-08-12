package com.example.qgent.ui.agent

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.qgent.R
import com.example.qgent.databinding.FragmentSkillPoolBinding
import com.example.qgent.model.ResourceStatus
import com.example.qgent.model.SkillItem
import com.example.qgent.viewmodel.MainViewModel

class SkillPoolFragment : Fragment() {

    private var _binding: FragmentSkillPoolBinding? = null
    private val binding get() = _binding!!
    private val mainViewModel: MainViewModel by activityViewModels()

    private val mockPending = listOf(
        SkillItem("s1", "Docker 部署脚本", "自动构建并推送 Docker 镜像到团队私有仓库，支持多阶段构建、缓存优化与环境变量注入，确保构建产物的一致性。", ResourceStatus.PENDING)
    )

    private val mockApproved = listOf(
        SkillItem("s2", "TypeScript 检查", "对修改的 .ts/.tsx 文件运行 tsc --noEmit 并报告类型错误", ResourceStatus.APPROVED),
        SkillItem("s3", "ESLint 格式化", "基于团队 .eslintrc 规则自动修复格式问题", ResourceStatus.APPROVED)
    )

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
        binding.rvReviewList.adapter = PoolResourceAdapter(mockPending) { name, desc ->
            onItemClick(name, desc, isPending = true)
        }
        binding.tvReviewEmpty.isVisible = mockPending.isEmpty()

        binding.tvApprovedCount.text = "共 ${mockApproved.size} 条"
        binding.rvApprovedList.adapter = PoolResourceAdapter(mockApproved) { name, desc ->
            onItemClick(name, desc, isPending = false)
        }
    }

    private fun onItemClick(name: String, desc: String, isPending: Boolean) {
        if (isPending && !mainViewModel.isProjectAdmin) {
            Toast.makeText(requireContext(), R.string.review_permission_denied, Toast.LENGTH_SHORT).show()
            return
        }
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
