package com.example.qgent.ui.personal

import android.animation.ObjectAnimator
import android.app.AlertDialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.navigation.fragment.findNavController
import com.example.qgent.R
import com.example.qgent.databinding.FragmentTeamDetailBinding
import com.example.qgent.viewmodel.MainViewModel

/**
 * 我管理的团队详情页：管理成员 / 管理项目两个下拉分组（默认收起，展开时顶部有增加按钮），底部解散团队。
 */
class TeamDetailFragment : Fragment() {

    private var _binding: FragmentTeamDetailBinding? = null
    private val binding get() = _binding!!
    private val mainViewModel: MainViewModel by activityViewModels()

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

        binding.ivBack.setOnClickListener { findNavController().navigateUp() }
        binding.tvTeamName.text = teamName

        // 两个三角下拉分组：默认收起，点击头部展开 / 收起
        bindSection(binding.headerMembers, binding.ivArrowMembers, binding.sectionMembers)
        bindSection(binding.headerProjects, binding.ivArrowProjects, binding.sectionProjects)

        // 展开时分组顶部显示增加按钮
        binding.btnAddMember.setOnClickListener { showTodoToast() }
        binding.btnAddProject.setOnClickListener { showTodoToast() }

        // mock 成员
        renderMembers(mockMembers(teamName))

        // 项目来自 MainViewModel（mock 回退）
        renderProjects(mainViewModel.projectsOf(teamName))

        binding.btnDissolveTeam.setOnClickListener { confirmDissolveTeam() }
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

    private fun renderMembers(members: List<String>) {
        binding.containerMembers.removeAllViews()
        for (name in members) {
            val row = layoutInflater.inflate(
                R.layout.item_chat_member, binding.containerMembers, false
            )
            row.findViewById<TextView>(R.id.tvMemberName)?.text = name
            binding.containerMembers.addView(row)
        }
    }

    private fun renderProjects(projects: List<String>) {
        binding.containerProjects.removeAllViews()
        for (name in projects) {
            val row = layoutInflater.inflate(
                R.layout.item_project, binding.containerProjects, false
            )
            row.findViewById<TextView>(R.id.tvProjectName)?.text = name
            binding.containerProjects.addView(row)
        }
    }

    private fun mockMembers(teamName: String): List<String> =
        when (teamName) {
            "团队A" -> listOf("张三", "李四", "王五", "赵六")
            "团队D" -> listOf("小明", "小红", "小刚")
            else -> listOf("张三", "李四")
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
    }
}
