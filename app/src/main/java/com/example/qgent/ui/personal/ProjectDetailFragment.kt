package com.example.qgent.ui.personal

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.Toast
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.qgent.QgentApp
import com.example.qgent.R
import com.example.qgent.data.model.ApiException
import com.example.qgent.data.model.BindProjectRepositoryRequest
import com.example.qgent.data.model.GitHubRepositoryDto
import com.example.qgent.data.model.ProjectMemberDto
import com.example.qgent.data.repository.GitHubRepository
import com.example.qgent.data.repository.UserRepository
import com.example.qgent.databinding.BottomSheetCreateGroupBinding
import com.example.qgent.databinding.FragmentProjectDetailBinding
import com.example.qgent.databinding.ItemChatMemberBinding
import com.example.qgent.databinding.ItemRepositoryBinding
import com.example.qgent.ui.chat.GroupMemberPick
import com.example.qgent.ui.chat.GroupMemberPickAdapter
import com.example.qgent.viewmodel.MainViewModel
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * 项目详情页：展示当前项目的信息（头像/名称/简介），
 * 成员与已绑定仓库为两个下拉分组（三角箭头切换，默认收起）。
 *
 * 项目管理（仅 PROJECT_ADMIN 可见入口）：
 * - 成员：添加成员（可同时设身份）、点击角色标签切换 成员/管理员（PATCH 角色）；
 *   删除成员接口缺失，暂不实现（隐藏）。
 * - 仓库：绑定团队已授权仓库（POST bind）、点击删除图标解绑（DELETE unbind）。
 */
class ProjectDetailFragment : Fragment() {

    private var _binding: FragmentProjectDetailBinding? = null
    private val binding get() = _binding!!
    private val mainViewModel: MainViewModel by activityViewModels {
        (requireActivity().application as QgentApp).container.mainViewModelFactory
    }

    private val userRepository: UserRepository
        get() = (requireActivity().application as QgentApp).container.userRepository
    private val githubRepository: GitHubRepository
        get() = (requireActivity().application as QgentApp).container.githubRepository

    /** 当前用户是否为项目管理员（决定管理入口显隐） */
    private var isAdmin = false

    /** 成员 userId → 显示名（团队成员表反查） */
    private var memberNameById = emptyMap<String, String>()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentProjectDetailBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.ivBack.setOnClickListener { findNavController().navigateUp() }
        binding.tvProjectName.text = mainViewModel.currentProject.value?.takeIf { it.isNotEmpty() }
            ?: getString(R.string.project_detail_title)

        // 两个下拉分组：默认收起，点击头部展开 / 收起（与团队详情页一致）
        bindCollapsibleSection(binding.headerMembers, binding.ivArrowMembers, binding.sectionMembers)
        bindCollapsibleSection(binding.headerRepositories, binding.ivArrowRepositories, binding.sectionRepositories)

        // 管理员管理入口
        binding.tvAddMember.setOnClickListener { showAddMemberDialog() }
        binding.tvAddRepository.setOnClickListener { showBindRepoDialog() }

        val projectId = mainViewModel.currentProjectId()
        if (projectId == null) {
            binding.tvProjectDetailName.text = mainViewModel.currentProject.value
            binding.tvProjectDescription.text = getString(R.string.project_desc_missing)
            return
        }

