package com.example.qgent.ui.tasks

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.qgent.QgentApp
import com.example.qgent.R
import com.example.qgent.data.model.TestsetResponseDto
import com.example.qgent.data.repository.TaskRepository
import com.example.qgent.databinding.FragmentTestsetListBinding
import com.example.qgent.viewmodel.MainViewModel
import com.example.qgent.ui.personal.setInlineSkeletonLoading
import kotlinx.coroutines.launch

/** Testset 列表页：展示当前项目全部 Testset（§10，名称/仓库/状态/执行命令） */
class TestsetListFragment : Fragment() {

    private var _binding: FragmentTestsetListBinding? = null
    private val binding get() = _binding!!

    private val mainViewModel: MainViewModel by activityViewModels {
        (requireActivity().application as QgentApp).container.mainViewModelFactory
    }
    private val taskRepository: TaskRepository
        get() = (requireActivity().application as QgentApp).container.taskRepository

    private val adapter = TestsetAdapter()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentTestsetListBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.ivBack.setOnClickListener { findNavController().navigateUp() }
        binding.rvTestsetList.layoutManager = LinearLayoutManager(requireContext())
        binding.rvTestsetList.adapter = adapter
        loadTestsets()
    }

    private fun loadTestsets() {
        val projectId = mainViewModel.currentProjectId() ?: return
        viewLifecycleOwner.lifecycleScope.launch {
            val initialLoad = adapter.itemCount == 0
            if (initialLoad) {
                binding.tvEmpty.isVisible = false
                setInlineSkeletonLoading(binding.rvTestsetList, true)
            }
            try {
                taskRepository.getTestsets(projectId)
                    .onSuccess { list ->
                        adapter.submitList(list)
                        binding.tvEmpty.isVisible = list.isEmpty()
                    }
                    .onFailure { e ->
                        binding.tvEmpty.isVisible = true
                        binding.tvEmpty.text = "TestSet 加载失败"
                        Toast.makeText(requireContext(), "加载失败：${e.message}", Toast.LENGTH_SHORT).show()
                    }
            } finally {
                if (initialLoad) setInlineSkeletonLoading(binding.rvTestsetList, false)
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    /** Testset 列表适配器：一行 = 名称 + 仓库 + 状态 + 执行命令 */
    private class TestsetAdapter : RecyclerView.Adapter<TestsetAdapter.VH>() {

        private val items = mutableListOf<TestsetResponseDto>()

        fun submitList(newItems: List<TestsetResponseDto>) {
            items.clear()
            items.addAll(newItems)
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_testset, parent, false)
            return VH(v)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val ts = items[position]
            holder.tvName.text = ts.name
            holder.tvRepo.text = "仓库：${ts.repositoryId ?: "-"}"
            holder.tvStatus.text = if (ts.status == "ENABLED") "已启用" else "已禁用"
            holder.tvStatus.setTextColor(
                holder.itemView.context.getColor(
                    if (ts.status == "ENABLED") R.color.teal else R.color.text_secondary
                )
            )
            holder.tvCommand.text = "命令：${ts.definition?.command ?: "-"}"
        }

        override fun getItemCount(): Int = items.size

        class VH(view: View) : RecyclerView.ViewHolder(view) {
            val tvName: TextView = view.findViewById(R.id.tvTestsetName)
            val tvRepo: TextView = view.findViewById(R.id.tvTestsetRepo)
            val tvStatus: TextView = view.findViewById(R.id.tvTestsetStatus)
            val tvCommand: TextView = view.findViewById(R.id.tvTestsetCommand)
        }
    }
}
