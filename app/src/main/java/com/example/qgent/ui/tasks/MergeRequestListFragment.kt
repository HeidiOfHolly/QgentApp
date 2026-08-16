package com.example.qgent.ui.tasks

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
import com.example.qgent.data.repository.GitHubRepository
import com.example.qgent.databinding.FragmentMrListBinding
import com.example.qgent.viewmodel.MainViewModel
import kotlinx.coroutines.launch

/** MR 列表页：展示当前项目的全部 MR（§13），复用 item_mr_card 卡片 */
class MergeRequestListFragment : Fragment() {

    private var _binding: FragmentMrListBinding? = null
    private val binding get() = _binding!!

    private val mainViewModel: MainViewModel by activityViewModels {
        (requireActivity().application as QgentApp).container.mainViewModelFactory
    }
    private val taskListViewModel: TaskListViewModel by activityViewModels {
        (requireActivity().application as QgentApp).container.taskListViewModelFactory
    }
    private val githubRepository: GitHubRepository
        get() = (requireActivity().application as QgentApp).container.githubRepository

    private val mrAdapter = MergeRequestAdapter { mr ->
        findNavController().navigate(
            R.id.action_mrList_to_mrDetail,
            Bundle().apply {
                putString(MergeRequestDetailFragment.ARG_MR_ID, mr.id)
                putString(MergeRequestDetailFragment.ARG_PROJECT_ID, mainViewModel.currentProjectId().orEmpty())
            }
        )
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentMrListBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.ivBack.setOnClickListener { findNavController().navigateUp() }
        binding.rvMrList.layoutManager = LinearLayoutManager(requireContext())
        binding.rvMrList.adapter = mrAdapter

        taskListViewModel.uiState.observe(viewLifecycleOwner) { state ->
            mrAdapter.submitList(state.mergeRequests)
            binding.tvEmpty.isVisible = state.mergeRequests.isEmpty()
            state.error?.let {
                Toast.makeText(requireContext(), it, Toast.LENGTH_SHORT).show()
                taskListViewModel.consumeError()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        val projectId = mainViewModel.currentProjectId() ?: return
        taskListViewModel.loadMergeRequestsForList(projectId)
        loadRepoNameMap(projectId)
    }

    /** 拉取项目绑定仓库，建立 repositoryId → 仓库名 映射供 MR 卡片展示 */
    private fun loadRepoNameMap(projectId: String) {
        viewLifecycleOwner.lifecycleScope.launch {
            githubRepository.getProjectRepositories(projectId)
                .onSuccess { repos ->
                    mrAdapter.updateRepoNames(repos.associate { it.id to it.displayName })
                }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