        binding.tvProjectDetailName.text = mainViewModel.currentProject.value
        loadProjectInfo(projectId)
        // 成员加载时会判定管理员身份并联动渲染仓库管理入口
        loadMembers(projectId)
    }

    /** 项目简介：从团队项目列表查当前项目的 description */
    private fun loadProjectInfo(projectId: String) {
        val teamId = mainViewModel.currentTeamId() ?: return
        viewLifecycleOwner.lifecycleScope.launch {
            userRepository.getProjects(teamId).getOrNull().orEmpty()
                .firstOrNull { it.id == projectId }
                ?.description
                ?.takeIf { it.isNotBlank() }
                ?.let { binding.tvProjectDescription.text = it }
        }
    }

    /** 成员：项目成员 + 团队成员表反查显示名；判定当前用户是否管理员并联动仓库管理入口 */
    private fun loadMembers(projectId: String) {
        val teamId = mainViewModel.currentTeamId()
        viewLifecycleOwner.lifecycleScope.launch {
            memberNameById = if (teamId != null) {
                userRepository.getTeamMembers(teamId).getOrNull().orEmpty()
                    .associate { it.userId to it.displayName }
            } else emptyMap()
            val members = userRepository.getProjectMembers(projectId).getOrNull().orEmpty()
            // 管理员判断以项目详情返回的当前用户有效角色为准（权限方案 v1.1）：
            // Team Owner 兜底 PROJECT_ADMIN 已由后端在 role 体现，不再从成员列表查找自己
            isAdmin = userRepository.getProject(projectId).getOrNull()?.role == "PROJECT_ADMIN"
            binding.tvAddMember.isVisible = isAdmin
            binding.tvAddRepository.isVisible = isAdmin

            binding.tvMembersEmpty.isVisible = members.isEmpty()
            fillLinearLayout(binding.rvMembers, members, R.layout.item_chat_member) { view, member ->
                val item = ItemChatMemberBinding.bind(view)
                item.tvMemberName.text = memberNameById[member.userId] ?: getString(R.string.member_unknown)
                // 角色标签：仅管理员可见；点击切换 成员/管理员（PATCH 角色）
                item.tvMemberRole.isVisible = isAdmin
                item.tvMemberRole.text = roleLabel(member.role)
                item.tvMemberRole.setTextColor(requireContext().getColor(
                    if (member.role == "PROJECT_ADMIN") R.color.teal else R.color.text_secondary
                ))
                item.tvMemberRole.setOnClickListener {
                    toggleMemberRole(projectId, member)
                }
                // 删除成员接口缺失，暂不实现（保持隐藏）
                item.ivDeleteMember.isVisible = false
            }
            loadRepositories(projectId)
        }
    }

    private fun roleLabel(role: String): String = when (role) {
        "PROJECT_ADMIN" -> "管理员"
        else -> "成员"
    }

    /** 切换成员身份：成员 ↔ 管理员（PATCH /projects/{id}/members/{userId}，§5.2） */
    private fun toggleMemberRole(projectId: String, member: ProjectMemberDto) {
        val name = memberNameById[member.userId] ?: getString(R.string.member_unknown)
        val newRole = if (member.role == "PROJECT_ADMIN") "PROJECT_MEMBER" else "PROJECT_ADMIN"
        val newLabel = roleLabel(newRole)
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("调整成员身份")
            .setMessage("将 $name 设为$newLabel？")
            .setNegativeButton("取消", null)
            .setPositiveButton("确认") { _, _ ->
                viewLifecycleOwner.lifecycleScope.launch {
                    userRepository.updateProjectMemberRole(projectId, member.userId, newRole, UUID.randomUUID().toString())
                        .onSuccess {
                            Toast.makeText(requireContext(), "已将 $name 设为$newLabel", Toast.LENGTH_SHORT).show()
                            loadMembers(projectId)
                        }
                        .onFailure { e ->
                            Toast.makeText(requireContext(), "设置失败：${e.message}", Toast.LENGTH_LONG).show()
                        }
                }
            }
            .show()
    }

    /** 添加成员：列出团队中未加入当前项目的成员，勾选后可设置身份（与群设置页一致） */
    private fun showAddMemberDialog() {
        val projectId = mainViewModel.currentProjectId() ?: return
        val teamId = mainViewModel.currentTeamId() ?: return
        val dialog = BottomSheetDialog(requireContext())
        val sheetBinding = BottomSheetCreateGroupBinding.inflate(layoutInflater)
        dialog.setContentView(sheetBinding.root)
        sheetBinding.tvSheetTitle.text = getString(R.string.action_add_member)
        sheetBinding.tvSheetSubtitle.text = getString(R.string.add_member_subtitle)
        sheetBinding.etGroupDescription.visibility = View.GONE
        sheetBinding.btnSelectAll.visibility = View.GONE
        sheetBinding.etGroupName.hint = getString(R.string.add_member_select_hint)

        lateinit var pickAdapter: GroupMemberPickAdapter
        pickAdapter = GroupMemberPickAdapter(
            onItemClick = { pickAdapter.toggle(it) },
            onRoleClick = { pickAdapter.toggleRole(it) }
        )
        sheetBinding.rvGroupMembers.layoutManager = LinearLayoutManager(requireContext())
        sheetBinding.rvGroupMembers.adapter = pickAdapter

        viewLifecycleOwner.lifecycleScope.launch {
            val teamMembers = userRepository.getTeamMembers(teamId).getOrElse {
                Toast.makeText(requireContext(), R.string.add_member_failed, Toast.LENGTH_SHORT).show()
                dialog.dismiss()
                return@launch
            }
            val existingIds = userRepository.getProjectMembers(projectId)
                .getOrNull()?.map { it.userId }?.toSet().orEmpty()
            val candidates = teamMembers.filter { it.userId !in existingIds }
            if (candidates.isEmpty()) {
                Toast.makeText(requireContext(), R.string.add_member_empty, Toast.LENGTH_SHORT).show()
                dialog.dismiss()
                return@launch
            }
            pickAdapter.submitList(
                candidates.map { GroupMemberPick(it.userId, it.displayName, "PROJECT_MEMBER") }
            )
        }

        sheetBinding.btnSend.setOnClickListener {
            val selected = pickAdapter.checkedIds()
            if (selected.isEmpty()) {
                Toast.makeText(requireContext(), R.string.add_member_empty, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val roles = pickAdapter.roleById()
            dialog.dismiss()
            addMembers(projectId, selected, roles)
        }
        sheetBinding.btnCancel.setOnClickListener { dialog.dismiss() }
        dialog.show()
    }

    /** 逐个加入项目：POST 加入后按所选身份决定是否 PATCH 升级，汇总成功数量 */
    private fun addMembers(projectId: String, userIds: List<String>, roles: Map<String, String>) {
        if (userIds.isEmpty()) return
        viewLifecycleOwner.lifecycleScope.launch {
            var added = 0
            userIds.forEach { userId ->
                userRepository.addProjectMember(projectId, userId, UUID.randomUUID().toString())
                    .onSuccess {
                        added++
                        if (roles[userId] == "PROJECT_ADMIN") {
                            userRepository.updateProjectMemberRole(projectId, userId, "PROJECT_ADMIN", UUID.randomUUID().toString())
                        }
                    }
            }
            if (added == 0) {
                Toast.makeText(requireContext(), R.string.add_member_failed, Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(requireContext(), getString(R.string.add_member_success, added), Toast.LENGTH_SHORT).show()
                loadMembers(projectId)
            }
        }
    }

    /** 已绑定仓库列表：管理员可解绑（删除） */
    private fun loadRepositories(projectId: String) {
        viewLifecycleOwner.lifecycleScope.launch {
            val repos = githubRepository.getProjectRepositories(projectId).getOrNull().orEmpty()
            binding.tvRepositoriesEmpty.isVisible = repos.isEmpty()
            fillLinearLayout(binding.rvRepositories, repos, R.layout.item_repository) { view, repo ->
                val item = ItemRepositoryBinding.bind(view)
                item.tvRepositoryName.text = repo.fullName
                item.tvBoundStatus.isVisible = false
                // REVOKED 死绑定标红提示（授权已撤销但仍绑定）
                item.tvRepositoryStatus.isVisible = repo.authorizationStatus == "REVOKED"
                // 管理员可解绑
                item.ivDeleteRepository.isVisible = isAdmin
                item.ivDeleteRepository.setOnClickListener { unbindRepo(projectId, repo.id, repo.fullName) }
            }
        }
    }

    /** 解绑仓库（DELETE /projects/{id}/repositories/{id}） */
    private fun unbindRepo(projectId: String, projectRepositoryId: String, name: String) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("解绑仓库")
            .setMessage("确认将仓库 $name 从项目解绑？")
            .setNegativeButton("取消", null)
            .setPositiveButton("解绑") { _, _ ->
                viewLifecycleOwner.lifecycleScope.launch {
                    githubRepository.unbindProjectRepository(projectId, projectRepositoryId, UUID.randomUUID().toString())
                        .onSuccess {
                            Toast.makeText(requireContext(), "已解绑 $name", Toast.LENGTH_SHORT).show()
                            loadRepositories(projectId)
                        }
                        .onFailure { e ->
                            // 软解绑：仓库正被进行中的任务使用，无法解绑（后端接入活动占用校验后生效）
                            val msg = if (e is ApiException && e.code == "PROJECT_REPOSITORY_IN_USE") {
                                "仓库正被进行中的任务使用，无法解绑"
                            } else {
                                "解绑失败：${e.message}"
                            }
                            Toast.makeText(requireContext(), msg, Toast.LENGTH_LONG).show()
                        }
                }
            }
            .show()
    }

    /** 绑定仓库：列出团队已授权且未绑定的仓库，勾选后逐个绑定 */
    private fun showBindRepoDialog() {
        val projectId = mainViewModel.currentProjectId() ?: return
        val teamId = mainViewModel.currentTeamId() ?: return
        viewLifecycleOwner.lifecycleScope.launch {
            val boundIds = githubRepository.getProjectRepositories(projectId).getOrNull().orEmpty()
                .map { it.repositoryId }.toSet()
            val candidates = githubRepository.getGithubRepositories(teamId).getOrNull().orEmpty()
                .filter { it.id !in boundIds && it.authorizationStatus == "AUTHORIZED" }
            if (candidates.isEmpty()) {
                Toast.makeText(requireContext(), "暂无可绑定仓库（需先在团队中授权 GitHub 仓库）", Toast.LENGTH_SHORT).show()
                return@launch
            }
            val container = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(48, 16, 48, 8)
            }
            val checks = mutableListOf<android.widget.CheckBox>()
            candidates.forEach { repo ->
                val cb = android.widget.CheckBox(requireContext()).apply {
                    text = repo.fullName
                    textSize = 14f
                    isChecked = false
                }
                checks.add(cb)
                container.addView(cb)
            }
            MaterialAlertDialogBuilder(requireContext())
                .setTitle("绑定仓库")
                .setView(container)
                .setNegativeButton("取消", null)
                .setPositiveButton("确认") { _, _ ->
                    val selected = candidates.filterIndexed { index, _ -> checks[index].isChecked }
                    bindRepos(projectId, selected)
                }
                .show()
        }
    }

    /** 逐个绑定选中的团队授权仓库（POST /projects/{id}/repositories） */
    private fun bindRepos(projectId: String, selected: List<GitHubRepositoryDto>) {
        if (selected.isEmpty()) return
        viewLifecycleOwner.lifecycleScope.launch {
            var ok = 0
            selected.forEach { repo ->
                githubRepository.bindProjectRepository(
                    projectId,
                    UUID.randomUUID().toString(),
                    BindProjectRepositoryRequest(repo.installationId, repo.id, repo.fullName)
                ).onSuccess { ok++ }
            }
            Toast.makeText(requireContext(), "已绑定 $ok 个仓库", Toast.LENGTH_SHORT).show()
            loadRepositories(projectId)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
