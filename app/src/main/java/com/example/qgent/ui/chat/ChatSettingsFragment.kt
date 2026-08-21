package com.example.qgent.ui.chat

import android.app.Dialog
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.TextView
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
import androidx.recyclerview.widget.LinearLayoutManager
import com.bumptech.glide.Glide
import com.bumptech.glide.load.model.GlideUrl
import com.bumptech.glide.load.model.LazyHeaders
import com.example.qgent.QgentApp
import com.example.qgent.R
import com.example.qgent.data.DndStore
import com.example.qgent.data.SessionStore
import com.example.qgent.data.api.RetrofitClient
import com.example.qgent.data.model.ApiException
import com.example.qgent.data.model.GroupMemberDto
import com.example.qgent.data.model.GroupMessageDto
import com.example.qgent.data.repository.ChatRepository
import com.example.qgent.data.repository.UserRepository
import com.example.qgent.model.GroupType
import com.example.qgent.databinding.BottomSheetCreateGroupBinding
import com.example.qgent.databinding.DialogSearchMessagesBinding
import com.example.qgent.databinding.FragmentChatSettingsBinding
import com.example.qgent.viewmodel.MainViewModel
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

class ChatSettingsFragment : Fragment() {

    private var _binding: FragmentChatSettingsBinding? = null
    private val binding get() = _binding!!
    private val mainViewModel: MainViewModel by activityViewModels {
        (requireActivity().application as QgentApp).container.mainViewModelFactory
    }
    private val chatRepo: ChatRepository by lazy {
        (requireActivity().application as QgentApp).container.chatRepository
    }
    private val userRepository: UserRepository
        get() = (requireActivity().application as QgentApp).container.userRepository

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentChatSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.btnBack.setOnClickListener { findNavController().popBackStack() }

        // 「更多 ▶」：跳转成员列表页（头像/名称/身份，管理员与团长可见删除键）
        binding.btnMore.setOnClickListener {
            findNavController().navigate(
                R.id.action_chatSettings_to_memberList,
                bundleOf("groupId" to (arguments?.getString("groupId") ?: ""))
            )
        }

        loadGroupData()
        loadAdminPermission()

        // 查看聊天记录：弹出搜索弹窗，按关键词过滤消息
        binding.btnViewHistory.setOnClickListener { showSearchDialog() }

        // 消息免打扰（本地存储，仅本机生效）：该群后台不弹系统通知，未读红点照常累计
        val dndGroupId = arguments?.getString("groupId").orEmpty()
        binding.swDnd.isChecked = DndStore.isMuted(dndGroupId)
        binding.swDnd.setOnCheckedChangeListener { _, checked ->
            DndStore.setMuted(dndGroupId, checked)
            Toast.makeText(
                requireContext(),
                if (checked) R.string.dnd_muted_on else R.string.dnd_muted_off,
                Toast.LENGTH_SHORT
            ).show()
        }

        // 退出群聊：先弹确认，确认后调接口
        binding.btnExitGroup.setOnClickListener { confirmExitGroup() }

