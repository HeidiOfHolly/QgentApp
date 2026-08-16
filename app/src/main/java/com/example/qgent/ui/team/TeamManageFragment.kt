package com.example.qgent.ui.team

import android.app.Dialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Toast
import androidx.appcompat.widget.PopupMenu
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.example.qgent.QgentApp
import com.example.qgent.R
import com.example.qgent.data.model.TeamDto
import com.example.qgent.databinding.DialogJoinTeamBinding
import com.example.qgent.databinding.DialogNewTewmBinding
import com.example.qgent.databinding.FragmentTeamManageBinding
import com.example.qgent.databinding.ItemTeamManageBinding
import com.example.qgent.ui.personal.bindCollapsibleSection
import com.example.qgent.ui.personal.fillLinearLayout
import com.example.qgent.ui.personal.newInputDialog
import com.example.qgent.viewmodel.MainViewModel
import kotlinx.coroutines.launch
import java.util.UUID

/** 团队管理页：三角下拉分组展示“我加入的 / 我创建的”团队，右上角可创建团队 */
class TeamManageFragment : Fragment() {

    private var _binding: FragmentTeamManageBinding? = null
    private val binding get() = _binding!!
    private val mainViewModel: MainViewModel by activityViewModels {
        (requireActivity().application as QgentApp).container.mainViewModelFactory
    }

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

        // 两个三角下拉分组：点击头部收起 / 展开
        bindCollapsibleSection(binding.headerJoined, binding.ivArrowJoined, binding.rvJoined)
        bindCollapsibleSection(binding.headerCreated, binding.ivArrowCreated, binding.rvCreated)

        // 按 role 拆分团队：TEAM_OWNER → 我创建的，其余 → 我加入的
        mainViewModel.teamDtos.observe(viewLifecycleOwner) { teams ->
            val created = teams.filter { it.role == "TEAM_OWNER" }
            val joined = teams.filter { it.role != "TEAM_OWNER" }
            fillLinearLayout(binding.rvJoined, joined, R.layout.item_team_manage) { view, team ->
                bindTeamItem(view, team, isOwner = false)
            }
            fillLinearLayout(binding.rvCreated, created, R.layout.item_team_manage) { view, team ->
                bindTeamItem(view, team, isOwner = true)
            }
        }
    }

    /** 填充单个团队列表项：团队名 + 成员数 + 点击进详情 */
    private fun bindTeamItem(view: View, team: TeamDto, isOwner: Boolean) {
        val item = ItemTeamManageBinding.bind(view)
        item.tvTeamName.text = team.name
        item.tvMemberCount.text = getString(R.string.team_member_count, team.memberCount)
        item.root.setOnClickListener {
            mainViewModel.setCurrentTeam(team.name)
            navigateToTeamDetail(team, isOwner)
        }
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

    /** 加入团队：输入邀请码调用接受邀请接口，成功后刷新团队列表（与其余入口保持一致） */
    private fun showJoinTeamDialog() {
        val dialogBinding = DialogJoinTeamBinding.inflate(layoutInflater)
        val dialog = Dialog(requireContext()).apply {
            setContentView(dialogBinding.root)
            window?.setBackgroundDrawableResource(R.drawable.bg_card)
            window?.setLayout(
                (resources.displayMetrics.widthPixels * 0.85f).toInt(),
                WindowManager.LayoutParams.WRAP_CONTENT
            )
        }

        dialogBinding.etCode.doAfterTextChanged {
            if (dialogBinding.codeLayout.error != null) dialogBinding.codeLayout.error = null
        }

        dialogBinding.btnJoin.setOnClickListener {
            val reference = dialogBinding.etCode.text.toString().trim()
            if (reference.isEmpty()) {
                dialogBinding.codeLayout.error = getString(R.string.error_join_code_required)
                return@setOnClickListener
            }
            dialogBinding.btnJoin.isEnabled = false
            val userRepository = (requireActivity().application as QgentApp).container.userRepository
            viewLifecycleOwner.lifecycleScope.launch {
                userRepository.acceptTeamInvitation(reference, UUID.randomUUID().toString())
                    .onSuccess {
                        dialog.dismiss()
                        Toast.makeText(requireContext(), R.string.join_team_success, Toast.LENGTH_SHORT).show()
                        mainViewModel.refreshTeams()
                    }
                    .onFailure {
                        dialogBinding.btnJoin.isEnabled = true
                        Toast.makeText(
                            requireContext(),
                            it.message ?: getString(R.string.join_team_failed),
                            Toast.LENGTH_SHORT
                        ).show()
                    }
            }
        }

        dialog.show()
    }

    /** 创建团队：输入团队名称 / 简介后调创建接口，成功后刷新团队列表 */
    private fun showCreateTeamDialog() {
        val dialogBinding = DialogNewTewmBinding.inflate(layoutInflater)
        val dialog = newInputDialog(dialogBinding.root)

        dialogBinding.etName.doAfterTextChanged {
            if (dialogBinding.nameLayout.error != null) dialogBinding.nameLayout.error = null
        }

        dialogBinding.bnNewTeam.setOnClickListener {
            val name = dialogBinding.etName.text.toString().trim()
            if (name.isEmpty()) {
                dialogBinding.nameLayout.error = getString(R.string.error_team_name_required)
                return@setOnClickListener
            }
            val description = dialogBinding.etInformation.text.toString().trim().ifEmpty { null }
            dialogBinding.bnNewTeam.isEnabled = false
            val userRepository = (requireActivity().application as QgentApp).container.userRepository
            viewLifecycleOwner.lifecycleScope.launch {
                userRepository.createTeam(name, description, UUID.randomUUID().toString())
                    .onSuccess {
                        dialog.dismiss()
                        Toast.makeText(requireContext(), R.string.team_create_success, Toast.LENGTH_SHORT).show()
                        mainViewModel.refreshTeams()
                    }
                    .onFailure { e ->
                        dialogBinding.bnNewTeam.isEnabled = true
                        Toast.makeText(
                            requireContext(),
                            e.message ?: getString(R.string.error_team_create_failed),
                            Toast.LENGTH_SHORT
                        ).show()
                    }
            }
        }
        dialog.show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
