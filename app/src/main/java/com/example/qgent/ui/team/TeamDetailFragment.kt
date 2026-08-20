package com.example.qgent.ui.team

import android.app.AlertDialog
import android.app.Dialog
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.os.bundleOf
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.bumptech.glide.Glide
import com.example.qgent.QgentApp
import com.example.qgent.R
import com.example.qgent.data.api.RetrofitClient
import com.example.qgent.data.model.ApiException
import com.example.qgent.data.model.GitHubRepositoryDto
import com.example.qgent.ui.auth.TeamEntryActivity
import com.example.qgent.data.model.InviteTeamMemberRequest
import com.example.qgent.data.model.TeamInvitationDto
import com.example.qgent.data.model.TeamMemberDto
import com.example.qgent.data.repository.GitHubRepository
import com.example.qgent.data.repository.UserRepository
import com.example.qgent.data.sse.SseEventType
import com.example.qgent.databinding.DialogInviteHistoryBinding
import com.example.qgent.databinding.DialogInviteMemberBinding
import com.example.qgent.databinding.FragmentTeamDetailBinding
import com.example.qgent.databinding.ItemChatMemberBinding
import com.example.qgent.databinding.ItemInviteHistoryBinding
import com.example.qgent.databinding.ItemProjectBinding
import com.example.qgent.databinding.ItemRepositoryBinding
import com.example.qgent.ui.personal.bindCollapsibleSection
import com.example.qgent.ui.personal.fillLinearLayout
import com.example.qgent.viewmodel.MainViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * 团队详情页：管理成员 / 管理项目 / 管理仓库三个下拉分组（默认收起，展开时顶部有增加按钮），底部解散团队。
 * 项目列表观察 ViewModel 数据流；成员来自团队成员接口；仓库按权限分路：
 * 团长/项目管理员看团队授权仓库，普通成员看自己加入项目的绑定仓库。
 */
class TeamDetailFragment : Fragment() {

    private var _binding: FragmentTeamDetailBinding? = null
    private val binding get() = _binding!!
    private val mainViewModel: MainViewModel by activityViewModels {
        (requireActivity().application as QgentApp).container.mainViewModelFactory
    }

    private val userRepository: UserRepository
        get() = (requireActivity().application as QgentApp).container.userRepository
    private val githubRepository: GitHubRepository
        get() = (requireActivity().application as QgentApp).container.githubRepository

    /** 当前用户是否团队创建者（仅团长可见撤销仓库授权入口等管理操作） */
    private var isOwner = false

    /** 团队级 SSE：GitHub 安装/仓库状态事件 → 刷新授权仓库（撤销授权/归档/恢复实时可见） */
    private val eventStream: com.example.qgent.data.sse.ProjectEventStream
        get() = (requireActivity().application as QgentApp).container.projectEventStream
    private var eventStreamJob: Job? = null

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
        // 我创建的团队 → 解散团队；我加入的团队 → 退出团队（默认按创建的兜底）
        isOwner = arguments?.getBoolean(ARG_IS_OWNER, true) ?: true

        binding.ivBack.setOnClickListener { findNavController().navigateUp() }
        binding.tvTeamName.text = teamName

        // 仅我创建的团队（Team Owner）可使用邀请/仓库/新建项目管理；普通成员隐藏对应入口
        binding.btnInviteHistory.isVisible = isOwner
        binding.btnAddMember.isVisible = isOwner
        binding.btnAddProject.isVisible = isOwner
        binding.btnRepository.isVisible = isOwner

        // 右上角邀请记录：弹窗查看
        binding.btnInviteHistory.setOnClickListener {
            if (teamId.isNotEmpty()) showInviteHistoryDialog(teamId)
        }

        // 三个三角下拉分组：默认收起，点击头部展开 / 收起
        bindCollapsibleSection(binding.headerMembers, binding.ivArrowMembers, binding.sectionMembers)
        bindCollapsibleSection(binding.headerProjects, binding.ivArrowProjects, binding.sectionProjects)
        bindCollapsibleSection(binding.headerRepository, binding.ivArrowRepository, binding.sectionRepository)

        // 展开时分组顶部显示增加按钮
        binding.btnAddMember.setOnClickListener {
            if (teamId.isNotEmpty()) showInviteMemberDialog(teamId)
        }
        binding.btnAddProject.setOnClickListener { openNewProject(teamId) }
        binding.btnRepository.setOnClickListener { openGitHubAuthorize() }