        // Agent 名单异步加载完成后重新合并渲染成员（Agent 加载慢于群成员时避免漏显示 Agent）
        mainViewModel.agents.observe(viewLifecycleOwner) {
            remergeMembers()
        }
    }

    /** 用缓存的原始群成员重新合并 Agent 并渲染（不重新拉接口） */
    private fun remergeMembers() {
        val projectId = mainViewModel.currentProjectId() ?: return
        val groupId = arguments?.getString("groupId").orEmpty()
        Log.d("ChatSettings", "remergeMembers: agents=${mainViewModel.agents.value.orEmpty().size} lastRaw=${lastRawMembers.size}")
        if (groupId.isEmpty() || lastRawMembers.isEmpty()) return
        renderMembers(mergeAgents(projectId, groupId, lastRawMembers))
    }

    /** 当前用户是否项目管理员（团长由后端兜底 PROJECT_ADMIN） */
    private var isAdmin = false

    /** 当前需求群创建者 userId（PROJECT_MAIN 总群为 null）：群创建者也可管理成员 */
    private var groupCreatorId: String? = null

    /** 当前用户是否可管理成员（团长/管理员/分群创建者）：控制成员网格的 添加/删除 控件显隐 */
    private fun canManageMembers(): Boolean =
        isAdmin || (groupCreatorId?.let { it == SessionStore.user()?.id } == true)

    /** 判定当前用户是否为项目管理员：仅团长/管理员/分群创建者显示成员网格的 添加/删除 控件 */
    private fun loadAdminPermission() {
        val projectId = mainViewModel.currentProjectId() ?: return
        viewLifecycleOwner.lifecycleScope.launch {
            isAdmin = userRepository.getProject(projectId).getOrNull()?.role == "PROJECT_ADMIN"
            // 成员网格已渲染时重新填充，刷新控件格
            loadGroupData()
        }
    }

    /** 添加成员入口：总群=拉团队进项目（可设身份）；分群=把项目成员拉入该群（无身份） */
    private fun showAddMemberDialog() {
        val groupId = arguments?.getString("groupId").orEmpty()
        val isMainGroup = mainViewModel.groups.value.orEmpty()
            .firstOrNull { it.id == groupId }
            ?.type == GroupType.PROJECT_MAIN
        if (isMainGroup) showAddProjectMemberDialog() else showAddGroupMemberDialog()
    }

    /**
     * 总群添加成员：列出团队中尚未加入当前项目的成员，勾选后可设置身份（项目成员/项目管理员），
     * POST 加入后选管理员的再 PATCH 升级（§5.2）。与项目详情页「添加成员」共用交互。
     */
    private fun showAddProjectMemberDialog() {
        val projectId = mainViewModel.currentProjectId() ?: run {
            Toast.makeText(requireContext(), R.string.add_member_missing_project, Toast.LENGTH_SHORT).show()
            return
        }
        val teamId = mainViewModel.currentTeamId() ?: run {
            Toast.makeText(requireContext(), R.string.new_project_missing_team, Toast.LENGTH_SHORT).show()
            return
        }
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
            // 当前用户始终视为已在项目内（后端 GET /projects/{id}/members 可能不返回自己/Team Owner 兜底无成员行），
            // 否则候选 = 团队成员 − 项目成员 会把「自己」误当成唯一可添加的人
            val existingIds = userRepository.getProjectMembers(projectId)
                .getOrNull()?.map { it.userId }?.toSet().orEmpty() + listOfNotNull(SessionStore.user()?.id)
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

    /**
     * 分群添加成员（v2.0.6 §9）：把项目成员拉入该需求群。
     * 候选 = 项目成员 − 当前群成员（不涉及团队，也不可设身份）。
     */
    private fun showAddGroupMemberDialog() {
        val projectId = mainViewModel.currentProjectId() ?: run {
            Toast.makeText(requireContext(), R.string.add_member_missing_project, Toast.LENGTH_SHORT).show()
            return
        }
        val groupId = arguments?.getString("groupId").orEmpty()
        if (groupId.isEmpty()) return
        val dialog = BottomSheetDialog(requireContext())
        val sheetBinding = BottomSheetCreateGroupBinding.inflate(layoutInflater)
        dialog.setContentView(sheetBinding.root)
        sheetBinding.tvSheetTitle.text = getString(R.string.action_add_member)
        sheetBinding.tvSheetSubtitle.text = getString(R.string.add_group_member_subtitle)
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
            val projectMembers = userRepository.getProjectMembers(projectId).getOrElse {
                Toast.makeText(requireContext(), R.string.add_member_failed, Toast.LENGTH_SHORT).show()
                dialog.dismiss()
                return@launch
            }
            val existingIds = chatRepo.getMembers(projectId, groupId).getOrNull().orEmpty()
                .map { it.id }.toSet()
            val candidates = projectMembers.filter { it.userId !in existingIds }
            if (candidates.isEmpty()) {
                Toast.makeText(requireContext(), R.string.add_group_member_empty, Toast.LENGTH_SHORT).show()
                dialog.dismiss()
                return@launch
            }
            // 显示名从团队成员表反查（ProjectMemberDto 无 displayName），查不到用「成员」占位
            val nameById = userRepository.getTeamMembers(mainViewModel.currentTeamId().orEmpty())
                .getOrNull().orEmpty().associate { it.userId to it.displayName }
            pickAdapter.submitList(
                candidates.map { GroupMemberPick(it.userId, nameById[it.userId] ?: getString(R.string.member_unknown), "PROJECT_MEMBER") }
            )
        }

        sheetBinding.btnSend.setOnClickListener {
            val selected = pickAdapter.checkedIds()
            if (selected.isEmpty()) {
                Toast.makeText(requireContext(), R.string.add_group_member_empty, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            dialog.dismiss()
            addGroupMembers(projectId, groupId, selected)
        }
        sheetBinding.btnCancel.setOnClickListener { dialog.dismiss() }
        dialog.show()
    }

    /** 逐个将选中的项目成员拉入当前需求群（v2.0.6 §9：POST .../groups/{groupId}/members），汇总成功数量提示 */
    private fun addGroupMembers(projectId: String, groupId: String, userIds: List<String>) {
        if (userIds.isEmpty()) return
        viewLifecycleOwner.lifecycleScope.launch {
            var added = 0
            userIds.forEach { userId ->
                chatRepo.addGroupMember(projectId, groupId, userId, UUID.randomUUID().toString())
                    .onSuccess { added++ }
            }
            if (added == 0) {
                Toast.makeText(requireContext(), R.string.add_member_failed, Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(requireContext(), getString(R.string.add_group_member_success, added), Toast.LENGTH_SHORT).show()
                loadGroupData()
            }
        }
    }

    /** 逐个将选中成员加入项目：POST 加入后按所选身份决定是否 PATCH 升级，汇总成功数量提示 */
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
            }
        }
    }

    private fun loadGroupData() {
        val projectId = mainViewModel.currentProjectId()
        val groupId = arguments?.getString("groupId").orEmpty()

        if (projectId == null || groupId.isEmpty()) {
            renderMembers(emptyList())
            return
        }

        viewLifecycleOwner.lifecycleScope.launch {
            chatRepo.getGroup(projectId, groupId).onSuccess { dto ->
                binding.tvGroupName.text = dto.title
                groupCreatorId = dto.createdBy
                loadBoundRepositories(projectId, dto.repositoryIds.orEmpty())
            }
            chatRepo.getMembers(projectId, groupId).onSuccess { dtos ->
                Log.d("ChatSettings", "getMembers raw: $dtos")
                lastRawMembers = dtos
                renderMembers(mergeAgents(projectId, groupId, dtos))
            }
        }
    }

    /** dp 转 px */
    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    /** 绑定仓库：群 repositoryIds → 项目仓库映射 → 填充列表（复用 getProjectRepositories） */
    private fun loadBoundRepositories(projectId: String, repositoryIds: List<String>) {
        binding.containerRepositories.removeAllViews()
        if (repositoryIds.isEmpty()) {
            binding.tvRepositoriesEmpty.isVisible = true
            return
        }
        viewLifecycleOwner.lifecycleScope.launch {
            val nameById = (requireActivity().application as QgentApp).container.githubRepository
                .getProjectRepositories(projectId).getOrNull().orEmpty()
                .associate { it.id to (it.displayName.ifBlank { it.fullName }) }
            val names = repositoryIds.mapNotNull { nameById[it] }
            binding.tvRepositoriesEmpty.isVisible = names.isEmpty()
            names.forEach { name ->
                val row = layoutInflater.inflate(R.layout.item_bound_repo_row, binding.containerRepositories, false) as TextView
                row.text = "• $name"
                binding.containerRepositories.addView(row)
            }
        }
    }

    /**
     * 合并团队 Agent 到群成员（与群聊详情页一致）：
     * 仅需求群合并；Agent 以团队 Agent 名单为准，按 id 去重（群成员里同 id 的 Agent 条目丢弃）。
     */
    private fun mergeAgents(projectId: String, groupId: String, dtos: List<GroupMemberDto>): List<GroupMemberDto> {
        val isMainGroup = mainViewModel.groups.value.orEmpty()
            .firstOrNull { it.id == groupId }
            ?.type == GroupType.PROJECT_MAIN
        if (isMainGroup) return dtos
        val allAgents = mainViewModel.agents.value.orEmpty()
        Log.d("ChatSettings", "mergeAgents: raw=${dtos.size} agents=${allAgents.size} groups=${mainViewModel.groups.value.orEmpty().size}")
        // 群里只合并一个 Agent（取团队第一个 ACTIVE；角色已收敛为 4 种执行角色）
        // 显示名统一为「编排助手」（与后端编排回复方 senderName 对齐），id/头像仍指向该 Agent
        val teamAgents = allAgents
            .firstOrNull { it.status.name != "ARCHIVED" }
            ?.let {
                listOf(GroupMemberDto(
                    id = it.id,
                    nickname = getString(R.string.chat_group_agent_name),
                    displayName = getString(R.string.chat_group_agent_name),
                    avatar = it.avatar,
                    memberType = "AGENT"
                ))
            }
            .orEmpty()
        val agentIds = teamAgents.map { it.id }.toSet()
        // 后端群成员中可能含 Agent：全部过滤，只保留合并的单一 Agent（避免叠加成多个）
        return dtos.filter { it.id !in agentIds && !it.isAgent } + teamAgents
    }

    private fun renderMembers(members: List<GroupMemberDto>) {
        binding.containerMembers.removeAllViews()
        binding.tvMemberCount.text = getString(R.string.group_member_count, members.size)
        // 网格 4 列 × 3 行：
        // - 普通成员：第 1-3 行各 4 个，共 12 个成员（最多看到 4*3 个成员）
        // - 团长/管理员/分群创建者：第 1-2 行 8 个成员 + 第 3 行 3 个成员 + 添加按钮（最后一行 = 3 成员 + 添加）
        val canManage = canManageMembers()
        val cellCount = if (canManage) 11 else 12
        val cells = mutableListOf<View>()
        for (member in members.take(cellCount)) {
            val cell = layoutInflater.inflate(R.layout.item_chat_member_grid, binding.containerMembers, false)
            bindMemberCell(cell, member)
            cells.add(cell)
        }
        // 团长/管理员/分群创建者：第 3 行末尾格子放 添加 按钮
        if (canManage) {
            cells.add(buildControlCell(R.drawable.ic_add, "添加") { showAddMemberDialog() })
        }
        renderMemberGrid(cells)
    }

    /** 成员网格：GridLayout 固定 4 列，按实际 cells 自动分行（最多 3 行），每格 columnWeight 均分等宽 */
    private fun renderMemberGrid(cells: List<View>) {
        binding.containerMembers.removeAllViews()
        cells.forEach { cell ->
            val lp = GridLayout.LayoutParams()
            lp.width = 0
            lp.height = GridLayout.LayoutParams.WRAP_CONTENT
            lp.columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
            binding.containerMembers.addView(cell, lp)
        }
    }

    /** 成员网格子项：头像 / 名称 / 身份（Agent 显示标签） */
    private fun bindMemberCell(cell: View, member: GroupMemberDto) {
        val name = member.resolvedName
        cell.findViewById<TextView>(R.id.tvMemberName)?.text = name
        // 身份：Agent 显示「Agent」，真人隐藏
        cell.findViewById<TextView>(R.id.tvMemberRole)?.isVisible = member.isAgent
        // 头像：avatar 为空显示默认占位，否则 Glide 带鉴权头加载
        val ivAvatar = cell.findViewById<ImageView>(R.id.ivMemberAvatar)
        if (member.avatar.isNullOrBlank()) {
            ivAvatar?.setImageResource(R.drawable.ic_avatar_default)
        } else {
            ivAvatar?.let { Glide.with(it).load(authedGlideUrl(member.avatar)).into(it) }
        }
    }

    /** 最近一次拉取的原始群成员（未合并 Agent），供 Agent 名单加载完成后重新合并渲染 */
    private var lastRawMembers = emptyList<GroupMemberDto>()

    /** 构建一个控件格：圆形图标 + 底部文字，点击回调（layoutParams 由 renderMemberGrid 统一设置） */
    private fun buildControlCell(iconRes: Int, label: String, onClick: () -> Unit): View {
        val cell = layoutInflater.inflate(R.layout.item_chat_member_grid, binding.containerMembers, false)
        cell.findViewById<ImageView>(R.id.ivMemberAvatar)?.apply {
            setImageResource(iconRes)
            setBackgroundResource(R.drawable.bg_oval)
            backgroundTintList = android.content.res.ColorStateList.valueOf(
                androidx.core.content.ContextCompat.getColor(requireContext(), R.color.bg_chat_pinned)
            )
        }
        cell.findViewById<TextView>(R.id.tvMemberName)?.text = label
        cell.findViewById<TextView>(R.id.tvMemberRole)?.isVisible = false
        cell.setOnClickListener { onClick() }
        return cell
    }

    /** 聊天记录搜索弹窗：加载当前群消息后按关键词本地过滤（后端暂无消息搜索接口） */
    private fun showSearchDialog() {
        val dialog = Dialog(requireContext())
        val searchBinding = DialogSearchMessagesBinding.inflate(layoutInflater)
        dialog.setContentView(searchBinding.root)
        dialog.window?.setLayout(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT
        )

        val adapter = SearchMessageAdapter()
        searchBinding.rvSearchResults.layoutManager = LinearLayoutManager(requireContext())
        searchBinding.rvSearchResults.adapter = adapter

        val projectId = mainViewModel.currentProjectId()
        val groupId = arguments?.getString("groupId").orEmpty()
        var allMessages = emptyList<GroupMessageDto>()
        if (projectId != null && groupId.isNotEmpty()) {
            viewLifecycleOwner.lifecycleScope.launch {
                chatRepo.getMessages(projectId, groupId).onSuccess { dtos -> allMessages = dtos }
            }
        }

        searchBinding.etSearchQuery.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val q = s?.toString()?.trim().orEmpty()
                val results = if (q.isEmpty()) emptyList() else allMessages.filter {
                    it.type == "TEXT" && it.content?.text?.contains(q, ignoreCase = true) == true
                }
                adapter.submitList(results)
                searchBinding.tvSearchEmpty.isVisible = results.isEmpty()
            }
        })

        searchBinding.btnSearchClose.setOnClickListener { dialog.dismiss() }
        searchBinding.btnSearchCancel.setOnClickListener { dialog.dismiss() }
        dialog.show()
    }

    private fun confirmExitGroup() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.exit_group)
            .setMessage("退出后你将不再接收该群消息")
            .setNegativeButton("取消", null)
            .setPositiveButton("退出") { _, _ -> exitGroup() }
            .show()
    }

    private fun exitGroup() {
        val projectId = mainViewModel.currentProjectId()
        val groupId = arguments?.getString("groupId").orEmpty()
        if (projectId == null || groupId.isEmpty()) return

        viewLifecycleOwner.lifecycleScope.launch {
            chatRepo.leaveGroup(projectId, groupId, UUID.randomUUID().toString())
                .onSuccess {
                    Toast.makeText(requireContext(), "已退出群聊", Toast.LENGTH_SHORT).show()
                    // 回到群列表页；列表页 onResume 会自动刷新，移除已退出的群
                    findNavController().popBackStack(R.id.chatListFragment, false)
                }
                .onFailure { e ->
                    Toast.makeText(requireContext(), "退出失败：${e.message}", Toast.LENGTH_LONG).show()
                }
        }
    }

    private fun authedGlideUrl(uri: String): GlideUrl {
        val token = SessionStore.accessToken()
        val headers = LazyHeaders.Builder().apply {
            if (!token.isNullOrEmpty()) addHeader("Authorization", "Bearer $token")
        }.build()
        return GlideUrl(RetrofitClient.resolveMediaUrl(uri), headers)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
