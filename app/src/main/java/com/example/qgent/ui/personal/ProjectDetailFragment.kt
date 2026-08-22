package com.example.qgent.ui.personal

import android.content.res.ColorStateList
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.Toast
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.qgent.QgentApp
import com.example.qgent.R
import com.example.qgent.data.SessionStore
import com.example.qgent.data.model.ApiException
import com.example.qgent.data.model.BindProjectRepositoryRequest
import com.example.qgent.data.model.CreateProjectRepositoryRequest
import com.example.qgent.data.model.GitHubInstallationDto
import com.example.qgent.data.model.GitHubRepositoryDto
import com.example.qgent.data.model.ProjectMemberDto
import com.example.qgent.data.repository.GitHubRepository
import com.example.qgent.data.repository.UserRepository
import com.example.qgent.databinding.BottomSheetCreateGroupBinding
import com.example.qgent.databinding.DialogCreateProjectRepositoryBinding
import com.example.qgent.databinding.FragmentProjectDetailBinding
import com.example.qgent.databinding.ItemChatMemberBinding
import com.example.qgent.databinding.ItemRepositoryBinding
import com.example.qgent.ui.chat.GroupMemberPick
import com.example.qgent.ui.chat.GroupMemberPickAdapter
import com.example.qgent.ui.personal.setInlineSkeletonLoading
import com.example.qgent.viewmodel.MainViewModel
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
    /** 仅 Team Owner 可在已有项目内新建并绑定仓库（接口 §44.2）。 */
    private var isTeamOwner = false

    /** 当前项目绑定的仓库数量：仅剩 1 个时禁止解绑（项目至少保留一个仓库） */
    private var boundRepoCount = 0

    /** 按接口文档 §44.3 判断仓库是否可以进入项目绑定流程。 */
    private fun repositoryBindBlockReason(
        repository: GitHubRepositoryDto,
        activeInstallationIds: Set<String>,
    ): String? = when {
        repository.authorizationStatus != "AUTHORIZED" -> "GitHub 授权已撤销"
        repository.archived -> "仓库已归档"
        repository.defaultBranch.isNullOrBlank() -> "仓库尚未初始化，请先创建初始提交"
        repository.installationId !in activeInstallationIds -> "GitHub Installation 不可用"
        else -> null
    }

    /** 成员 userId → 显示名（团队成员表反查） */
    private var memberNameById = emptyMap<String, String>()
    /** userId → 头像 URL（团队成员表反查，成员行展示用） */
    private var memberAvatarById = emptyMap<String, String>()

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
        binding.ivMembersMenu.setOnClickListener { showMembersMenu() }
        binding.tvAddRepository.setOnClickListener { showRepositoryActions() }
        // 退出项目（仅普通成员可见）
        binding.tvExitProject.setOnClickListener { confirmExitProject() }

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

        // 项目头像（§31.1）：点击更换，仅 Project Admin（Team Owner 由后端兜底 PROJECT_ADMIN）
        binding.ivProjectAvatar.setOnClickListener {
            if (isAdmin) {
                pickProjectAvatar.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            } else {
                Toast.makeText(requireContext(), "仅项目管理员可设置项目头像", Toast.LENGTH_SHORT).show()
            }
        }
    }

    /** 项目简介 + 项目头像（§31.1）：从团队项目列表查当前项目的 description / avatarUrl */
    private fun loadProjectInfo(projectId: String) {
        val teamId = mainViewModel.currentTeamId() ?: return
        viewLifecycleOwner.lifecycleScope.launch {
            val project = userRepository.getProjects(teamId).getOrNull().orEmpty()
                .firstOrNull { it.id == projectId }
            project?.description?.takeIf { it.isNotBlank() }
                ?.let { binding.tvProjectDescription.text = it }
            loadProjectAvatar(project?.avatarUrl)
        }
    }

    /** 项目头像显示：有 URL 用 Glide（公共读地址），无则默认项目图标 */
    private fun loadProjectAvatar(avatarUrl: String?) {
        if (avatarUrl.isNullOrBlank()) {
            binding.ivProjectAvatar.imageTintList = ColorStateList.valueOf(requireContext().getColor(R.color.blue_tint))
            binding.ivProjectAvatar.setImageResource(R.drawable.ic_folder)
            return
        }
        binding.ivProjectAvatar.imageTintList = null
        com.bumptech.glide.Glide.with(binding.ivProjectAvatar)
            .load(com.example.qgent.data.api.RetrofitClient.resolveMediaUrl(avatarUrl))
            .centerCrop()
            .placeholder(R.drawable.ic_folder)
            .error(R.drawable.ic_folder)
            .into(binding.ivProjectAvatar)
    }

    private val pickProjectAvatar = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) uploadProjectAvatar(uri)
    }

    /** 项目头像上传（§31.1）：credential → OSS PUT → confirm → PATCH /projects/{id} 回写（仅 Project Admin） */
    private fun uploadProjectAvatar(uri: Uri) {
        val projectId = mainViewModel.currentProjectId() ?: return
        viewLifecycleOwner.lifecycleScope.launch {
            val bytes = withContext(Dispatchers.IO) {
                runCatching { requireContext().contentResolver.openInputStream(uri)?.use { it.readBytes() } }.getOrNull()
            }
            if (bytes == null || bytes.isEmpty()) {
                Toast.makeText(requireContext(), "读取图片失败", Toast.LENGTH_SHORT).show()
                return@launch
            }
            if (bytes.size > AVATAR_MAX_BYTES) {
                Toast.makeText(requireContext(), "头像图片不能超过 5MB", Toast.LENGTH_SHORT).show()
                return@launch
            }
            val mime = requireContext().contentResolver.getType(uri) ?: "image/png"
            Toast.makeText(requireContext(), "正在上传头像…", Toast.LENGTH_SHORT).show()
            val uploader = (requireActivity().application as QgentApp).container.avatarUploader
            uploader.uploadFor(
                mime, bytes.size.toLong(), bytes,
                { key, body -> com.example.qgent.data.api.RetrofitClient.service.createProjectAvatarCredential(projectId, key, body) },
                { key, body -> com.example.qgent.data.api.RetrofitClient.service.confirmProjectAvatar(projectId, key, body) }
            ).onSuccess { avatarUrl ->
                (requireActivity().application as QgentApp).container.userRepository
                    .updateProject(projectId, avatarUrl, UUID.randomUUID().toString())
                    .onSuccess { updatedProject ->
                        loadProjectAvatar(updatedProject.avatarUrl ?: avatarUrl)
                        Toast.makeText(requireContext(), "项目头像已更新", Toast.LENGTH_SHORT).show()
                    }
                    .onFailure { error ->
                        Toast.makeText(requireContext(), "头像已上传，但项目头像保存失败：${error.message}", Toast.LENGTH_LONG).show()
                    }
            }.onFailure { e ->
                val code = (e as? ApiException)?.code
                Toast.makeText(
                    requireContext(),
                    if (code == "AVATAR_STORAGE_NOT_CONFIGURED") "头像上传暂不可用" else "头像上传失败：${e.message}",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    /** 成员：项目成员 + 团队成员表反查显示名；判定当前用户是否管理员并联动仓库管理入口 */
    private fun loadMembers(projectId: String) {
        val teamId = mainViewModel.currentTeamId()
        viewLifecycleOwner.lifecycleScope.launch {
            val initialLoad = binding.rvMembers.childCount == 0
            if (initialLoad) {
                binding.tvMembersEmpty.isVisible = false
                setInlineSkeletonLoading(binding.rvMembers, true)
            }
            try {
            val teamMembers = if (teamId != null) {
                userRepository.getTeamMembers(teamId).getOrNull().orEmpty()
            } else emptyList()
            memberNameById = teamMembers.associate { it.userId to it.displayName }
            memberAvatarById = teamMembers
                .mapNotNull { it.avatarUrl?.takeIf { u -> u.isNotBlank() }?.let { u -> it.userId to u } }
                .toMap()
            val myId = SessionStore.user()?.id
            isTeamOwner = myId != null && teamMembers.any { it.userId == myId && it.role == "TEAM_OWNER" }
            val members = userRepository.getProjectMembers(projectId).getOrNull().orEmpty()
            // 管理员判断以项目详情返回的当前用户有效角色为准（权限方案 v1.1）：
            // Team Owner 兜底 PROJECT_ADMIN 已由后端在 role 体现，不再从成员列表查找自己
            isAdmin = userRepository.getProject(projectId).getOrNull()?.role == "PROJECT_ADMIN"
            // 团长由后端兜底 PROJECT_ADMIN，故 isAdmin 即覆盖「团长与管理员」
            binding.ivMembersMenu.isVisible = isAdmin
            binding.tvAddRepository.isVisible = isAdmin
            // 退出项目仅普通成员可见（团长/管理员隐藏）
            binding.tvExitProject.isVisible = !isAdmin

            // 管理员（PROJECT_ADMIN）置顶，其余保持后端返回顺序
            val sortedMembers = members.sortedByDescending { it.role == "PROJECT_ADMIN" }
            binding.tvMembersEmpty.isVisible = sortedMembers.isEmpty()
            fillLinearLayout(binding.rvMembers, sortedMembers, R.layout.item_chat_member) { view, member ->
                val item = ItemChatMemberBinding.bind(view)
                item.tvMemberName.text = memberNameById[member.userId] ?: getString(R.string.member_unknown)
                // 成员头像（团队成员表反查 avatarUrl，无则默认占位）
                val memberAvatar = memberAvatarById[member.userId]
                if (memberAvatar.isNullOrBlank()) {
                    item.ivMemberAvatar.setImageResource(R.drawable.ic_avatar_default)
                } else {
                    com.bumptech.glide.Glide.with(item.ivMemberAvatar)
                        .load(com.example.qgent.data.api.RetrofitClient.resolveMediaUrl(memberAvatar))
                        .centerCrop()
                        .placeholder(R.drawable.ic_avatar_default)
                        .error(R.drawable.ic_avatar_default)
                        .into(item.ivMemberAvatar)
                }
                // 角色标签：仅管理员可见；点击切换 成员/管理员（PATCH 角色）
                item.tvMemberRole.isVisible = isAdmin
                item.tvMemberRole.text = roleLabel(member.role)
                item.tvMemberRole.setTextColor(requireContext().getColor(
                    if (member.role == "PROJECT_ADMIN") R.color.teal else R.color.text_secondary
                ))
                item.tvMemberRole.setOnClickListener {
                    toggleMemberRole(projectId, member)
                }
                // 删除成员：仅团长/管理员可见，且仅普通成员行显示（管理员/团长不设删除键）
                item.ivDeleteMember.isVisible = isAdmin && member.role == "PROJECT_MEMBER"
                item.ivDeleteMember.setOnClickListener {
                    confirmRemoveMember(projectId, member)
                }
            }
            loadRepositories(projectId)
            } finally {
                if (initialLoad) setInlineSkeletonLoading(binding.rvMembers, false)
            }
        }
    }

    private fun roleLabel(role: String): String = when (role) {
        "PROJECT_ADMIN" -> "管理员"
        else -> "成员"
    }

    /** 确认删除成员：弹窗确认后调移除项目成员接口，成功后刷新成员列表 */
    private fun confirmRemoveMember(projectId: String, member: ProjectMemberDto) {
        val name = memberNameById[member.userId] ?: getString(R.string.member_unknown)
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("删除成员")
            .setMessage("确定将 $name 从项目移除？")
            .setNegativeButton("取消", null)
            .setPositiveButton("删除") { _, _ ->
                viewLifecycleOwner.lifecycleScope.launch {
                    userRepository.removeProjectMember(projectId, member.userId, UUID.randomUUID().toString())
                        .onSuccess {
                            Toast.makeText(requireContext(), "已移除 $name", Toast.LENGTH_SHORT).show()
                            loadMembers(projectId)
                        }
                        .onFailure { e ->
                            Toast.makeText(requireContext(), "删除失败：${e.message}", Toast.LENGTH_LONG).show()
                        }
                }
            }
            .show()
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

    /** 成员管理菜单（仅团长/管理员可见）：添加成员 / 添加管理员 */
    private fun showMembersMenu() {
        val popup = PopupMenu(requireContext(), binding.ivMembersMenu)
        popup.menuInflater.inflate(R.menu.menu_project_members, popup.menu)
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_add_member -> showAddMemberDialog()
                R.id.action_add_admin -> showAddAdminDialog()
                else -> false
            }
            true
        }
        popup.show()
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
        sheetBinding.etGroupName.hint = getString(R.string.search_member_hint)

        lateinit var pickAdapter: GroupMemberPickAdapter
        pickAdapter = GroupMemberPickAdapter(
            onItemClick = { pickAdapter.toggle(it) },
            onRoleClick = { pickAdapter.toggleRole(it) }
        )
        sheetBinding.rvGroupMembers.layoutManager = LinearLayoutManager(requireContext())
        sheetBinding.rvGroupMembers.adapter = pickAdapter
        // 输入框作搜索：按关键字过滤候选成员
        sheetBinding.etGroupName.doAfterTextChanged { pickAdapter.filter(it?.toString()) }

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

    /** 设置管理员：列出项目内已有普通成员，勾选后将其升级为管理员（PATCH 角色，不新增成员） */
    private fun showAddAdminDialog() {
        val projectId = mainViewModel.currentProjectId() ?: return
        val dialog = BottomSheetDialog(requireContext())
        val sheetBinding = BottomSheetCreateGroupBinding.inflate(layoutInflater)
        dialog.setContentView(sheetBinding.root)
        sheetBinding.tvSheetTitle.text = "设置管理员"
        sheetBinding.tvSheetSubtitle.text = "选择普通成员设为项目管理员"
        sheetBinding.etGroupDescription.visibility = View.GONE
        sheetBinding.btnSelectAll.visibility = View.GONE
        sheetBinding.etGroupName.hint = getString(R.string.search_member_hint)

        lateinit var pickAdapter: GroupMemberPickAdapter
        pickAdapter = GroupMemberPickAdapter(
            onItemClick = { pickAdapter.toggle(it) }
        )
        sheetBinding.rvGroupMembers.layoutManager = LinearLayoutManager(requireContext())
        sheetBinding.rvGroupMembers.adapter = pickAdapter
        // 输入框作搜索：按关键字过滤候选成员
        sheetBinding.etGroupName.doAfterTextChanged { pickAdapter.filter(it?.toString()) }

        viewLifecycleOwner.lifecycleScope.launch {
            // 候选 = 项目内已有普通成员（PROJECT_MEMBER），管理员/团长已具管理员身份，不列入
            val candidates = userRepository.getProjectMembers(projectId).getOrElse {
                Toast.makeText(requireContext(), R.string.add_member_failed, Toast.LENGTH_SHORT).show()
                dialog.dismiss()
                return@launch
            }.filter { it.role == "PROJECT_MEMBER" }
            if (candidates.isEmpty()) {
                Toast.makeText(requireContext(), R.string.add_member_empty, Toast.LENGTH_SHORT).show()
                dialog.dismiss()
                return@launch
            }
            // 显示名从团队成员表反查（ProjectMemberDto 无 displayName）
            pickAdapter.submitList(
                candidates.map { member ->
                    GroupMemberPick(
                        userId = member.userId,
                        name = memberNameById[member.userId] ?: getString(R.string.member_unknown),
                        role = "PROJECT_MEMBER"
                    )
                }
            )
        }

        sheetBinding.btnSend.setOnClickListener {
            val selected = pickAdapter.checkedIds()
            if (selected.isEmpty()) {
                Toast.makeText(requireContext(), R.string.add_member_empty, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            dialog.dismiss()
            promoteToAdmin(projectId, selected)
        }
        sheetBinding.btnCancel.setOnClickListener { dialog.dismiss() }
        dialog.show()
    }

    /** 逐个将选中的普通成员升级为管理员（PATCH /projects/{id}/members/{userId}） */
    private fun promoteToAdmin(projectId: String, userIds: List<String>) {
        if (userIds.isEmpty()) return
        viewLifecycleOwner.lifecycleScope.launch {
            var ok = 0
            userIds.forEach { userId ->
                userRepository.updateProjectMemberRole(projectId, userId, "PROJECT_ADMIN", UUID.randomUUID().toString())
                    .onSuccess { ok++ }
            }
            if (ok == 0) {
                Toast.makeText(requireContext(), R.string.add_member_failed, Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(requireContext(), "已将 $ok 名成员设为管理员", Toast.LENGTH_SHORT).show()
                loadMembers(projectId)
            }
        }
    }

    /** 退出项目：确认弹窗后调用移除项目成员接口（删除自己），成功后返回上一页 */
    private fun confirmExitProject() {
        val projectId = mainViewModel.currentProjectId() ?: return
        val myId = SessionStore.user()?.id
        if (myId.isNullOrEmpty()) return
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("退出项目")
            .setMessage("确定退出该项目？退出后将不再查看该项目的任务与合并请求。")
            .setNegativeButton("取消", null)
            .setPositiveButton("退出") { _, _ ->
                viewLifecycleOwner.lifecycleScope.launch {
                    userRepository.removeProjectMember(projectId, myId, UUID.randomUUID().toString())
                        .onSuccess {
                            Toast.makeText(requireContext(), "已退出项目", Toast.LENGTH_SHORT).show()
                            findNavController().navigateUp()
                        }
                        .onFailure { e ->
                            Toast.makeText(requireContext(), "退出失败：${e.message}", Toast.LENGTH_LONG).show()
                        }
                }
            }
            .show()
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
            val initialLoad = binding.rvRepositories.childCount == 0
            if (initialLoad) {
                binding.tvRepositoriesEmpty.isVisible = false
                setInlineSkeletonLoading(binding.rvRepositories, true)
            }
            try {
                val repos = githubRepository.getProjectRepositories(projectId).getOrNull().orEmpty()
                boundRepoCount = repos.size
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
            } finally {
                if (initialLoad) setInlineSkeletonLoading(binding.rvRepositories, false)
            }
        }
    }

    /** 解绑仓库（DELETE /projects/{id}/repositories/{id}） */
    private fun unbindRepo(projectId: String, projectRepositoryId: String, name: String) {
        // 仅剩一个绑定仓库时禁止解绑（项目至少保留一个仓库）
        if (boundRepoCount <= 1) {
            Toast.makeText(requireContext(), R.string.repo_last_one, Toast.LENGTH_SHORT).show()
            return
        }
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

    /** Existing project repository actions. Only Team Owners see the create-and-bind operation. */
    private fun showRepositoryActions() {
        val popup = PopupMenu(requireContext(), binding.tvAddRepository)
        popup.menu.add(0, REPOSITORY_ACTION_BIND, 0, R.string.project_repo_action_bind)
        if (isTeamOwner) {
            popup.menu.add(0, REPOSITORY_ACTION_CREATE, 1, R.string.project_repo_action_create)
        }
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                REPOSITORY_ACTION_BIND -> showBindRepoDialog()
                REPOSITORY_ACTION_CREATE -> showCreateAndBindRepoDialog()
            }
            true
        }
        popup.show()
    }

    private fun showCreateAndBindRepoDialog() {
        if (!isTeamOwner) return
        val teamId = mainViewModel.currentTeamId() ?: return
        viewLifecycleOwner.lifecycleScope.launch {
            val installations = githubRepository.getInstallations(teamId).getOrNull()
                ?.filter { it.status == "ACTIVE" }
                .orEmpty()
            when (installations.size) {
                0 -> Toast.makeText(requireContext(), "当前团队没有可用的 GitHub Installation", Toast.LENGTH_LONG).show()
                1 -> showCreateAndBindRepoForm(installations.single())
                else -> showInstallationPicker(installations)
            }
        }
    }

    private fun showInstallationPicker(installations: List<GitHubInstallationDto>) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("选择 GitHub Installation")
            .setItems(installations.map { it.accountLogin }.toTypedArray()) { _, which ->
                showCreateAndBindRepoForm(installations[which])
            }
            .show()
    }

    private fun showCreateAndBindRepoForm(installation: GitHubInstallationDto) {
        val projectId = mainViewModel.currentProjectId() ?: return
        val form = DialogCreateProjectRepositoryBinding.inflate(layoutInflater)
        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.project_repo_create_title)
            .setView(form.root)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.project_repo_create_confirm, null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val name = form.etRepositoryName.text?.toString()?.trim().orEmpty()
                val description = form.etRepositoryDescription.text?.toString()?.trim().orEmpty()
                val displayName = form.etDisplayName.text?.toString()?.trim().orEmpty()
                form.nameInputLayout.error = when {
                    name.isEmpty() -> getString(R.string.new_repo_name_required)
                    name.length > MAX_REPOSITORY_NAME_LENGTH -> getString(R.string.project_repo_name_too_long)
                    else -> null
                }
                form.descriptionInputLayout.error = if (description.length > MAX_REPOSITORY_DESCRIPTION_LENGTH) {
                    getString(R.string.project_repo_description_too_long)
                } else {
                    null
                }
                if (form.nameInputLayout.error != null || form.descriptionInputLayout.error != null) return@setOnClickListener

                dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).isEnabled = false
                viewLifecycleOwner.lifecycleScope.launch {
                    githubRepository.createAndBindProjectRepository(
                        projectId = projectId,
                        idempotencyKey = UUID.randomUUID().toString(),
                        body = CreateProjectRepositoryRequest(
                            name = name,
                            description = description.ifBlank { null },
                            isPrivate = form.swPrivate.isChecked,
                            installationId = installation.id,
                            displayName = displayName.ifBlank { null }
                        )
                    ).onSuccess { repository ->
                        dialog.dismiss()
                        Toast.makeText(requireContext(), "已创建并绑定 ${repository.fullName}", Toast.LENGTH_SHORT).show()
                        loadRepositories(projectId)
                    }.onFailure { error ->
                        dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).isEnabled = true
                        Toast.makeText(requireContext(), createRepositoryErrorMessage(error), Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
        dialog.show()
    }

    private fun createRepositoryErrorMessage(error: Throwable): String = when ((error as? ApiException)?.code) {
        "GITHUB_REPOSITORY_CREATE_CONFLICT" -> "仓库名称已存在或不符合 GitHub 规则"
        "GITHUB_INSTALLATION_NOT_ACTIVE" -> "GitHub Installation 已不可用，请重新选择"
        "GITHUB_INSTALLATION_REQUIRED" -> "请选择 GitHub Installation"
        "GITHUB_REPOSITORY_ACCESS_DENIED" -> "仅 Team Owner 可以新建仓库"
        "GITHUB_REPOSITORY_METADATA_INCOMPLETE" -> "仓库初始化未完成，请稍后重试"
        "GITHUB_API_UNAVAILABLE" -> "GitHub 暂时不可用，请稍后重试"
        else -> "创建仓库失败：${error.message}"
    }

    /** 绑定仓库：列出团队已授权且未绑定的仓库，勾选后逐个绑定 */
    private fun showBindRepoDialog() {
        val projectId = mainViewModel.currentProjectId() ?: return
        val teamId = mainViewModel.currentTeamId() ?: return
        viewLifecycleOwner.lifecycleScope.launch {
            val boundIds = githubRepository.getProjectRepositories(projectId).getOrNull().orEmpty()
                .map { it.repositoryId }.toSet()
            val installations = githubRepository.getInstallations(teamId).getOrElse {
                Toast.makeText(requireContext(), "加载 GitHub Installation 失败", Toast.LENGTH_SHORT).show()
                return@launch
            }
            val activeInstallationIds = installations
                .filter { it.status == "ACTIVE" }
                .map { it.id }
                .toSet()
            // 保留不可绑定仓库用于展示原因，但通过 isEnabled 禁止勾选，和 Web 端一致。
            val candidates = githubRepository.getGithubRepositories(teamId).getOrNull().orEmpty()
                .filter { it.id !in boundIds }
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
                val blockReason = repositoryBindBlockReason(repo, activeInstallationIds)
                val cb = android.widget.CheckBox(requireContext()).apply {
                    text = if (blockReason == null) repo.fullName else "${repo.fullName}（$blockReason）"
                    textSize = 14f
                    isChecked = false
                    isEnabled = blockReason == null
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
            val failed = mutableListOf<String>()
            selected.forEach { repo ->
                githubRepository.bindProjectRepository(
                    projectId,
                    UUID.randomUUID().toString(),
                    BindProjectRepositoryRequest(repo.installationId, repo.id, repo.fullName)
                ).onSuccess { ok++ }
                    .onFailure { error ->
                        failed += "${repo.fullName}：${repositoryBindErrorMessage(error)}"
                    }
            }
            val message = when {
                failed.isEmpty() -> "已绑定 $ok 个仓库"
                ok == 0 -> "没有仓库绑定成功：${failed.joinToString("；")}"
                else -> "已绑定 $ok 个仓库，失败：${failed.joinToString("；")}"
            }
            Toast.makeText(requireContext(), message, Toast.LENGTH_LONG).show()
            loadRepositories(projectId)
        }
    }

    private fun repositoryBindErrorMessage(error: Throwable): String = when ((error as? ApiException)?.code) {
        "GITHUB_REPOSITORY_METADATA_INCOMPLETE" -> "仓库尚未初始化"
        "REPOSITORY_NOT_AUTHORIZED_FOR_PROJECT" -> "仓库不在有效授权范围"
        "GITHUB_REPOSITORY_ACCESS_DENIED" -> "没有项目绑定权限"
        else -> error.message?.takeIf { it.isNotBlank() } ?: "绑定失败"
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        private const val AVATAR_MAX_BYTES = 5 * 1024 * 1024L
        private const val REPOSITORY_ACTION_BIND = 1
        private const val REPOSITORY_ACTION_CREATE = 2
        private const val MAX_REPOSITORY_NAME_LENGTH = 100
        private const val MAX_REPOSITORY_DESCRIPTION_LENGTH = 500
    }
}