        // 项目列表来自 MainViewModel（真实数据流）
        mainViewModel.projects.observe(viewLifecycleOwner) { projects ->
            fillLinearLayout(binding.rvProjects, projects, R.layout.item_project) { view, name ->
                ItemProjectBinding.bind(view).apply {
                    tvProjectName.text = name
                    // 项目头像（§31.1）：有则显示，无则默认文件夹图标
                    val avatarUrl = projectAvatarByName[name]
                    if (avatarUrl.isNullOrBlank()) {
                        ivProjectAvatar.setImageResource(R.drawable.ic_folder)
                    } else {
                        Glide.with(ivProjectAvatar)
                            .load(RetrofitClient.resolveMediaUrl(avatarUrl))
                            .centerCrop()
                            .placeholder(R.drawable.ic_folder)
                            .error(R.drawable.ic_folder)
                            .into(ivProjectAvatar)
                    }
                }
            }
        }

        if (teamId.isNotEmpty()) {
            // 仓库列表 = 团队授权仓库（与 GitHub 页计数口径一致）
            loadAuthorizedRepositories(teamId, isOwner)
            loadMembers(teamId, isOwner)
            startTeamEventStream(teamId, isOwner)
            loadTeamAvatar(teamId)
            loadProjectAvatars(teamId)
        }

        // 团队头像：点击更换（仅 Team Owner；头像上传失败提示但不阻断）
        if (isOwner) {
            binding.ivTeamAvatar.setOnClickListener {
                pickAvatar.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            }
        }

