package com.example.qgent.ui.team

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.PopupMenu
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.navigation.fragment.findNavController
import com.example.qgent.QgentApp
import com.example.qgent.R
import com.example.qgent.data.model.TeamDto
import com.example.qgent.databinding.DialogNewTewmBinding
import com.example.qgent.databinding.FragmentTeamManageBinding
import com.example.qgent.ui.personal.bindCollapsibleSection
import com.example.qgent.ui.personal.newInputDialog
import com.example.qgent.ui.personal.setupRecyclerList
import com.example.qgent.viewmodel.MainViewModel

/** 团队管理页：三角下拉分组展示“我加入的 / 我创建的”团队，右上角可创建团队 */
class TeamManageFragment : Fragment() {

    private var _binding: FragmentTeamManageBinding? = null
    private val binding get() = _binding!!
    private val mainViewModel: MainViewModel by activityViewModels {
        (requireActivity().application as QgentApp).container.mainViewModelFactory
    }

    private val joinedAdapter = TeamManageAdapter { onJoinTeamClick(it) }
    private val createdAdapter = TeamManageAdapter { onCreateTeamClick(it) }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentTeamManageBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.ivBack.setOnClickListener { findNavController().navigateUp() }
        binding.btnTeamMenu.setOnClickListener { showTeamMenu() }

        setupRecyclerList(binding.rvJoined, joinedAdapter)
        setupRecyclerList(binding.rvCreated, createdAdapter)

        // 两个三角下拉分组：点击头部收起 / 展开
        bindCollapsibleSection(binding.headerJoined, binding.ivArrowJoined, binding.rvJoined)
        bindCollapsibleSection(binding.headerCreated, binding.ivArrowCreated, binding.rvCreated)

        // 按 role 拆分团队：TEAM_OWNER → 我创建的，其余 → 我加入的
        mainViewModel.teamDtos.observe(viewLifecycleOwner) { teams ->
            val created = teams.filter { it.role == "TEAM_OWNER" }
            val joined = teams.filter { it.role != "TEAM_OWNER" }
            createdAdapter.submitList(created)
            joinedAdapter.submitList(joined)
        }
    }

    private fun onJoinTeamClick(team: TeamDto) {
        mainViewModel.setCurrentTeam(team.name)
        navigateToTeamDetail(team, isOwner = false)
    }

    /** 我创建的团队 → 进入团队详情页管理成员 / 项目 */
    private fun onCreateTeamClick(team: TeamDto) {
        navigateToTeamDetail(team, isOwner = true)
    }

    /** isOwner：我创建的团队 → 详情页底部显示「解散团队」；我加入的 → 显示「退出团队」 */
    private fun navigateToTeamDetail(team: TeamDto, isOwner: Boolean) {
        val bundle = Bundle().apply {
            putString(TeamDetailFragment.ARG_TEAM_NAME, team.name)
            putString(TeamDetailFragment.ARG_TEAM_ID, team.id)
            putBoolean(TeamDetailFragment.ARG_IS_OWNER, isOwner)
        }
        findNavController().navigate(R.id.teamDetailFragment, bundle)
    }

    /** 右上角菜单：创建团队 / 加入团队 */
    private fun showTeamMenu() {
        PopupMenu(requireContext(), binding.btnTeamMenu).apply {
            menuInflater.inflate(R.menu.menu_team_manage, menu)
            setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    R.id.action_create_team -> showCreateTeamDialog()
                    R.id.action_join_team -> showJoinTeamDialog()
                }
                true
            }
            show()
        }
    }

    /** 加入团队：输入团队邀请码后加入（后端接口待接入，先占位提示） */
    private fun showJoinTeamDialog() {
        val input = EditText(requireContext()).apply {
            hint = getString(R.string.join_team_invite_hint)
        }
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.action_join_team)
            .setView(input)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton("确定") { _, _ ->
                Toast.makeText(requireContext(), R.string.join_team_placeholder, Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    /** 创建团队：弹出输入团队名称 / 简介的弹窗（创建 API 待后端就绪后接入） */
    private fun showCreateTeamDialog() {
        val dialogBinding = DialogNewTewmBinding.inflate(layoutInflater)
        val dialog = newInputDialog(dialogBinding.root)

        dialogBinding.etName.doAfterTextChanged {
            if (dialogBinding.nameLayout.error != null) dialogBinding.nameLayout.error = null
        }

        dialogBinding.bnNewTeam.setOnClickListener {
            if (dialogBinding.etName.text.toString().trim().isEmpty()) {
                dialogBinding.nameLayout.error = getString(R.string.error_team_name_required)
                return@setOnClickListener
            }
            dialog.dismiss()
            // 创建团队 API 待后端就绪后接入；先跳转 GitHub 页配置仓库
            findNavController().navigate(R.id.githubFragment)
        }
        dialog.show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
