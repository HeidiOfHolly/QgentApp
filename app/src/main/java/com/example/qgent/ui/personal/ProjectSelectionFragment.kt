package com.example.qgent.ui.personal

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
import com.example.qgent.data.model.GitHubRepositoryDto
import com.example.qgent.data.model.TeamMemberDto
import com.example.qgent.databinding.FragmentProjectSelectionBinding
import com.example.qgent.viewmodel.NewProjectViewModel

/**
 * 新建项目共享多选页：按 mode 参数区分「邀请成员」或「绑定仓库」。
 * 选中结果写回 NewProjectViewModel，点「完成」返回表单页。
 */
class ProjectSelectionFragment : Fragment() {

    private var _binding: FragmentProjectSelectionBinding? = null
    private val binding get() = _binding!!

    private val newProjectViewModel: NewProjectViewModel by activityViewModels {
        (requireActivity().application as QgentApp).container.newProjectViewModelFactory
    }

    private val adapter = SelectionAdapter { id, checked -> onToggle(id, checked) }
    private val selectedIds = mutableSetOf<String>()

    private var mode = MODE_MEMBERS
    private var memberItems = emptyList<TeamMemberDto>()
    private var repoItems = emptyList<GitHubRepositoryDto>()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentProjectSelectionBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        mode = arguments?.getString(ARG_MODE) ?: MODE_MEMBERS
        val isMembers = mode == MODE_MEMBERS

        binding.ivBack.setOnClickListener { findNavController().navigateUp() }
        binding.tvTitle.text = getString(if (isMembers) R.string.selection_members_title else R.string.selection_repos_title)
        binding.tvEmpty.text = getString(if (isMembers) R.string.selection_members_empty else R.string.selection_repos_empty)
        binding.rvItems.layoutManager = LinearLayoutManager(requireContext())
        binding.rvItems.adapter = adapter
        binding.bnDone.setOnClickListener { confirm() }

        // 预选状态来自向导草稿
        val draft = newProjectViewModel.draft.value
        if (draft != null) {
            selectedIds.clear()
            selectedIds.addAll(if (isMembers) draft.selectedMembers.map { it.userId } else draft.selectedRepos.map { it.id })
        }

        if (isMembers) {
            newProjectViewModel.members.observe(viewLifecycleOwner) { list ->
                memberItems = list
                refresh()
            }
        } else {
            newProjectViewModel.repos.observe(viewLifecycleOwner) { list ->
                repoItems = list
                refresh()
            }
        }

        newProjectViewModel.loadError.observe(viewLifecycleOwner) { msg ->
            msg?.let {
                Toast.makeText(requireContext(), it, Toast.LENGTH_SHORT).show()
                newProjectViewModel.consumeLoadError()
            }
        }
    }

    private fun onToggle(id: String, checked: Boolean) {
        if (checked) selectedIds.add(id) else selectedIds.remove(id)
        refresh()
    }

    private fun refresh() {
        val items = if (mode == MODE_MEMBERS) {
            memberItems.map { SelectionAdapter.Item(it.userId, it.displayName, it.userId in selectedIds) }
        } else {
            repoItems.map { SelectionAdapter.Item(it.id, it.fullName, it.id in selectedIds) }
        }
        adapter.submitList(items)
        binding.tvEmpty.isVisible = items.isEmpty()
        binding.rvItems.isVisible = items.isNotEmpty()
    }

    private fun confirm() {
        if (mode == MODE_MEMBERS) {
            newProjectViewModel.setSelectedMembers(memberItems.filter { it.userId in selectedIds })
        } else {
            newProjectViewModel.setSelectedRepos(repoItems.filter { it.id in selectedIds })
        }
        findNavController().navigateUp()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        const val ARG_MODE = "mode"
        const val MODE_MEMBERS = "members"
        const val MODE_REPOS = "repos"
    }
}
