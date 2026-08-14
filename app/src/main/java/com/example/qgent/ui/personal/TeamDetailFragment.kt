package com.example.qgent.ui.personal

import android.animation.ObjectAnimator
import android.app.AlertDialog
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
import com.example.qgent.databinding.FragmentTeamDetailBinding
import com.example.qgent.ui.github.GithubViewModel
import com.example.qgent.viewmodel.MainViewModel

/**
 * 团队详情页：管理成员 / 管理项目 / 管理仓库三个下拉分组（默认收起，展开时顶部有增加按钮），底部解散团队。
 * 成员与仓库列表暂无 API 数据源，等待接口接入；项目列表观察 ViewModel 数据流。
 */
class TeamDetailFragment : Fragment() {

    private var _binding: FragmentTeamDetailBinding? = null
    private val binding get() = _binding!!
    private val mainViewModel: MainViewModel by activityViewModels {
        (requireActivity().application as QgentApp).container.mainViewModelFactory
    }
    private val githubViewModel: GithubViewModel by activityViewModels {
        (requireActivity().application as QgentApp).container.githubViewModelFactory
    }

    private val memberAdapter = TeamMemberAdapter()
    private val projectAdapter = TeamProjectAdapter()
    private val repositoryAdapter = TeamRepositoryAdapter()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentTeamDetailBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val teamName = arguments?.getString(ARG_TEAM_NAME).orEmpty()
        val teamId = arguments?.getString(ARG_TEAM_ID).orEmpty()

        binding.ivBack.setOnClickListener { findNavController().navigateUp() }
        binding.tvTeamName.text = teamName

        // 三个三角下拉分组：默认收起，点击头部展开 / 收起
        bindSection(binding.headerMembers, binding.ivArrowMembers, binding.sectionMembers)
        bindSection(binding.headerProjects, binding.ivArrowProjects, binding.sectionProjects)
        bindSection(binding.headerRepository, binding.ivArrowRepository, binding.sectionRepository)

        // 展开时分组顶部显示增加按钮
        binding.btnAddMember.setOnClickListener { showTodoToast() }
        binding.btnAddProject.setOnClickListener { showTodoToast() }
        binding.btnRepository.setOnClickListener { showTodoToast() }

        setupList(binding.rvMembers, memberAdapter)
        setupList(binding.rvProjects, projectAdapter)
        setupList(binding.rvRepository, repositoryAdapter)

        // 项目列表来自 MainViewModel（真实数据流）
        mainViewModel.projects.observe(viewLifecycleOwner) { projectAdapter.submitList(it) }

        // 仓库列表来自 GitHub 授权仓库（fullName）；成员列表暂无数据源
        githubViewModel.uiState.observe(viewLifecycleOwner) { state ->
            repositoryAdapter.submitList(state.repositories.map { it.fullName })
        }
        if (teamId.isNotEmpty()) {
            githubViewModel.loadRepositories(teamId)
        }

        binding.btnDissolveTeam.setOnClickListener { confirmDissolveTeam() }
    }

    private fun setupList(
        rv: androidx.recyclerview.widget.RecyclerView,
        adapter: androidx.recyclerview.widget.RecyclerView.Adapter<*>
    ) {
        rv.layoutManager = LinearLayoutManager(requireContext())
        rv.adapter = adapter
    }

    private fun bindSection(header: View, arrow: View, content: View) {
        header.setOnClickListener {
            val expanded = content.isVisible
            content.isVisible = !expanded
            // 三角形转向：收起 90°，展开 180°
            ObjectAnimator.ofFloat(arrow, View.ROTATION, if (expanded) 90f else 180f)
                .setDuration(180)
                .start()
        }
    }

    private fun confirmDissolveTeam() {
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.dissolve_team)
            .setMessage(R.string.dissolve_team_confirm)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.dissolve_team) { _, _ ->
                Toast.makeText(requireContext(), R.string.dissolve_team_placeholder, Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    private fun showTodoToast() {
        Toast.makeText(requireContext(), R.string.todo_placeholder, Toast.LENGTH_SHORT).show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        const val ARG_TEAM_NAME = "teamName"
        const val ARG_TEAM_ID = "teamId"
    }
}
