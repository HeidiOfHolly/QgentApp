package com.example.qgent.ui.github

import android.app.AlertDialog
import android.app.Dialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Toast
import androidx.core.os.bundleOf
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.GridLayoutManager
import com.example.qgent.MainActivity
import com.example.qgent.QgentApp
import com.example.qgent.R
import com.example.qgent.data.SessionStore
import com.example.qgent.data.model.TeamDto
import com.example.qgent.databinding.DialogSelectTeamBinding
import com.example.qgent.databinding.FragmentGithubBinding
import com.example.qgent.databinding.ItemSelectTeamBinding
import com.example.qgent.ui.personal.fillLinearLayout
import com.example.qgent.viewmodel.MainViewModel

/** GitHub 页：网格展示团队仓库概况，列表项使用 item_team_github */
class GithubFragment : Fragment() {

    private var _binding: FragmentGithubBinding? = null
    private val binding get() = _binding!!
    private val mainViewModel: MainViewModel by activityViewModels {
        (requireActivity().application as QgentApp).container.mainViewModelFactory
    }
    private val githubViewModel: GithubViewModel by activityViewModels {
        (requireActivity().application as QgentApp).container.githubViewModelFactory
    }

    private var currentTeams: List<TeamDto> = emptyList()

    private val adapter = GithubTeamAdapter(
        onItemClick = { team ->
            findNavController().navigate(
                R.id.githubAuthorizeFragment,
                bundleOf("teamId" to team.id, "teamName" to team.name)
            )
        },
        onUninstallClick = { team -> confirmUninstall(team) }
    )

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentGithubBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.tvUserName.text = SessionStore.user()?.displayName ?: getString(R.string.user_name_placeholder)

        // 头像 → 打开个人中心抽屉（与群聊列表页一致）
        binding.btnAvatar.setOnClickListener {
            (activity as? MainActivity)?.openDrawer()
        }

        binding.rvGithubTeams.layoutManager = GridLayoutManager(requireContext(), 2)
        binding.rvGithubTeams.adapter = adapter

        // 右上角加号：选择我创建的团队后进入新建项目页
        binding.tvAddTeam.setOnClickListener { showSelectTeamDialog() }

        mainViewModel.teamDtos.observe(viewLifecycleOwner) { teams ->
            // GitHub 授权是团队级能力，仅展示我创建的团队（TEAM_OWNER）
            currentTeams = teams.filter { it.role == "TEAM_OWNER" }
            adapter.submitList(currentTeams)
            binding.tvEmpty.isVisible = currentTeams.isEmpty()
            githubViewModel.loadRepositoryCounts(currentTeams.map { it.id })
        }

        githubViewModel.uiState.observe(viewLifecycleOwner) { state ->
            adapter.updateRepoCounts(state.repoCounts)
            if (state.uninstallDone) {
                Toast.makeText(requireContext(), R.string.github_uninstall_success, Toast.LENGTH_SHORT).show()
                githubViewModel.consumeUninstallDone()
            }
            state.error?.let {
                Toast.makeText(requireContext(), it, Toast.LENGTH_SHORT).show()
                githubViewModel.consumeError()
            }
        }
    }

    /** 解除安装确认弹窗：确认后解除该团队的全部 GitHub 安装 */
    private fun confirmUninstall(team: TeamDto) {
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.github_uninstall_confirm_title)
            .setMessage(R.string.github_uninstall_confirm_message)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.github_uninstall) { _, _ ->
                githubViewModel.uninstallTeam(team.id)
            }
            .show()
    }

    /** 右上角加号：从「我创建的团队」单选一个，下一步进入新建项目页 */
    private fun showSelectTeamDialog() {
        val dialogBinding = DialogSelectTeamBinding.inflate(layoutInflater)
        val dialog = Dialog(requireContext()).apply {
            setContentView(dialogBinding.root)
            window?.setBackgroundDrawableResource(R.drawable.bg_card)
            window?.setLayout(
                (resources.displayMetrics.widthPixels * 0.85f).toInt(),
                WindowManager.LayoutParams.WRAP_CONTENT
            )
        }

        // 仅展示我创建的团队（TEAM_OWNER），天然满足新建项目权限
        val createdTeams = mainViewModel.teamDtos.value.orEmpty()
            .filter { it.role == "TEAM_OWNER" }
        if (createdTeams.isEmpty()) {
            dialogBinding.tvEmpty.isVisible = true
        }
        var selectedTeam: TeamDto? = null

        fillLinearLayout(dialogBinding.teamList, createdTeams, R.layout.item_select_team) { view, team ->
            val item = ItemSelectTeamBinding.bind(view)
            item.tvTeamName.text = team.name
            item.tvMemberCount.text = getString(R.string.team_member_count, team.memberCount)
            view.setOnClickListener {
                // 清除上一次选中高亮
                for (i in 0 until dialogBinding.teamList.childCount) {
                    dialogBinding.teamList.getChildAt(i).setBackgroundResource(
                        android.R.color.transparent
                    )
                }
                view.setBackgroundResource(R.drawable.bg_team_selected_navy)
                selectedTeam = team
            }
        }

        dialogBinding.btnNext.setOnClickListener {
            val team = selectedTeam
            if (team == null) {
                Toast.makeText(requireContext(), R.string.select_team_required, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            dialog.dismiss()
            findNavController().navigate(
                R.id.newProjectFragment,
                bundleOf("teamId" to team.id)
            )
        }

        dialog.show()
    }

    override fun onResume() {
        super.onResume()
        githubViewModel.loadRepositoryCounts(currentTeams.map { it.id })
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
