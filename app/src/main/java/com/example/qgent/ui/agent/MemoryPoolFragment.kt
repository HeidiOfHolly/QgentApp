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
import com.example.qgent.QgentApp
import com.example.qgent.R
import com.example.qgent.databinding.FragmentMemoryPoolBinding
import com.example.qgent.model.MemoryItem
import com.example.qgent.model.ResourceStatus
import com.example.qgent.viewmodel.MainViewModel

class MemoryPoolFragment : Fragment() {

    private var _binding: FragmentMemoryPoolBinding? = null
    private val binding get() = _binding!!
    private val mainViewModel: MainViewModel by activityViewModels {
        (requireActivity().application as QgentApp).container.mainViewModelFactory
    }

    private val mockPending = listOf(
        MemoryItem("m1", "登录状态持久化方案", "描述了跨 Activity 的登录状态管理策略，建议使用 SharedPreferences 配合 LiveData 实现全局登录状态同步，避免在多个 Activity 中重复检查。", ResourceStatus.PENDING),
        MemoryItem("m2", "RSA 加密流程说明", "前后端 RSA 公钥加密流程的详细说明与注意事项，包含密钥长度选择、填充方式配置以及前后端传输过程中的编码规范。", ResourceStatus.PENDING)
    )

    private val mockApproved = listOf(
        MemoryItem("m3", "React 组件规范", "统一项目 React 组件命名、文件结构与状态管理规范", ResourceStatus.APPROVED),
        MemoryItem("m4", "API 接口约定", "RESTful API 统一返回格式、分页与错误码约定", ResourceStatus.APPROVED),
        MemoryItem("m5", "Git 提交规范", "Conventional Commits 格式要求与分支命名规则", ResourceStatus.APPROVED)
    )

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

        // 审核队列
        binding.rvReviewList.layoutManager = LinearLayoutManager(requireContext())
        binding.rvReviewList.adapter = PoolResourceAdapter(mockPending) { name, desc ->
            onItemClick(name, desc, isPending = true)
        }
        binding.tvReviewEmpty.isVisible = mockPending.isEmpty()

        // 共享池
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
