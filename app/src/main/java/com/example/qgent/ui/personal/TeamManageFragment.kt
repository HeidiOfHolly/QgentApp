package com.example.qgent.ui.personal

import android.animation.ObjectAnimator
import android.app.Dialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Toast
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.qgent.R
import com.example.qgent.data.model.TeamDto
import com.example.qgent.databinding.DialogNewTewmBinding
import com.example.qgent.databinding.FragmentTeamManageBinding
import com.example.qgent.viewmodel.MainViewModel

/** 团队管理页：三角下拉分组展示“我加入的 / 我创建的”团队，右上角可创建团队 */
class TeamManageFragment : Fragment() {

    private var _binding: FragmentTeamManageBinding? = null
    private val binding get() = _binding!!
    private val mainViewModel: MainViewModel by activityViewModels()

    private val joinedAdapter = TeamManageAdapter { onTeamClick(it) }
    private val createdAdapter = TeamManageAdapter { onTeamClick(it) }

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
        binding.btnCreateTeam.setOnClickListener { showCreateTeamDialog() }

        setupList(binding.rvJoined, joinedAdapter)
        setupList(binding.rvCreated, createdAdapter)

        // 两个三角下拉分组：点击头部收起 / 展开
        bindSection(binding.headerJoined, binding.ivArrowJoined, binding.rvJoined)
        bindSection(binding.headerCreated, binding.ivArrowCreated, binding.rvCreated)

        // 按 role 拆分团队：TEAM_OWNER → 我创建的，其余 → 我加入的
        mainViewModel.teamDtos.observe(viewLifecycleOwner) { teams ->
            val created = teams.filter { it.role == "TEAM_OWNER" }
            val joined = teams.filter { it.role != "TEAM_OWNER" }
            createdAdapter.submitList(created)
            joinedAdapter.submitList(joined)
        }
    }

    private fun setupList(
        rv: androidx.recyclerview.widget.RecyclerView,
        adapter: TeamManageAdapter
    ) {
        rv.layoutManager = LinearLayoutManager(requireContext())
        rv.adapter = adapter
    }

    private fun bindSection(
        header: LinearLayout,
        arrow: ImageView,
        content: View
    ) {
        header.setOnClickListener {
            val expanded = content.isVisible
            content.isVisible = !expanded
            //三角形转向
            ObjectAnimator.ofFloat(arrow, View.ROTATION, if (expanded) 90f else 180f)
                .setDuration(180)
                .start()
        }
    }

    private fun onTeamClick(team: TeamDto) {
        mainViewModel.setCurrentTeam(team.name)
        findNavController().navigateUp()
    }

    /** 创建团队：弹出输入团队名称 / 简介的弹窗（创建 API 待后端就绪后接入） */
    private fun showCreateTeamDialog() {
        val dialog = Dialog(requireContext())
        val dialogBinding = DialogNewTewmBinding.inflate(layoutInflater)
        dialog.setContentView(dialogBinding.root)
        dialog.window?.setBackgroundDrawableResource(R.drawable.bg_card)

        dialogBinding.etName.doAfterTextChanged {
            if (dialogBinding.nameLayout.error != null) dialogBinding.nameLayout.error = null
        }

        dialogBinding.bnNewTeam.setOnClickListener {
            if (dialogBinding.etName.text.toString().trim().isEmpty()) {
                dialogBinding.nameLayout.error = getString(R.string.error_team_name_required)
                return@setOnClickListener
            }
            dialog.dismiss()
            Toast.makeText(requireContext(), R.string.todo_placeholder, Toast.LENGTH_SHORT).show()
        }
        dialog.show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