        binding.btnDissolveTeam.text =
            getString(if (isOwner) R.string.dissolve_team else R.string.exit_team)
        binding.btnDissolveTeam.setOnClickListener { confirmLeaveOrDissolve(isOwner) }
    }

    /** 加载并填充团队成员列表；isOwner 控制是否显示删除按钮。
     *  排序：创建者（TEAM_OWNER）置顶，其余保持后端返回顺序（产品约定，见 docs/product-notes.md）。 */
    private fun loadMembers(teamId: String, isOwner: Boolean) {
        viewLifecycleOwner.lifecycleScope.launch {
            userRepository.getTeamMembers(teamId).onSuccess { members ->
                val sorted = members.sortedByDescending { it.role == "TEAM_OWNER" }
                fillLinearLayout(binding.rvMembers, sorted, R.layout.item_chat_member) { view, member ->
                    val item = ItemChatMemberBinding.bind(view)
                    item.tvMemberName.text = member.displayName
                    // 成员头像（§28.2：members 返回 avatarUrl，可为空；无则默认占位）
                    if (member.avatarUrl.isNullOrBlank()) {
                        item.ivMemberAvatar.setImageResource(R.drawable.ic_avatar_default)
                    } else {
                        Glide.with(item.ivMemberAvatar)
                            .load(RetrofitClient.resolveMediaUrl(member.avatarUrl))
                            .centerCrop()
                            .placeholder(R.drawable.ic_avatar_default)
                            .error(R.drawable.ic_avatar_default)
                            .into(item.ivMemberAvatar)
                    }
                    item.tvAgentTag.isVisible = member.role == "TEAM_OWNER"
                    item.tvAgentTag.text = "创建者"
                    // 仅我创建的团队可移除成员；创建者行不显示删除按钮
                    item.ivDeleteMember.isVisible = isOwner && member.role != "TEAM_OWNER"
                    item.ivDeleteMember.setOnClickListener { confirmRemoveMember(teamId, member, isOwner) }
                }
            }
        }
    }

    /**
     * 加载团队仓库列表，按当前用户权限选择数据源：
     * - 团长：团队授权仓库接口可调（§6，GET /teams/{teamId}/integrations/github/repositories，
     *   权限 Team Owner 或 Project Admin），展示 AUTHORIZED 仓库 + REVOKED 死绑定，带撤销入口；
     * - 普通成员：无该接口权限（后端 403，且 mock 回退会返回静态数据误导展示），
     *   改为聚合「我加入的项目」的绑定仓库（GET /projects/{projectId}/repositories 项目成员可调；
     *   §26.2 项目列表仅返回我有权限的项目）。
     * 用 isOwner 明确分流而非尝试调用接口：Fallback 机制下普通成员调用会回退到 mock 静态仓库，
     * 无法仅凭返回值区分真实授权仓库与 mock 数据。
     */
    /** 团队头像显示（从团队列表缓存 teamDtos 取 avatarUrl；Glide 加载，无则默认图标） */
    private fun loadTeamAvatar(teamId: String) {
        val avatarUrl = mainViewModel.teamDtos.value?.firstOrNull { it.id == teamId }?.avatarUrl
        if (avatarUrl.isNullOrBlank()) {
            binding.ivTeamAvatar.setImageResource(R.drawable.ic_group)
            return
        }
        Glide.with(binding.ivTeamAvatar)
            .load(RetrofitClient.resolveMediaUrl(avatarUrl))
            .centerCrop()
            .placeholder(R.drawable.ic_group)
            .error(R.drawable.ic_group)
            .into(binding.ivTeamAvatar)
    }

    private val pickAvatar = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) uploadTeamAvatar(uri)
    }

    /** 团队头像上传（§28.1）：credential → OSS PUT → confirm → PATCH /teams/{id} 回写 → 刷新团队列表 */
    private fun uploadTeamAvatar(uri: Uri) {
        val teamId = arguments?.getString(ARG_TEAM_ID).orEmpty()
        if (teamId.isEmpty()) return
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
                { key, body -> RetrofitClient.service.createTeamAvatarCredential(teamId, key, body) },
                { key, body -> RetrofitClient.service.confirmTeamAvatar(teamId, key, body) }
            ).onSuccess { avatarUrl ->
                (requireActivity().application as QgentApp).container.userRepository
                    .updateTeam(teamId, avatarUrl, UUID.randomUUID().toString())
                mainViewModel.refreshTeams()
                loadTeamAvatar(teamId)
                Toast.makeText(requireContext(), "团队头像已更新", Toast.LENGTH_SHORT).show()
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

    /** 项目名 → 项目头像 URL 映射（§31.1，项目分组行显示用） */
    private val projectAvatarByName = mutableMapOf<String, String>()

    /** 拉取团队项目列表，建立 项目名 → avatarUrl 映射 */
    private fun loadProjectAvatars(teamId: String) {
        viewLifecycleOwner.lifecycleScope.launch {
            userRepository.getProjects(teamId).getOrNull().orEmpty().forEach { project ->
                if (!project.avatarUrl.isNullOrBlank()) projectAvatarByName[project.name] = project.avatarUrl!!
            }
        }
    }

    /** 团队级 SSE：GitHub App/仓库状态变化（暂停/删除/撤销授权/归档/恢复）→ 刷新授权仓库与绑定状态 */
    private fun startTeamEventStream(teamId: String, isOwner: Boolean) {        eventStream.startTeam(teamId)
        if (eventStreamJob == null) {
            eventStreamJob = viewLifecycleOwner.lifecycleScope.launch {
                eventStream.events.collect { event ->
                    when (event.type) {
                        SseEventType.GITHUB_INSTALLATION_UPDATED,
                        SseEventType.GITHUB_REPOSITORY_UPDATED -> {
                            loadAuthorizedRepositories(teamId, isOwner)
                        }
                        else -> Unit
                    }
                }
            }
        }
    }

    private fun loadAuthorizedRepositories(teamId: String, isOwner: Boolean) {
        viewLifecycleOwner.lifecycleScope.launch {
            if (isOwner) {
                val repos = githubRepository.getGithubRepositories(teamId).getOrNull().orEmpty()
                renderTeamAuthorizedRepositories(teamId, repos, isOwner)
            } else {
                renderMyProjectRepositories(teamId)
            }
        }
    }

    /**
     * 团长 / 项目管理员数据源：展示团队授权仓库。
     * - 展示 AUTHORIZED 仓库 + 「已被项目绑定但授权已撤销」的死绑定仓库（REVOKED），后者标红并点击提示；
     * - 每个仓库标注「已绑定项目 / 未绑定项目」，未绑定且仍授权的提供撤销授权删除入口。
     */
    private suspend fun renderTeamAuthorizedRepositories(
        teamId: String,
        repos: List<GitHubRepositoryDto>,
        isOwner: Boolean
    ) {
        // 授权仓库 → 项目绑定列表：文档 §6 对 ProjectRepository.repositoryId 的语义与示例冲突
        //（§6.612 示例为 github_repositories.id，§6.440 通用规则又要求下游 repositoryId 表示绑定 id），
        // 改用两边都含的 providerRepositoryId（GitHub 仓库全局唯一数字 ID）关联，规避语义歧义导致的绑定状态错乱
        val bindingsByRepository = userRepository.getProjects(teamId).getOrNull().orEmpty()
            .flatMap { project ->
                githubRepository.getProjectRepositories(project.id).getOrNull().orEmpty()
                    .map { it.providerRepositoryId to (project.id to it.id) }
            }
            .groupBy({ it.first }, { it.second })
        // 展示集：AUTHORIZED 全部展示；REVOKED 仅展示仍被项目绑定的死绑定（未绑定的已无意义，不展示）
        val displayRepos = repos.filter {
            it.authorizationStatus == "AUTHORIZED" ||
                (it.authorizationStatus == "REVOKED" && bindingsByRepository[it.providerRepositoryId].orEmpty().isNotEmpty())
        }
        fillLinearLayout(binding.rvRepository, displayRepos, R.layout.item_repository) { view, repo ->
            val item = ItemRepositoryBinding.bind(view)
            item.tvRepositoryName.text = repo.fullName
            val bindings = bindingsByRepository[repo.providerRepositoryId].orEmpty()
            val bound = bindings.isNotEmpty()
            val revoked = repo.authorizationStatus == "REVOKED"
            item.tvBoundStatus.text = getString(
                if (bound) R.string.github_repo_bound else R.string.github_repo_unbound
            )
            item.tvRepositoryStatus.isVisible = revoked
            if (revoked) {
                // 死绑定标红，点击提示原因
                item.tvRepositoryName.setTextColor(
                    androidx.core.content.ContextCompat.getColor(requireContext(), R.color.exit_red)
                )
                item.root.setOnClickListener {
                    Toast.makeText(requireContext(), R.string.repo_revoked_hint, Toast.LENGTH_LONG).show()
                }
            } else {
                // 仅团长可撤销授权；未绑定项目且仍授权 → 显示删除入口，已绑定保护项目引用，不显示
                item.ivDeleteRepository.isVisible = isOwner && !bound
                item.ivDeleteRepository.setOnClickListener {
                    confirmDeleteRepository(teamId, repo)
                }
            }
        }
    }

    /**
     * 普通成员数据源：聚合「我加入的项目」的绑定仓库。
     * 团队授权仓库接口仅 Team Owner / Project Admin 可调，普通成员改走项目仓库接口；
     * 项目列表（§26.2）只返回当前用户有权限的项目，天然限定为「自己加入的项目」。
     * 同一仓库被多个项目绑定按 fullName 去重展示；REVOKED 死绑定标红；无撤销入口。
     */
    private suspend fun renderMyProjectRepositories(teamId: String) {
        val repos = userRepository.getProjects(teamId).getOrNull().orEmpty()
            .flatMap { project ->
                githubRepository.getProjectRepositories(project.id).getOrNull().orEmpty()
            }
            .distinctBy { it.fullName }
        fillLinearLayout(binding.rvRepository, repos, R.layout.item_repository) { view, repo ->
            val item = ItemRepositoryBinding.bind(view)
            item.tvRepositoryName.text = repo.fullName
            item.tvBoundStatus.text = getString(R.string.github_repo_bound)
            val revoked = repo.authorizationStatus == "REVOKED"
            item.tvRepositoryStatus.isVisible = revoked
            if (revoked) {
                item.tvRepositoryName.setTextColor(
                    androidx.core.content.ContextCompat.getColor(requireContext(), R.color.exit_red)
                )
                item.root.setOnClickListener {
                    Toast.makeText(requireContext(), R.string.repo_revoked_hint, Toast.LENGTH_LONG).show()
                }
            }
            // 普通成员无撤销授权入口
            item.ivDeleteRepository.isVisible = false
        }
    }

    /** 确认撤销仓库授权：从团队授权列表移除该仓库 */
    private fun confirmDeleteRepository(teamId: String, repo: GitHubRepositoryDto) {
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.github_repo_delete)
            .setMessage(getString(R.string.github_repo_delete_confirm, repo.fullName))
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.github_repo_delete) { _, _ ->
                deleteRepository(teamId, repo)
            }
            .show()
    }

    /** 撤销仓库授权（DELETE /teams/{teamId}/integrations/github/repositories/{repositoryId}）并刷新 */
    private fun deleteRepository(teamId: String, repo: GitHubRepositoryDto) {
        viewLifecycleOwner.lifecycleScope.launch {
            githubRepository.revokeGithubRepository(teamId, repo.id, UUID.randomUUID().toString())
                .onSuccess {
                    Toast.makeText(requireContext(), R.string.github_repo_delete_success, Toast.LENGTH_SHORT).show()
                    loadAuthorizedRepositories(teamId, isOwner)
                }
                .onFailure { e ->
                    // 按错误码区分提示：权限不足 / 服务暂不可用，其余透传后端 message
                    Toast.makeText(
                        requireContext(),
                        deleteRepoErrorMessage(e),
                        Toast.LENGTH_SHORT
                    ).show()
                }
        }
    }

    /**
     * 撤销仓库授权失败文案：按 ApiException 错误码分类。
     * 权限不足：401 / 403 及 FORBIDDEN / UNAUTHORIZED / PERMISSION_* 业务码；
     * 服务暂不可用：HTTP_5xx（网关/服务端异常）；其余（业务码、4xx 冲突等）回退透传后端 message。
     */
    private fun deleteRepoErrorMessage(e: Throwable): String {
        if (e is ApiException) {
            val code = e.code
            val forbidden = code == "HTTP_401" || code == "HTTP_403" ||
                code.startsWith("FORBIDDEN") || code == "UNAUTHORIZED" || code.startsWith("PERMISSION")
            if (forbidden) return getString(R.string.github_repo_delete_forbidden)
            if (code.startsWith("HTTP_5")) return getString(R.string.github_repo_delete_service_unavailable)
        }
        return e.message ?: getString(R.string.github_repo_delete_failed)
    }

    /** 底部操作：我创建的团队 → 解散（契约 §5.1）；我加入的团队 → 退出（待接入） */
    private fun confirmLeaveOrDissolve(isOwner: Boolean) {
        val titleRes = if (isOwner) R.string.dissolve_team else R.string.exit_team
        val confirmRes = if (isOwner) R.string.dissolve_team_confirm else R.string.exit_team_confirm
        if (!isOwner) {
            Toast.makeText(requireContext(), R.string.exit_team_placeholder, Toast.LENGTH_SHORT).show()
            return
        }
        AlertDialog.Builder(requireContext())
            .setTitle(titleRes)
            .setMessage(confirmRes)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(titleRes) { _, _ ->
                dissolveTeam(arguments?.getString(ARG_TEAM_ID).orEmpty())
            }
            .show()
    }

    /** 解散团队：调用契约 §5.1 DELETE /teams/{teamId}，成功后刷新团队列表并返回上一页 */
    private fun dissolveTeam(teamId: String) {
        if (teamId.isEmpty()) return
        viewLifecycleOwner.lifecycleScope.launch {
            userRepository.deleteTeam(teamId, UUID.randomUUID().toString())
                .onSuccess {
                    Toast.makeText(requireContext(), R.string.dissolve_team_success, Toast.LENGTH_SHORT).show()
                    routeAfterDissolve()
                }
                .onFailure { e ->
                    Toast.makeText(
                        requireContext(),
                        e.message ?: getString(R.string.dissolve_team_failed),
                        Toast.LENGTH_SHORT
                    ).show()
                }
        }
    }

    /** 解散后路由：重新拉取团队列表，无其他团队 → 团队引导页（创建/加入）；有 → 返回团队管理页 */
    private fun routeAfterDissolve() {
        viewLifecycleOwner.lifecycleScope.launch {
            userRepository.getTeams()
                .onSuccess { teams ->
                    if (teams.isEmpty()) {
                        startActivity(Intent(requireContext(), TeamEntryActivity::class.java))
                        requireActivity().finish()
                    } else {
                        mainViewModel.refreshTeams()
                        findNavController().popBackStack()
                    }
                }
                .onFailure {
                    mainViewModel.refreshTeams()
                    findNavController().popBackStack()
                }
        }
    }

    /** 邀请记录弹窗：加载该团队邀请并支持撤销待接受邀请 */
    private fun showInviteHistoryDialog(teamId: String) {
        val dialogBinding = DialogInviteHistoryBinding.inflate(layoutInflater)
        val dialog = Dialog(requireContext()).apply {
            setContentView(dialogBinding.root)
            window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            window?.setLayout(
                (resources.displayMetrics.widthPixels * 0.9f).toInt(),
                (resources.displayMetrics.heightPixels * 0.8f).toInt()
            )
        }
        dialogBinding.btnClose.setOnClickListener { dialog.dismiss() }

        // 两个三角下拉分组：待接收 / 已处理，点击头部收起 / 展开
        bindCollapsibleSection(dialogBinding.headerPending, dialogBinding.ivArrowPending, dialogBinding.rvPending)
        bindCollapsibleSection(dialogBinding.headerProcessed, dialogBinding.ivArrowProcessed, dialogBinding.rvProcessed)

        loadInvitationsInto(dialogBinding, teamId)
        dialog.show()
    }

    /** 加载邀请记录并按状态分组填充：PENDING → 待接收组，其余（已接受/已撤销/已过期）→ 已处理组 */
    private fun loadInvitationsInto(
        dialogBinding: DialogInviteHistoryBinding,
        teamId: String
    ) {
        viewLifecycleOwner.lifecycleScope.launch {
            userRepository.getTeamInvitations(teamId)
                .onSuccess { list ->
                    val pending = list.filter { it.status == "PENDING" }
                    val processed = list.filter { it.status != "PENDING" }
                    fillLinearLayout(dialogBinding.rvPending, pending, R.layout.item_invite_history) { view, invitation ->
                        bindInviteItem(view, teamId, invitation) {
                            loadInvitationsInto(dialogBinding, teamId)
                        }
                    }
                    fillLinearLayout(dialogBinding.rvProcessed, processed, R.layout.item_invite_history) { view, invitation ->
                        bindInviteItem(view, teamId, invitation) {
                            loadInvitationsInto(dialogBinding, teamId)
                        }
                    }
                    dialogBinding.tvEmpty.isVisible = list.isEmpty()
                }
                .onFailure {
                    dialogBinding.rvPending.removeAllViews()
                    dialogBinding.rvProcessed.removeAllViews()
                    dialogBinding.tvEmpty.isVisible = true
                }
        }
    }

    /** 填充单个邀请记录项：邮箱 + 状态；仅待接收显示撤销按钮，撤销成功回调 onRevoked 刷新弹窗 */
    private fun bindInviteItem(
        view: View,
        teamId: String,
        invitation: TeamInvitationDto,
        onRevoked: () -> Unit
    ) {
        val item = ItemInviteHistoryBinding.bind(view)
        item.tvEmail.text = invitation.email
        item.tvStatus.text = when (invitation.status) {
            "PENDING" -> getString(R.string.invite_status_pending)
            "ACCEPTED" -> getString(R.string.invite_status_accepted)
            "REVOKED" -> getString(R.string.invite_status_revoked)
            "EXPIRED" -> getString(R.string.invite_status_expired)
            else -> invitation.status
        }
        // 仅 PENDING（待接收）可撤销
        item.btnRevoke.isVisible = invitation.status == "PENDING"
        item.btnRevoke.setOnClickListener {
            confirmRevokeInvitation(teamId, invitation, onRevoked)
        }
    }

    /** 确认撤销邀请，成功后回调以刷新弹窗列表 */
    private fun confirmRevokeInvitation(
        teamId: String,
        invitation: TeamInvitationDto,
        onRevoked: () -> Unit
    ) {
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.revoke_invitation)
            .setMessage(R.string.revoke_invitation_confirm)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.revoke_invitation) { _, _ ->
                viewLifecycleOwner.lifecycleScope.launch {
                    userRepository.revokeInvitation(teamId, invitation.id, UUID.randomUUID().toString())
                        .onSuccess {
                            Toast.makeText(requireContext(), R.string.revoke_invitation_success, Toast.LENGTH_SHORT).show()
                            onRevoked()
                        }
                        .onFailure {
                            Toast.makeText(requireContext(), R.string.revoke_invitation_failed, Toast.LENGTH_SHORT).show()
                        }
                }
            }
            .show()
    }

    /** 邀请成员弹窗：输入邮箱后发送邀请请求 */
    private fun showInviteMemberDialog(teamId: String) {
        val dialogBinding = DialogInviteMemberBinding.inflate(layoutInflater)
        val dialog = Dialog(requireContext()).apply {
            setContentView(dialogBinding.root)
            window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            window?.setLayout(
                (resources.displayMetrics.widthPixels * 0.85f).toInt(),
                WindowManager.LayoutParams.WRAP_CONTENT
            )
        }

        dialogBinding.etEmail.doAfterTextChanged {
            if (dialogBinding.emailLayout.error != null) dialogBinding.emailLayout.error = null
        }

        dialogBinding.btnSend.setOnClickListener {
            val email = dialogBinding.etEmail.text?.toString()?.trim().orEmpty()
            when {
                email.isEmpty() -> dialogBinding.emailLayout.error = getString(R.string.error_invite_email_required)
                !android.util.Patterns.EMAIL_ADDRESS.matcher(email).matches() ->
                    dialogBinding.emailLayout.error = getString(R.string.error_invite_email_invalid)
                else -> {
                    dialog.dismiss()
                    sendInvitation(teamId, email)
                }
            }
        }

        dialog.show()
    }

    /** 发送邀请请求：调 POST 接口；失败时区分网络错误与后端业务错误（如邮箱已加入团队） */
    private fun sendInvitation(teamId: String, email: String) {
        viewLifecycleOwner.lifecycleScope.launch {
            userRepository.createInvitation(
                teamId,
                InviteTeamMemberRequest(email = email, role = "TEAM_MEMBER", expiresInDays = 7),
                UUID.randomUUID().toString()
            ).onSuccess {
                Toast.makeText(requireContext(), R.string.invite_sent_success, Toast.LENGTH_SHORT).show()
            }.onFailure { e ->
                val message = when {
                    // 网络层异常：无法连接/超时等，与后端业务错误区分
                    e is java.io.IOException -> getString(R.string.invite_send_network_error)
                    // 后端业务错误：直接透出后端中文提示（如「该邮箱已加入团队」），后端未带提示时用通用文案
                    e is ApiException -> e.message?.takeIf { it.isNotBlank() } ?: getString(R.string.invite_send_failed)
                    else -> getString(R.string.invite_send_failed)
                }
                Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
            }
        }
    }

    /** 确认移除团队成员：调 DELETE 接口后刷新成员列表 */
    private fun confirmRemoveMember(teamId: String, member: TeamMemberDto, isOwner: Boolean) {
        if (teamId.isEmpty()) return
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.remove_member_title)
            .setMessage(getString(R.string.remove_member_confirm, member.displayName))
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.remove_member) { _, _ ->
                viewLifecycleOwner.lifecycleScope.launch {
                    userRepository.removeTeamMember(teamId, member.userId, UUID.randomUUID().toString())
                        .onSuccess {
                            Toast.makeText(requireContext(), R.string.remove_member_success, Toast.LENGTH_SHORT).show()
                            loadMembers(teamId, isOwner)
                        }
                        .onFailure {
                            Toast.makeText(requireContext(), R.string.remove_member_failed, Toast.LENGTH_SHORT).show()
                        }
                }
            }
            .show()
    }

    /** 新增仓库：跳转 GitHub 授权页，传入当前团队 id / 名称 */
    private fun openGitHubAuthorize() {
        val teamId = arguments?.getString(ARG_TEAM_ID).orEmpty()
        val teamName = arguments?.getString(ARG_TEAM_NAME).orEmpty()
        findNavController().navigate(
            R.id.githubAuthorizeFragment,
            bundleOf("teamId" to teamId, "teamName" to teamName)
        )
    }

    /** 新建项目：与抽屉一致，跳转新建项目页并传入当前团队 id（无创建权限时拦截提示） */
    private fun openNewProject(teamId: String) {
        if (teamId.isEmpty()) {
            Toast.makeText(requireContext(), R.string.new_project_missing_team, Toast.LENGTH_SHORT).show()
            return
        }
        if (!mainViewModel.canCreateProject(arguments?.getString(ARG_TEAM_NAME).orEmpty())) {
            Toast.makeText(requireContext(), R.string.new_project_no_permission, Toast.LENGTH_SHORT).show()
            return
        }
        findNavController().navigate(R.id.newProjectFragment, bundleOf("teamId" to teamId))
    }

    override fun onDestroyView() {
        super.onDestroyView()
        eventStreamJob?.cancel()
        eventStreamJob = null
        _binding = null
    }

    companion object {
        const val ARG_TEAM_NAME = "teamName"
        const val ARG_TEAM_ID = "teamId"
        const val ARG_IS_OWNER = "isOwner"
        private const val AVATAR_MAX_BYTES = 5 * 1024 * 1024L
    }
}
