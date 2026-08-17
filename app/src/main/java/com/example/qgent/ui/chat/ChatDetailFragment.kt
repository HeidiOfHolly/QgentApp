package com.example.qgent.ui.chat
import android.app.Dialog
import android.content.Intent
import android.graphics.drawable.Drawable
import android.net.Uri
import android.webkit.MimeTypeMap
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.os.Bundle
import android.provider.OpenableColumns
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.PopupMenu
import android.widget.Toast
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import androidx.core.os.bundleOf
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import com.bumptech.glide.Glide
import com.bumptech.glide.load.model.GlideUrl
import com.bumptech.glide.load.model.LazyHeaders
import com.bumptech.glide.request.target.CustomTarget
import com.bumptech.glide.request.transition.Transition
import com.example.qgent.QgentApp
import com.example.qgent.R
import com.example.qgent.data.SessionStore
import com.example.qgent.data.api.RetrofitClient
import com.example.qgent.data.local.MessageCache
import com.example.qgent.data.model.CreateMemoryRequest
import com.example.qgent.data.model.MentionDto
import com.example.qgent.data.model.MessageContentDto
import com.example.qgent.data.model.TaskCreateRequest
import com.example.qgent.data.model.TaskTriggerRequest
import com.example.qgent.data.model.toChatMessage
import com.example.qgent.data.model.toDiffFile
import com.example.qgent.data.model.toGroupMember
import com.example.qgent.data.repository.AttachmentUploader
import com.example.qgent.data.repository.ChatRepository
import com.example.qgent.data.sse.ProjectEventStream
import com.example.qgent.data.sse.SseEventType
import com.example.qgent.databinding.BottomSheetMentionMemberBinding
import com.example.qgent.databinding.DialogImagePreviewBinding
import com.example.qgent.databinding.FragmentChatDetailBinding
import com.example.qgent.model.ChatMessage
import com.example.qgent.model.DiffFile
import com.example.qgent.model.GroupMember
import com.example.qgent.model.GroupType
import com.example.qgent.model.MemberType
import com.example.qgent.model.MessageType
import com.example.qgent.model.SendState
import com.example.qgent.viewmodel.MainViewModel
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import okhttp3.Request

class ChatDetailFragment : Fragment() {

    private var _binding: FragmentChatDetailBinding? = null
    private val binding get() = _binding!!
    private val mainViewModel: MainViewModel by activityViewModels {
        (requireActivity().application as QgentApp).container.mainViewModelFactory
    }
    private val chatRepo: ChatRepository by lazy {
        (requireActivity().application as QgentApp).container.chatRepository
    }
    private val messageCache: MessageCache by lazy {
        (requireActivity().application as QgentApp).container.messageCache
    }
    private val attachmentUploader: AttachmentUploader by lazy {
        (requireActivity().application as QgentApp).container.attachmentUploader
    }
    private val eventStream: ProjectEventStream by lazy {
        (requireActivity().application as QgentApp).container.projectEventStream
    }
    private val diffRepo: com.example.qgent.data.repository.DiffRepository by lazy {
        (requireActivity().application as QgentApp).container.diffRepository
    }

    private val messages = mutableListOf<ChatMessage>()
    private lateinit var rows: MutableList<ChatRow>
    private lateinit var adapter: ChatMessageAdapter
    private var pollingJob: Job? = null
    private var eventStreamJob: Job? = null

    /** 当前引用的目标消息（非空时输入框上方显示引用条，发送时带 replyToId） */
    private var quoteTarget: ChatMessage? = null

    /** taskId → diffId 缓存（SSE diff 事件填充；后端任务详情可能不返回 diffId） */
    private val taskDiffIdMap = mutableMapOf<String, String>()

    /** 多选模式：长按消息选「多选」进入，点击消息切换选中，用于生成 Memory 草稿 */
    private var multiSelectMode = false
    private val selectedMessageIds = mutableSetOf<String>()

    // 系统相册选图：免存储权限，返回图片 content:// URI
    private val pickImage = registerForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) sendMediaMessage("IMAGE", uri)
    }

    // 系统文件选择器：任意类型文件，返回 content:// URI
    private val pickFile = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) sendMediaMessage("FILE", uri)
    }

    // 群成员 id → 昵称（文档 §7：消息 senderName 需按 senderId 反查）
    private var memberNamesById: Map<String, String> = emptyMap()

    // 群成员 id → 成员（含类型，@ 时按 USER/AGENT 生成 mention）
    private var memberById: Map<String, GroupMember> = emptyMap()

    private var groupMembers = emptyList<GroupMember>()

    /** 原始群成员（不含 Agent），供 agents 加载后动态合并 */
    private var baseGroupMembers = emptyList<GroupMember>()

    private val mentionWatcher = object : TextWatcher {
        private var lastAtPos = -1

        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}

        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
            val text = s?.toString() ?: return
            if (count == 1 && start < text.length && text[start] == '@') {
                lastAtPos = start
                showMentionPicker()
            }
        }

        override fun afterTextChanged(s: Editable?) {}
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentChatDetailBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.tvGroupName.text = arguments?.getString("groupName") ?: ""

        binding.btnBack.setOnClickListener { findNavController().popBackStack() }
        binding.btnSettings.setOnClickListener {
            findNavController().navigate(
                R.id.action_chatDetail_to_chatSettings,
                bundleOf("groupId" to (arguments?.getString("groupId") ?: ""))
            )
        }
        binding.btnPlus.setOnClickListener { showAttachmentMenu() }
        binding.btnSend.setOnClickListener { sendTextMessage() }

        binding.etInput.addTextChangedListener(mentionWatcher)

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            binding.inputBar.setPadding(0, 0, 0, bars.bottom + ime.bottom)
            insets
        }

        binding.etInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                sendTextMessage()
                true
            } else {
                false
            }
        }

        rows = buildRows(messages).toMutableList()
        adapter = ChatMessageAdapter(
            rows,
            onAvatarLongClick = { senderName -> insertMention(senderName) },
            onImageClick = { uri -> showImagePreview(uri) },
            onFileClick = { message -> openFile(message) },
            onMessageLongClick = { anchor, message -> showMessageLongPressMenu(anchor, message) },
            onLoadDiff = { diffId, onLoaded -> loadDiff(diffId, onLoaded) },
            onMessageClick = { message -> onMessageRowClick(message) },
            onSendFailedClick = { message -> showResendDialog(message) },
            onTaskStatusClick = { message -> onTaskStatusCardClick(message) }
        )
        binding.rvMessages.layoutManager = LinearLayoutManager(requireContext())
        binding.rvMessages.adapter = adapter

        // 取消引用：关闭引用条，发送不再带 replyToId
        binding.btnCancelQuote.setOnClickListener { clearQuote() }

        // 多选操作条：取消 / 生成 Memory 草稿
        binding.btnCancelMultiSelect.setOnClickListener { exitMultiSelect() }
        binding.btnCreateMemoryDraft.setOnClickListener { createMemoryDraftFromSelection() }

        loadInitialData()

        // 团队 Agent 异步加载完成后重建成员映射（@ 弹窗始终包含 Agent）
        mainViewModel.agents.observe(viewLifecycleOwner) { _ ->
            rebuildMemberMaps()
        }
    }

    private fun sendTextMessage() {
        val text = binding.etInput.text.toString().trim()
        if (text.isEmpty()) return
        // 契约 §7：消息体不再携带 mentions；仅客户端解析 @Agent 用于发送成功后弹「触发任务」弹窗
        val mentions = extractMentions(text)
        val agentMentioned = mentions.any { it.type == "AGENT" }
        val replyToId = quoteTarget?.id
        val replyToSummary = quoteTarget?.let { "${it.senderName}：${it.displayContent()}" }
        Log.d("SendMsg", "send text=$text mentions=$mentions replyToId=$replyToId memberNamesById=$memberNamesById")
        binding.etInput.text.clear()
        clearQuote()

        val projectId = mainViewModel.currentProjectId()
        val groupId = arguments?.getString("groupId").orEmpty()

        if (projectId != null && groupId.isNotEmpty()) {
            // 乐观插入本地消息（发送中：旁边显示小加载标），成功后替换为服务端消息，失败标记红色感叹号
            val local = ChatMessage(
                id = LOCAL_ID_PREFIX + UUID.randomUUID(),
                senderName = "我",
                content = text,
                type = MessageType.TEXT,
                timestamp = System.currentTimeMillis(),
                isMine = true,
                sequence = 0,
                replyToId = replyToId,
                replyToSummary = replyToSummary,
                sendState = SendState.SENDING
            )
            appendMessage(local)
            viewLifecycleOwner.lifecycleScope.launch {
                // Idempotency-Key 后端强制要求，防重复提交；每次发送都是全新消息，生成新 UUID
                chatRepo.sendMessage(projectId, groupId, "TEXT", MessageContentDto(text = text), replyToId = replyToId, idempotencyKey = UUID.randomUUID().toString())
                    .onSuccess { dto ->
                        Log.d("SendMsg", "send success id=${dto.id} senderId=${dto.senderId} mentions=${dto.mentions} replyTo=${dto.replyToId}")
                        replaceLocalMessage(local.id, dto.toChatMessage(SessionStore.user()?.id, memberNamesById))
                        // @ 了 Agent → 发送成功后弹「发起任务」弹窗，确认后调 trigger-task（契约 §7）
                        if (agentMentioned) {
                            showCreateTaskDialog(
                                prefillTitle = text.take(30),
                                prefillRequirement = text,
                                messageId = dto.id
                            )
                        }
                    }
                    .onFailure { e ->
                        Log.e("SendMsg", "send FAILED: ${e::class.simpleName} message=${e.message}", e)
                        markSendFailed(local.id, e.message)
                    }
            }
        } else {
            Log.d("SendMsg", "no projectId/groupId → local fallback. projectId=$projectId groupId=$groupId")
            // 无项目/群：本地兜底消息直接标记发送失败（红色感叹号，可点击删除），不再提示“仅自己可见”
            appendLocalMessage(text)
        }
    }

    /**
     * 解析输入文本中的 @成员名，反查成员 id 组装结构化 mentions（后端据此实现 @ 通知）。
     * 按成员类型生成 mention：普通用户 → USER，Agent → AGENT（文档 §7）。
     *
     * Agent 判定以团队 Agent 名单（getAgents）为准：即使群成员条目的类型因后端改名、
     * 缺 memberType 被启发式误判为 HUMAN，只要 id/名字命中 Agent 名单仍按 AGENT 发送，
     * 避免后端因 mention 类型错误（USER 却指向 agentId）拒绝消息。
     */
    private fun extractMentions(text: String): List<MentionDto> {
        Log.d("Mention", "extract from '$text', memberById=${memberById.map { "${it.value.name}:${it.value.type}" }}")
        if (memberById.isEmpty()) return emptyList()
        val activeAgents = mainViewModel.agents.value.orEmpty()
            .filter { it.status.name != "ARCHIVED" }
        val agentIds = activeAgents.map { it.id }.toSet()
        val agentNames = activeAgents.map { it.name }.toSet()
        val mentions = mutableListOf<MentionDto>()
        // 成员名可含空格（如 "开发 Agent"）：按名字长度降序，优先匹配最长的成员名
        val sortedMembers = memberById.entries.sortedByDescending { it.value.name.length }
        sortedMembers.forEach { (id, member) ->
            if (Regex("@" + Regex.escape(member.name) + "(?=\\s|$)").containsMatchIn(text)) {
                val isAgent = member.type == MemberType.AGENT || id in agentIds || member.name in agentNames
                mentions.add(
                    MentionDto(
                        type = if (isAgent) "AGENT" else "USER",
                        id = id
                    )
                )
            }
        }
        return mentions.distinct()
    }

    /** 上传附件 → 发送 IMAGE/FILE 消息：先乐观插入本地消息（发送中：小加载标），成功后替换为服务端消息，失败标记红色感叹号 */
    private fun sendMediaMessage(type: String, uri: Uri) {
        val projectId = mainViewModel.currentProjectId()
        val groupId = arguments?.getString("groupId").orEmpty()
        if (projectId == null || groupId.isEmpty()) {
            Toast.makeText(requireContext(), R.string.todo_placeholder, Toast.LENGTH_SHORT).show()
            return
        }
        val meta = readFileMeta(uri)
        // 乐观占位消息：图片/文件气泡先展示本地内容（content=本地 uri），发送成功后替换
        val local = ChatMessage(
            id = LOCAL_ID_PREFIX + UUID.randomUUID(),
            senderName = "我",
            content = uri.toString(),
            type = if (type == "IMAGE") MessageType.IMAGE else MessageType.FILE,
            timestamp = System.currentTimeMillis(),
            isMine = true,
            sequence = 0,
            fileName = if (type == "FILE") meta.fileName else null,
            fileSize = if (type == "FILE") meta.sizeBytes else null,
            sendState = SendState.SENDING
        )
        appendMessage(local)

        viewLifecycleOwner.lifecycleScope.launch {
            val bytes = readBytes(uri)
            if (bytes == null) {
                markSendFailed(local.id, "读取文件失败")
                return@launch
            }
            // 部分 provider 读不到 SIZE，用实际字节数兜底，避免 sizeBytes=0 被后端拒绝
            val size = if (meta.sizeBytes > 0) meta.sizeBytes else bytes.size.toLong()

            attachmentUploader.upload(projectId, meta.fileName, meta.mimeType, size, bytes)
                .onSuccess { url ->
                    val content = if (type == "IMAGE") {
                        MessageContentDto(text = null, url = url)
                    } else {
                        MessageContentDto(
                            text = null, url = url,
                            name = meta.fileName, size = size, mimeType = meta.mimeType
                        )
                    }
                    chatRepo.sendMessage(projectId, groupId, type, content, replyToId = quoteTarget?.id, idempotencyKey = UUID.randomUUID().toString())
                        .onSuccess { dto ->
                            clearQuote()
                            replaceLocalMessage(local.id, dto.toChatMessage(SessionStore.user()?.id, memberNamesById))
                        }
                        .onFailure { e ->
                            Log.e("SendMsg", "media send FAILED: ${e.message}", e)
                            markSendFailed(local.id, e.message)
                        }
                }
                .onFailure { e ->
                    Log.e("SendMsg", "media upload FAILED: ${e.message}", e)
                    markSendFailed(local.id, e.message)
                }
        }
    }

    private data class FileMeta(val fileName: String, val sizeBytes: Long, val mimeType: String?)

    private fun readFileMeta(uri: Uri): FileMeta {
        val resolver = requireContext().contentResolver
        var fileName = "file"
        var sizeBytes = 0L
        resolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (nameIdx >= 0) fileName = cursor.getString(nameIdx) ?: fileName
                val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (sizeIdx >= 0 && !cursor.isNull(sizeIdx)) sizeBytes = cursor.getLong(sizeIdx)
            }
        }
        return FileMeta(fileName, sizeBytes, resolver.getType(uri))
    }

    private suspend fun readBytes(uri: Uri): ByteArray? = withContext(Dispatchers.IO) {
        runCatching {
            requireContext().contentResolver.openInputStream(uri)?.use { it.readBytes() }
        }.getOrNull()
    }

    /** 无项目/群时的本地兜底消息：标记发送失败（红色感叹号，可点击删除），不落缓存、不与网络消息混淆 */
    private fun appendLocalMessage(text: String) {
        appendMessage(
            ChatMessage(
                LOCAL_ID_PREFIX + UUID.randomUUID(),
                "我",
                text,
                MessageType.TEXT,
                System.currentTimeMillis(),
                true,
                sequence = 0,
                sendState = SendState.FAILED
            )
        )
    }

    /** 长按消息：弹出 引用/复制/多选 菜单（全部消息可引用） */
    private fun showMessageLongPressMenu(anchor: View, message: ChatMessage) {
        val popup = PopupMenu(requireContext(), anchor)
        popup.menuInflater.inflate(R.menu.menu_message_long_press, popup.menu)
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_quote -> setQuote(message)
                R.id.action_copy -> copyMessage(message)
                R.id.action_multi_select -> enterMultiSelect()
            }
            true
        }
        popup.show()
    }

    // ── 多选模式：长按「多选」进入，点击消息切换选中，生成 Memory 草稿 ──

    private fun onMessageRowClick(message: ChatMessage) {
        if (multiSelectMode) {
            toggleMultiSelect(message.id)
        }
        // 非多选模式：气泡内点击已有各自处理（图片/文件），此处不接管
    }

    /** 进入多选模式：显示多选操作条，首个长按消息默认选中 */
    private fun enterMultiSelect() {
        multiSelectMode = true
        selectedMessageIds.clear()
        binding.llMultiSelectBar.isVisible = true
        binding.inputBar.isVisible = false
        updateMultiSelectBar()
        adapter.setMultiSelectMode(true)
        adapter.setSelectedIds(selectedMessageIds)
    }

    /** 退出多选模式：清空选中、隐藏操作条、恢复输入栏 */
    private fun exitMultiSelect() {
        multiSelectMode = false
        selectedMessageIds.clear()
        binding.llMultiSelectBar.isVisible = false
        binding.inputBar.isVisible = true
        adapter.setMultiSelectMode(false)
        adapter.setSelectedIds(emptySet())
    }

    private fun toggleMultiSelect(messageId: String) {
        if (!selectedMessageIds.add(messageId)) {
            selectedMessageIds.remove(messageId)
        }
        updateMultiSelectBar()
        adapter.setSelectedIds(selectedMessageIds)
    }

    private fun updateMultiSelectBar() {
        binding.tvMultiSelectCount.text = getString(R.string.multi_select_count, selectedMessageIds.size)
    }

    /** 生成 Memory 草稿：让用户填标题，内容固定为选中消息拼接，POST /memories 提交审核 */
    private fun createMemoryDraftFromSelection() {
        if (selectedMessageIds.isEmpty()) {
            Toast.makeText(requireContext(), R.string.memory_draft_empty, Toast.LENGTH_SHORT).show()
            return
        }
        val projectId = mainViewModel.currentProjectId()
        if (projectId == null) {
            Toast.makeText(requireContext(), R.string.add_member_missing_project, Toast.LENGTH_SHORT).show()
            return
        }
        val selected = messages.filter { it.id in selectedMessageIds }
            .filter { it.type != MessageType.SYSTEM }
        if (selected.isEmpty()) {
            Toast.makeText(requireContext(), R.string.memory_draft_empty, Toast.LENGTH_SHORT).show()
            return
        }

        // 弹窗：仅输入 Memory 标题，内容固定为选中消息拼接（不提供简介输入，避免覆盖聊天记录）
        val container = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 16, 48, 8)
        }
        val etTitle = EditText(requireContext()).apply {
            hint = getString(R.string.memory_draft_title_hint)
            textSize = 14f
        }
        container.addView(etTitle)

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.memory_draft_title)
            .setView(container)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.confirm) { _, _ ->
                val title = etTitle.text.toString().trim()
                if (title.isEmpty()) {
                    Toast.makeText(requireContext(), R.string.memory_draft_title_required, Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                // 内容固定为选中消息拼接，不再允许用简介覆盖聊天记录
                val content = selected.joinToString("\n") { "${it.senderName}：${it.displayContent()}" }
                submitMemoryDraft(projectId, title, content)
            }
            .show()
    }

    private fun submitMemoryDraft(projectId: String, title: String, content: String) {
        viewLifecycleOwner.lifecycleScope.launch {
            // 创建草稿（DRAFT）→ 立即提交审核（PENDING_REVIEW，进审核队列）
            memoryRepo().createMemory(
                projectId,
                CreateMemoryRequest(title = title, content = content, category = "聊天记录"),
                UUID.randomUUID().toString()
            ).onSuccess { draft ->
                memoryRepo().submitReview(
                    projectId, draft.id,
                    UUID.randomUUID().toString()
                ).onSuccess {
                    Toast.makeText(requireContext(), R.string.memory_draft_created, Toast.LENGTH_SHORT).show()
                    exitMultiSelect()
                }.onFailure {
                    Toast.makeText(requireContext(), R.string.memory_draft_failed, Toast.LENGTH_SHORT).show()
                }
            }.onFailure {
                Toast.makeText(requireContext(), R.string.memory_draft_failed, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun memoryRepo(): com.example.qgent.data.repository.MemoryRepository =
        (requireActivity().application as QgentApp).container.memoryRepository

    /** 设置引用目标：显示引用条，发送时带 replyToId */
    private fun setQuote(message: ChatMessage) {
        quoteTarget = message
        binding.tvQuoteBar.text = getString(R.string.quote_prefix, message.senderName) +
            "：" + message.displayContent()
        binding.llQuoteBar.isVisible = true
        binding.etInput.requestFocus()
    }

    /** 清除引用：隐藏引用条，发送不再带 replyToId */
    private fun clearQuote() {
        quoteTarget = null
        binding.llQuoteBar.isVisible = false
    }

    /** 复制消息文本到剪贴板 */
    private fun copyMessage(message: ChatMessage) {
        val label = if (message.type == MessageType.IMAGE || message.type == MessageType.FILE) {
            message.displayContent()
        } else {
            message.content
        }
        val cm = requireContext().getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        cm.setPrimaryClip(android.content.ClipData.newPlainText("chat", label))
        Toast.makeText(requireContext(), R.string.message_copied, Toast.LENGTH_SHORT).show()
    }

    /** 弹出 @ 成员选择器 */
    private fun showMentionPicker() {
        val dialog = BottomSheetDialog(requireContext())
        val sheetBinding = BottomSheetMentionMemberBinding.inflate(layoutInflater)
        dialog.setContentView(sheetBinding.root)

        val mentionAdapter = MentionMemberAdapter(groupMembers) { member ->
            insertMention(member.name)
            dialog.dismiss()
        }
        sheetBinding.rvMentionMembers.layoutManager = LinearLayoutManager(requireContext())
        sheetBinding.rvMentionMembers.adapter = mentionAdapter

        dialog.show()
    }

    /** 点击图片：全屏预览放大后的原图，点击任意处关闭；加载中显示居中加载态 */
    private fun showImagePreview(uri: String) {
        val dialog = Dialog(requireContext())
        val previewBinding = DialogImagePreviewBinding.inflate(layoutInflater)
        dialog.setContentView(previewBinding.root)
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.window?.setLayout(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT
        )
        val loading = previewBinding.pbPreviewLoading
        loading.isVisible = true
        val loader = if (uri.startsWith("content://") || uri.startsWith("file://")) {
            // 本地 content:// URI（发送中的乐观占位图）直接加载，无需鉴权头
            Glide.with(previewBinding.ivPreview).load(uri)
        } else {
            val token = SessionStore.accessToken()
            val headers = LazyHeaders.Builder().apply {
                if (!token.isNullOrEmpty()) addHeader("Authorization", "Bearer $token")
            }.build()
            Glide.with(previewBinding.ivPreview)
                .load(GlideUrl(RetrofitClient.resolveMediaUrl(uri), headers))
        }
        loader.placeholder(android.R.color.transparent).into(object : CustomTarget<Drawable>() {
            override fun onResourceReady(resource: Drawable, transition: Transition<in Drawable>?) {
                loading.isVisible = false
                previewBinding.ivPreview.setImageDrawable(resource)
            }

            override fun onLoadCleared(placeholder: Drawable?) {
                loading.isVisible = false
            }

            override fun onLoadFailed(errorDrawable: Drawable?) {
                loading.isVisible = false
            }
        })
        previewBinding.ivPreview.onSingleTap = { dialog.dismiss() }
        dialog.show()
    }

    /** 点击文件气泡：文本类下载后内置预览，其余下载后调系统应用打开 */
    private fun openFile(message: ChatMessage) {
        val url = message.content
        if (url.isEmpty()) {
            Toast.makeText(requireContext(), R.string.todo_placeholder, Toast.LENGTH_SHORT).show()
            return
        }
        val fileName = message.fileName ?: "file"
        val mimeType = inferMimeType(fileName)
        viewLifecycleOwner.lifecycleScope.launch {
            Toast.makeText(requireContext(), "正在打开…", Toast.LENGTH_SHORT).show()
            val file = downloadFile(url, fileName, requireContext().cacheDir)
            if (file == null) {
                Toast.makeText(requireContext(), "下载失败", Toast.LENGTH_SHORT).show()
                return@launch
            }
            if (isTextFile(fileName, mimeType)) {
                showTextPreview(fileName, readTextContent(file))
            } else {
                openWithSystemApp(file, mimeType)
            }
        }
    }

    /** 下载附件到 cacheDir/downloads，返回本地文件；失败返回 null */
    private suspend fun downloadFile(url: String, fileName: String, cacheDir: File): File? = withContext(Dispatchers.IO) {
        runCatching {
            val target = File(File(cacheDir, "downloads"), fileName)
            target.parentFile?.mkdirs()
            val request = Request.Builder().url(RetrofitClient.resolveMediaUrl(url)).build()
            RetrofitClient.httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw IllegalStateException("HTTP ${response.code}")
                val body = response.body ?: throw IllegalStateException("empty body")
                target.outputStream().use { out -> body.byteStream().copyTo(out) }
            }
            target
        }.getOrNull()
    }

    private suspend fun readTextContent(file: File): String = withContext(Dispatchers.IO) {
        val raw = runCatching { file.readText(Charsets.UTF_8) }
            .getOrElse { runCatching { file.readText(Charsets.ISO_8859_1) }.getOrDefault("") }
        if (raw.length > MAX_PREVIEW_CHARS) raw.take(MAX_PREVIEW_CHARS) + "\n…（内容过长已截断）" else raw
    }

    /** 文本文件内置预览：ScrollView + 可选中 TextView */
    private fun showTextPreview(fileName: String, content: String) {
        val scroll = ScrollView(requireContext())
        val tv = TextView(requireContext()).apply {
            text = content
            setTextIsSelectable(true)
            setPadding(48, 40, 48, 40)
            textSize = 14f
        }
        scroll.addView(
            tv,
            ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(fileName)
            .setView(scroll)
            .setPositiveButton(R.string.close, null)
            .show()
    }

    /** 非文本文件：下载后用 FileProvider 供系统应用打开 */
    private fun openWithSystemApp(file: File, mimeType: String?) {
        try {
            val uri = FileProvider.getUriForFile(requireContext(), "${requireContext().packageName}.fileprovider", file)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mimeType ?: "*/*")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            if (intent.resolveActivity(requireContext().packageManager) == null) {
                Toast.makeText(requireContext(), "未找到可打开此文件的应用", Toast.LENGTH_SHORT).show()
                return
            }
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(requireContext(), "打开失败：${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun inferMimeType(fileName: String): String? {
        val ext = MimeTypeMap.getFileExtensionFromUrl(fileName).lowercase()
        return if (ext.isEmpty()) null else MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
    }

    private fun isTextFile(fileName: String, mimeType: String?): Boolean {
        if (mimeType?.startsWith("text/") == true) return true
        val ext = fileName.substringAfterLast('.', "").lowercase()
        return ext in TEXT_EXTENSIONS
    }

    /** 将 @Name 插入到输入框当前光标位置 */
    private fun insertMention(name: String) {
        val text = binding.etInput.text ?: return
        // 找到最后一个 @ 的位置，替换 @ 及其后面的部分字符
        val atIndex = text.lastIndexOf('@')
        val mentionText = "@$name "
        if (atIndex >= 0) {
            text.replace(atIndex, text.length, mentionText)
        } else {
            text.append(mentionText)
        }
        binding.etInput.setSelection(text.length)
    }

    /**
     * 发起任务弹窗：标题 + 需求描述 + 选项目绑定仓库。
     * 从群消息 @Agent 触发（[messageId] 非空，契约 §7）→ 调 trigger-task；
     * 从「+ 菜单」进入（[messageId] 为空）→ 调 POST /tasks 创建（requirementGroupId=当前群）。
     */
    private fun showCreateTaskDialog(
        prefillTitle: String = "",
        prefillRequirement: String = "",
        messageId: String? = null
    ) {
        val projectId = mainViewModel.currentProjectId() ?: return
        val groupId = arguments?.getString("groupId").orEmpty()
        if (groupId.isEmpty()) return

        val container = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 16, 48, 8)
        }
        val etTitle = EditText(requireContext()).apply {
            hint = getString(R.string.start_task_name_hint)
            textSize = 14f
            setText(prefillTitle)
        }
        val etRequirement = EditText(requireContext()).apply {
            hint = getString(R.string.start_task_requirement_hint)
            textSize = 14f
            minLines = 3
            gravity = android.view.Gravity.TOP
            setText(prefillRequirement)
        }
        val tvRepoLabel = TextView(requireContext()).apply {
            text = getString(R.string.manage_repositories)
            textSize = 14f
        }
        val repoChecks = mutableListOf<android.widget.CheckBox>()

        container.addView(etTitle)
        container.addView(etRequirement)
        container.addView(tvRepoLabel)

        // 加载项目绑定仓库（文档 §6 ProjectRepository），失败时提示
        // repositoryIds 必须用 getProjectRepositories 返回的 id（project_repositories.id，清单二）
        val repoBranchMap = mutableMapOf<String, String>()   // repoId -> defaultBranch
        viewLifecycleOwner.lifecycleScope.launch {
            val repos = githubRepo().getProjectRepositories(projectId).getOrNull().orEmpty()
            if (repos.isEmpty()) {
                tvRepoLabel.text = getString(R.string.start_task_repo_required)
                return@launch
            }
            repos.forEach { repo ->
                val cb = android.widget.CheckBox(requireContext()).apply {
                    text = repo.displayName
                    textSize = 14f
                    tag = repo.id
                    isChecked = repos.size == 1
                }
                repoBranchMap[repo.id] = repo.defaultBranch
                repoChecks.add(cb)
                container.addView(cb)
            }
        }

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.start_task_title)
            .setView(container)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.confirm) { _, _ ->
                val title = etTitle.text.toString().trim()
                val requirement = etRequirement.text.toString().trim()
                val repoIds = repoChecks.filter { it.isChecked }.map { it.tag as String }
                // baseRef 取仓库默认分支（清单二：不要写死或留空）
                val baseRef = repoIds.firstOrNull()?.let { repoBranchMap[it] }
                when {
                    title.isEmpty() -> Toast.makeText(requireContext(), R.string.start_task_name_required, Toast.LENGTH_SHORT).show()
                    requirement.isEmpty() -> Toast.makeText(requireContext(), R.string.start_task_requirement_required, Toast.LENGTH_SHORT).show()
                    repoIds.isEmpty() -> Toast.makeText(requireContext(), R.string.start_task_repo_required, Toast.LENGTH_SHORT).show()
                    messageId != null -> triggerTaskFromMessage(projectId, groupId, messageId, title, requirement, repoIds, baseRef)
                    else -> createTask(projectId, groupId, title, requirement, repoIds, baseRef)
                }
            }
            .show()
    }

    private fun createTask(
        projectId: String,
        groupId: String,
        title: String,
        requirement: String,
        repoIds: List<String>,
        baseRef: String?
    ) {
        viewLifecycleOwner.lifecycleScope.launch {
            taskRepo().createTask(
                projectId,
                TaskCreateRequest(
                    requirementGroupId = groupId,
                    title = title,
                    requirement = requirement,
                    repositoryIds = repoIds,
                    baseRef = baseRef
                ),
                UUID.randomUUID().toString()
            ).onSuccess {
                Toast.makeText(requireContext(), R.string.start_task_success, Toast.LENGTH_LONG).show()
            }.onFailure { e ->
                val rid = if (e is com.example.qgent.data.model.ApiException && e.code.startsWith("HTTP_500")) {
                    e.requestId?.let { "\nrequestId: $it" }.orEmpty()
                } else {
                    ""
                }
                Toast.makeText(requireContext(), "${getString(R.string.start_task_failed)}：${e.message}$rid", Toast.LENGTH_LONG).show()
            }
        }
    }

    /** 契约 §7：从已发送的群消息显式触发 Task（POST .../messages/{messageId}/trigger-task） */
    private fun triggerTaskFromMessage(
        projectId: String,
        groupId: String,
        messageId: String,
        title: String,
        requirement: String,
        repoIds: List<String>,
        baseRef: String?
    ) {
        viewLifecycleOwner.lifecycleScope.launch {
            taskRepo().triggerTask(
                projectId, groupId, messageId,
                TaskTriggerRequest(
                    title = title,
                    requirement = requirement,
                    repositoryIds = repoIds,
                    baseRef = baseRef
                ),
                UUID.randomUUID().toString()
            ).onSuccess {
                Toast.makeText(requireContext(), R.string.start_task_success, Toast.LENGTH_LONG).show()
            }.onFailure { e ->
                val rid = if (e is com.example.qgent.data.model.ApiException && e.code.startsWith("HTTP_500")) {
                    e.requestId?.let { "\nrequestId: $it" }.orEmpty()
                } else {
                    ""
                }
                Toast.makeText(requireContext(), "${getString(R.string.start_task_failed)}：${e.message}$rid", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun githubRepo(): com.example.qgent.data.repository.GitHubRepository =
        (requireActivity().application as QgentApp).container.githubRepository

    private fun taskRepo(): com.example.qgent.data.repository.TaskRepository =
        (requireActivity().application as QgentApp).container.taskRepository

    private fun showAttachmentMenu() {
        val popup = PopupMenu(requireContext(), binding.btnPlus)
        popup.menu.add(getString(R.string.image))
        popup.menu.add(getString(R.string.file))
        popup.menu.add(getString(R.string.start_task))
        popup.setOnMenuItemClickListener { item ->
            when (item.title) {
                getString(R.string.image) -> pickImage.launch(
                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                )
                getString(R.string.file) -> pickFile.launch(arrayOf("*/*"))
                getString(R.string.start_task) -> showCreateTaskDialog()
            }
            true
        }
        popup.show()
    }

    private fun appendMessage(message: ChatMessage) {
        // 引用消息：后端只回 replyToId 无被引用内容，从本地列表反查生成摘要，保证自己发的引用也显示
        val resolved = resolveReplySummary(message)
        messages.add(resolved)
        val start = rows.size
        val prevTime = messages.getOrNull(messages.size - 2)?.timestamp
        if (prevTime == null || resolved.timestamp - prevTime > TIME_GAP_MS) {
            rows.add(ChatRow.Time(formatTime(resolved.timestamp)))
        }
        rows.add(ChatRow.Message(resolved))
        adapter.notifyItemRangeInserted(start, rows.size - start)
        scrollToBottom()
        saveCache()
    }

    /** 立即落缓存（本地兜底/乐观消息不落缓存，防止幽灵消息持久化残留） */
    private fun saveCache() {
        val groupId = arguments?.getString("groupId").orEmpty()
        if (groupId.isNotEmpty()) {
            viewLifecycleOwner.lifecycleScope.launch {
                messageCache.save(groupId, messages.filterNot { it.id.startsWith(LOCAL_ID_PREFIX) })
            }
        }
    }

    /** 刷新消息行（与 messages 重新对齐）并滚动到底部 */
    private fun refreshRows() {
        rows.clear()
        rows.addAll(buildRows(messages))
        adapter.notifyDataSetChanged()
        scrollToBottom()
    }

    /** 发送成功后用服务端确认消息替换本地乐观消息（并落缓存） */
    private fun replaceLocalMessage(localId: String, network: ChatMessage) {
        val idx = messages.indexOfFirst { it.id == localId }
        if (idx >= 0) {
            messages[idx] = network
            refreshRows()
            saveCache()
        } else {
            appendMessage(network)
        }
    }

    /** 设置本地消息发送状态并刷新（SENDING → 小加载标；FAILED → 红色感叹号） */
    private fun markSendState(localId: String, state: SendState, error: String? = null) {
        val idx = messages.indexOfFirst { it.id == localId }
        if (idx >= 0) {
            messages[idx] = messages[idx].copy(sendState = state, sendError = error)
            refreshRows()
        }
    }

    /** 发送失败：标记红色感叹号（不再提示“仅自己可见”），可携带后端错误原因用于弹窗展示 */
    private fun markSendFailed(localId: String, error: String? = null) =
        markSendState(localId, SendState.FAILED, error)

    /** 删除本地失败消息（不落缓存） */
    private fun removeLocalMessage(localId: String) {
        val idx = messages.indexOfFirst { it.id == localId }
        if (idx >= 0) {
            messages.removeAt(idx)
            refreshRows()
        }
    }

    /** 点击红色感叹号：弹窗选择重新发送或删除（展示失败原因便于排查） */
    private fun showResendDialog(message: ChatMessage) {
        val reason = message.sendError?.takeIf { it.isNotBlank() }?.let { "\n\n失败原因：$it" }.orEmpty()
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.chat_send_failed_title)
            .setMessage(getString(R.string.chat_send_failed_resend) + reason)
            .setPositiveButton(R.string.chat_resend) { _, _ -> resendMessage(message) }
            .setNegativeButton(R.string.chat_delete_failed) { _, _ -> removeLocalMessage(message.id) }
            .show()
    }

    /** 重新发送失败消息：恢复为发送中状态后再次走发送流程 */
    private fun resendMessage(message: ChatMessage) {
        markSendState(message.id, SendState.SENDING)
        val projectId = mainViewModel.currentProjectId()
        val groupId = arguments?.getString("groupId").orEmpty()
        if (projectId == null || groupId.isEmpty()) {
            markSendFailed(message.id)
            return
        }
        when (message.type) {
            MessageType.TEXT -> resendText(message, projectId, groupId)
            MessageType.IMAGE, MessageType.FILE -> resendMedia(message, projectId, groupId)
            else -> removeLocalMessage(message.id) // 其他类型不支持重发，直接移除
        }
    }

    private fun resendText(message: ChatMessage, projectId: String, groupId: String) {
        viewLifecycleOwner.lifecycleScope.launch {
            // 契约 §7：消息体不再携带 mentions；仅客户端解析 @Agent 决定是否弹「触发任务」
            val agentMentioned = extractMentions(message.content).any { it.type == "AGENT" }
            chatRepo.sendMessage(
                projectId, groupId, "TEXT",
                MessageContentDto(text = message.content),
                replyToId = message.replyToId,
                idempotencyKey = UUID.randomUUID().toString()
            )
                .onSuccess { dto ->
                    replaceLocalMessage(message.id, dto.toChatMessage(SessionStore.user()?.id, memberNamesById))
                    if (agentMentioned) {
                        showCreateTaskDialog(
                            prefillTitle = message.content.take(30),
                            prefillRequirement = message.content,
                            messageId = dto.id
                        )
                    }
                }
                .onFailure { e ->
                    Log.e("SendMsg", "resend text FAILED: ${e.message}", e)
                    markSendFailed(message.id, e.message)
                }
        }
    }

    private fun resendMedia(message: ChatMessage, projectId: String, groupId: String) {
        val uri = runCatching { Uri.parse(message.content) }.getOrNull()
        if (uri == null) {
            markSendFailed(message.id)
            return
        }
        viewLifecycleOwner.lifecycleScope.launch {
            val meta = readFileMeta(uri)
            val bytes = readBytes(uri)
            if (bytes == null) {
                markSendFailed(message.id)
                return@launch
            }
            val size = if (meta.sizeBytes > 0) meta.sizeBytes else bytes.size.toLong()
            attachmentUploader.upload(projectId, meta.fileName, meta.mimeType, size, bytes)
                .onSuccess { url ->
                    val content = if (message.type == MessageType.IMAGE) {
                        MessageContentDto(text = null, url = url)
                    } else {
                        MessageContentDto(
                            text = null, url = url,
                            name = meta.fileName, size = size, mimeType = meta.mimeType
                        )
                    }
                    chatRepo.sendMessage(
                        projectId, groupId,
                        if (message.type == MessageType.IMAGE) "IMAGE" else "FILE",
                        content,
                        replyToId = message.replyToId,
                        idempotencyKey = UUID.randomUUID().toString()
                    )
                        .onSuccess { dto ->
                            replaceLocalMessage(message.id, dto.toChatMessage(SessionStore.user()?.id, memberNamesById))
                        }
                        .onFailure { e ->
                            Log.e("SendMsg", "resend media FAILED: ${e.message}", e)
                            markSendFailed(message.id, e.message)
                        }
                }
                .onFailure { e ->
                    Log.e("SendMsg", "resend media upload FAILED: ${e.message}", e)
                    markSendFailed(message.id, e.message)
                }
        }
    }

    /**
     * 为引用消息补全摘要：replyToId 非空但缺 replyToSummary 时，
     * 从当前消息列表反查被引用消息，拼成「发送者：内容」；查不到时兜底「引用消息」。
     */
    private fun resolveReplySummary(message: ChatMessage): ChatMessage {
        val replyId = message.replyToId ?: return message
        if (message.replyToSummary != null) return message
        val target = messages.firstOrNull { it.id == replyId }
        val summary = target?.let { "${it.senderName}：${it.displayContent()}" }
            ?: "引用消息"
        return message.copy(replyToSummary = summary)
    }

    /**
     * 拉取 DIFF 消息的文件内容：真实接口优先，失败由数据层 mock 保底（测试完成后移除）。
     * 结果通过 [onLoaded] 回传给 Diff 卡片渲染。
     */
    private fun loadDiff(diffId: String, onLoaded: (List<DiffFile>) -> Unit) {
        val projectId = mainViewModel.currentProjectId() ?: return
        viewLifecycleOwner.lifecycleScope.launch {
            val files = diffRepo.getDiffFiles(projectId, diffId)
                .getOrNull().orEmpty()
                .map { it.toDiffFile() }
            onLoaded(files)
        }
    }

    /**
     * TASK_STATUS 卡片点击：任务处于"待确认 Diff"时，拉任务详情 → 解析 diffReviewSummary
     * → 弹 Diff Review 确认对话框。
     *
     * 确认/拒绝走 Task 级最终 Diff Review 批次接口（§12.3）：
     * 批次内 Diff 禁止用单 Diff accept/reject（409 DIFF_BATCH_REVIEW_REQUIRED），
     * 因此这里只依赖 taskId，diffId 仅用于展示首个 Diff 的文件内容。
     */
    private fun onTaskStatusCardClick(message: ChatMessage) {
        val projectId = mainViewModel.currentProjectId() ?: return
        val taskId = message.taskId ?: run {
            Toast.makeText(requireContext(), "任务状态：${message.content}", Toast.LENGTH_SHORT).show()
            return
        }
        viewLifecycleOwner.lifecycleScope.launch {
            taskRepo().getTaskDetail(projectId, taskId)
                .onSuccess { detail ->
                    val diffSummary = detail.diffReviewSummary
                    // 从 JsonElement 解析 diffId：仅用于展示首个 Diff 内容；解析不到仍可确认整个批次
                    val diffId = extractDiffId(diffSummary) ?: taskDiffIdMap[taskId]
                    // reviewStatus：仅 PENDING_CONFIRMATION 显示确认/拒绝按钮，其余只读展示
                    val reviewStatus = extractStringField(diffSummary, "reviewStatus")
                    // 后端能力位优先（可确认/可拒绝/可重试交付），缺省按 reviewStatus 兜底。
                    // MR_FIRST（自动交付）由系统授权，不展示 Diff 确认/拒绝操作（文档 §15.2）
                    val caps = detail.capabilities
                    val mrFirst = detail.deliveryMode == "MR_FIRST"
                    val canDecide = !mrFirst && (caps?.canConfirmDiffReview
                        ?: (reviewStatus == "PENDING_CONFIRMATION" || reviewStatus.isNullOrBlank()))
                    val canRetry = caps?.canRetryDelivery == true
                    showDiffConfirmDialog(
                        projectId, taskId, diffId, detail.title, detail.status,
                        canDecide = canDecide,
                        canRetry = canRetry,
                        deliveryMode = detail.deliveryMode
                    )
                }
                .onFailure { e ->
                    Toast.makeText(requireContext(), "加载任务失败：${e.message}", Toast.LENGTH_SHORT).show()
                }
        }
    }

    /** 从 diffReviewSummary JsonElement 解析 diffId（兼容 diffId/reviewId/resourceId 等字段名） */
    private fun extractDiffId(diffSummary: com.google.gson.JsonElement?): String? {
        if (diffSummary == null || !diffSummary.isJsonObject) return null
        val obj = diffSummary.asJsonObject
        val candidates = listOf("diffId", "reviewId", "resourceId", "id")
        for (key in candidates) {
            val v = obj.get(key)
            if (v != null && !v.isJsonNull) {
                val s = v.asString
                if (s.isNotBlank()) return s
            }
        }
        return null
    }

    /** 从 JsonElement 读取指定字符串字段（无则 null） */
    private fun extractStringField(json: com.google.gson.JsonElement?, key: String): String? {
        if (json == null || !json.isJsonObject) return null
        val v = json.asJsonObject.get(key)
        return if (v != null && !v.isJsonNull) v.asString else null
    }

    /** 是否为"无 Diff Review 批次"的 404：FINAL_DIFF_EMPTY 后查询 Diff Review 属正常业务，不报错（文档 §15.6.4） */
    private fun isDiffReviewNotFound(e: Throwable): Boolean =
        e is com.example.qgent.data.model.ApiException &&
            (e.code == "DIFF_REVIEW_NOT_FOUND" || e.code == "HTTP_404")

    /**
     * Diff Review 确认对话框（§12.3）：
     * - 内容区：批次摘要（仓库数/文件数/增删行）+ 首个 Diff 的文件内容（有 diffId 时）
     * - 按钮：确认 Diff / 拒绝 Diff 走 Task 级批次接口（confirm/reject，带 Idempotency-Key）；
     *   canRetry 时追加"重试交付"（交付失败后重试）
     * - 非待确认状态（canDecide=false）只读展示
     */
    private fun showDiffConfirmDialog(
        projectId: String,
        taskId: String,
        diffId: String?,
        taskTitle: String,
        taskStatus: String,
        canDecide: Boolean = true,
        canRetry: Boolean = false,
        deliveryMode: String? = null
    ) {
        val container = ScrollView(requireContext())
        val tv = TextView(requireContext()).apply {
            textSize = 13f
            setTextIsSelectable(true)
            setPadding(48, 40, 48, 40)
        }
        container.addView(tv, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        // 拉取批次摘要 + 首个 Diff 文件内容（DTO → UI DiffFile 再渲染）
        viewLifecycleOwner.lifecycleScope.launch {
            val sb = StringBuilder("任务状态：").append(taskStatus).append("\n\n")
            // MR_FIRST：系统按规则自动授权交付，不展示为"用户已确认"（文档 §15.2）
            if (deliveryMode == "MR_FIRST") sb.append("⚡ 自动交付（系统授权）\n\n")
            diffRepo.getTaskDiffReview(projectId, taskId)
                .onSuccess { batch ->
                    if (batch != null) {
                        sb.append("📦 Diff Review 批次：\n")
                        if (!batch.deliveryStatus.isNullOrBlank()) {
                            sb.append("交付状态：").append(batch.deliveryStatus).append("\n")
                        }
                        sb.append("仓库 ").append(batch.repositoryCount)
                            .append(" 个 · 文件 ").append(batch.filesChanged)
                            .append(" 个 · +").append(batch.additions)
                            .append(" -").append(batch.deletions).append("\n")
                        batch.diffs?.forEach { d ->
                            val stats = d.changeStats
                            sb.append("• ").append(d.repositoryName ?: d.repositoryId ?: d.id ?: "未知仓库")
                                .append("  +${stats?.additions ?: 0} -${stats?.deletions ?: 0}").append("\n")
                        }
                        sb.append("\n")
                    }
                }
                .onFailure { e ->
                    // 无代码变更（FINAL_DIFF_EMPTY）：任务 SUCCEEDED 但无 DiffReviewBatch，
                    // 查询返回 404 是正常业务结果，不得显示为系统错误/交付失败/重试入口（文档 §15.6.4/§20.3）
                    if (isDiffReviewNotFound(e)) {
                        sb.append(getString(R.string.task_no_code_change)).append("\n\n")
                    } else {
                        Log.w("DiffReview", "批次摘要加载失败: ${e.message}")
                    }
                }
            if (!diffId.isNullOrBlank()) {
                val files = diffRepo.getDiffFiles(projectId, diffId).getOrNull().orEmpty().map { it.toDiffFile() }
                files.forEach { file ->
                    sb.append("📄 ").append(file.fileName).append("  (+${file.additions} -${file.deletions})").append("\n")
                    file.lines.forEach { line ->
                        val marker = when (line.type) {
                            com.example.qgent.model.DiffLineType.ADD -> "+"
                            com.example.qgent.model.DiffLineType.DELETE -> "-"
                            else -> " "
                        }
                        sb.append(marker).append(" ").append(line.text).append("\n")
                    }
                    sb.append("\n")
                }
                if (files.isEmpty()) sb.append("（该 Diff 无文件内容）\n")
            }
            tv.text = if (sb.isBlank()) "暂无 Diff 内容" else sb.toString()
        }

        // 标题：MR_FIRST=自动交付（只读）/ 待确认 Diff / 普通只读
        val dialogTitle = when {
            deliveryMode == "MR_FIRST" -> getString(R.string.task_delivery_auto_title, taskTitle)
            canDecide -> "待确认 Diff · $taskTitle"
            else -> "Diff · $taskTitle"
        }
        val dialogBuilder = MaterialAlertDialogBuilder(requireContext())
            .setTitle(dialogTitle)
            .setView(container)
        if (canDecide) {
            dialogBuilder
                .setNegativeButton(R.string.reject_diff) { _, _ -> rejectDiffReview(projectId, taskId) }
                .setPositiveButton(R.string.confirm_diff) { _, _ -> confirmDiffReview(projectId, taskId) }
        } else {
            dialogBuilder.setPositiveButton(R.string.close, null)
        }
        if (canRetry) {
            dialogBuilder.setNeutralButton(R.string.retry_delivery) { _, _ -> retryDiffDelivery(projectId, taskId) }
        }
        dialogBuilder.show()
    }

    /** 确认整个最终 Diff 批次（POST .../tasks/{taskId}/diff-review/confirm，§12.3；Idempotency-Key 必填） */
    private fun confirmDiffReview(projectId: String, taskId: String) {
        viewLifecycleOwner.lifecycleScope.launch {
            diffRepo.confirmDiffReview(projectId, taskId, UUID.randomUUID().toString())
                .onSuccess {
                    Toast.makeText(requireContext(), "已确认 Diff，Agent 提交 MR 等待审核", Toast.LENGTH_LONG).show()
                }
                .onFailure { e ->
                    Toast.makeText(requireContext(), "确认失败：${e.message}", Toast.LENGTH_LONG).show()
                }
        }
    }

    /** 拒绝整个最终 Diff 批次（POST .../tasks/{taskId}/diff-review/reject，§12.3；Idempotency-Key 必填） */
    private fun rejectDiffReview(projectId: String, taskId: String) {
        // 拒绝可填原因
        val input = EditText(requireContext())
        input.hint = "拒绝原因（可选）"
        input.setPadding(48, 32, 48, 32)
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.reject_diff)
            .setView(input)
            .setPositiveButton(R.string.confirm) { _, _ ->
                val reason = input.text.toString().trim().ifEmpty { null }
                viewLifecycleOwner.lifecycleScope.launch {
                    diffRepo.rejectDiffReview(projectId, taskId, reason, UUID.randomUUID().toString())
                        .onSuccess {
                            Toast.makeText(requireContext(), "已拒绝 Diff，Agent 重新修改", Toast.LENGTH_LONG).show()
                        }
                        .onFailure { e ->
                            Toast.makeText(requireContext(), "拒绝失败：${e.message}", Toast.LENGTH_LONG).show()
                        }
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /** 重试逐仓库交付（POST .../tasks/{taskId}/diff-review/retry-delivery，§12.3；Idempotency-Key 必填） */
    private fun retryDiffDelivery(projectId: String, taskId: String) {
        viewLifecycleOwner.lifecycleScope.launch {
            diffRepo.retryDiffDelivery(projectId, taskId, UUID.randomUUID().toString())
                .onSuccess {
                    Toast.makeText(requireContext(), "已重试交付", Toast.LENGTH_LONG).show()
                }
                .onFailure { e ->
                    Toast.makeText(requireContext(), "重试交付失败：${e.message}", Toast.LENGTH_LONG).show()
                }
        }
    }

    private fun buildRows(list: List<ChatMessage>): List<ChatRow> {
        val result = mutableListOf<ChatRow>()
        var prev: Long? = null
        list.forEach { msg ->
            if (prev == null || msg.timestamp - prev!! > TIME_GAP_MS) {
                result.add(ChatRow.Time(formatTime(msg.timestamp)))
            }
            result.add(ChatRow.Message(msg))
            prev = msg.timestamp
        }
        return result
    }

    private fun formatTime(timestamp: Long): String =
        SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(timestamp))

    private fun scrollToBottom() {
        binding.rvMessages.post {
            binding.rvMessages.scrollToPosition(adapter.itemCount - 1)
        }
    }

    private fun loadInitialData() {
        val projectId = mainViewModel.currentProjectId()
        val groupId = arguments?.getString("groupId").orEmpty()

        if (projectId == null || groupId.isEmpty()) {
            setMessages(emptyList())
            return
        }

        viewLifecycleOwner.lifecycleScope.launch {
            // 先读本地缓存秒开，无缓存则等网络
            val cached = messageCache.load(groupId)
            if (cached.isNotEmpty()) setMessages(cached)

            // 并行拉成员表 + 消息（原串行改并发）
            val membersDeferred = async { chatRepo.getMembers(projectId, groupId) }
            val messagesDeferred = async { chatRepo.getMessages(projectId, groupId) }

            val membersResult = membersDeferred.await()
            membersResult.onSuccess { dtos ->
                Log.d("Mention", "getMembers success: ${dtos.map { "${it.id}:${it.resolvedName}:${it.memberType}" }}")
                // 保存原始群成员；Agent 合并由 rebuildMemberMaps 统一处理（agents 可能异步后加载）
                baseGroupMembers = dtos.map { it.toGroupMember() }
                rebuildMemberMaps()
            }.onFailure {
                Log.e("Mention", "getMembers FAILED: ${it::class.simpleName} ${it.message}")
            }

            val messagesResult = messagesDeferred.await()
            val myId = SessionStore.user()?.id
            messagesResult.onSuccess { dtos ->
                val list = dtos.map { it.toChatMessage(myId, memberNamesById) }
                // 合并时必须以「当前内存列表」为基准（而非开头读的 cached 快照）：
                // 若初始 getMessages 较慢，期间用户已发出消息并 append 到 messages，
                // 用 cached 会把这几天新消息连同网络结果一起覆盖掉，导致「发出后几秒消失」。
                val merged = mergeWithNetwork(list)
                setMessages(merged)
                messageCache.save(groupId, merged)
            }.onFailure {
                // 网络失败但已有缓存时保留缓存显示，不清空
                if (messages.isEmpty()) setMessages(emptyList())
            }
        }
    }

    /**
     * 重建成员映射：原始群成员 + 团队 Agent（Agent 是团队级 @ 渠道）。
     * 仅需求群合并 Agent；项目总群（PROJECT_MAIN）是纯人类聊天页面，不合并（产品约定）。
     *
     * Agent 身份以团队 Agent 名单（getAgents）为准，按 id 去重合并：
     * 群成员列表中同 id 的 Agent 条目丢弃（避免 @ 弹窗重复、mention 发重复 id）；
     * 名单里没有的 Agent 成员（如名单加载失败）保留兜底。
     * 这样后端改名后不再依赖「名字以 Agent 开头」的启发式判断 Agent 类型。
     */
    private fun rebuildMemberMaps() {
        val isMainGroup = mainViewModel.groups.value.orEmpty()
            .firstOrNull { it.id == arguments?.getString("groupId") }
            ?.type == GroupType.PROJECT_MAIN
        val teamAgents = if (isMainGroup) {
            emptyList()
        } else {
            mainViewModel.agents.value.orEmpty()
                .filter { it.status.name != "ARCHIVED" }
                .map { GroupMember(id = it.id, name = it.name, type = MemberType.AGENT) }
        }
        val agentIds = teamAgents.map { it.id }.toSet()
        // 群成员列表中与 Agent 名单同 id 的条目以名单为准；其余（真人 + 名单缺失的 Agent）保留
        val mergedMembers = baseGroupMembers.filter { it.id !in agentIds } + teamAgents
        groupMembers = mergedMembers
        memberNamesById = mergedMembers.associate { it.id to it.name }
        memberById = mergedMembers.associate { it.id to it }
    }

    /** 前台轮询新消息：后端暂无聊天 SSE，用定时 getMessages 兜底实现「别人发消息实时显示」 */
    private fun startPolling() {
        if (pollingJob?.isActive == true) return
        val projectId = mainViewModel.currentProjectId()
        val groupId = arguments?.getString("groupId").orEmpty()
        if (projectId == null || groupId.isEmpty()) return

        pollingJob = viewLifecycleOwner.lifecycleScope.launch {
            while (true) {
                delay(POLL_INTERVAL_MS)
                pollMessages(projectId, groupId)
            }
        }
    }

    private fun stopPolling() {
        pollingJob?.cancel()
        pollingJob = null
    }

    /** 单次轮询：拉消息并与本地列表按 id 去重合并，仅在有新消息时刷新并落缓存 */
    private suspend fun pollMessages(projectId: String, groupId: String) {
        val myId = SessionStore.user()?.id
        chatRepo.getMessages(projectId, groupId).onSuccess { dtos ->
            val list = dtos.map { it.toChatMessage(myId, memberNamesById) }
            // 轮询时保留本会话内刚发的本地兜底消息（keepLocal=true），
            // 避免用户刚发送失败的消息被下一次轮询立刻删掉；下次进页面时由 loadInitialData 清掉
            val merged = mergeWithNetwork(list, keepLocal = true)
            if (merged != messages) {
                setMessages(merged)
                messageCache.save(groupId, merged.filterNot { it.id.startsWith(LOCAL_ID_PREFIX) })
            }
        }
    }

    /**
     * 网络消息与当前内存列表合并，并剔除「幽灵消息」：
     * 发送失败时 appendLocalMessage 生成的本地兜底消息（isMine && sequence<=0）后端不存在，
     * 历史出错版本（如 @ 功能早期版本）把它们写进了 Room 缓存，导致：
     * 1) 只在自己这边显示、别人看不到；
     * 2) 永远无法被 distinctBy(id) 匹配清除，一直残留；
     * 3) 本地消息 sequence=0 → 排序恒排末尾，新消息反而显示在它上方。
     *
     * [keepLocal]=false（初次加载）：剔除所有未被后端确认的本地兜底消息（含历史残留）；
     * [keepLocal]=true（轮询）：仅剔除旧幽灵（无 local- 前缀的），本会话新发的 local- 消息保留显示。
     */
    private fun mergeWithNetwork(network: List<ChatMessage>, keepLocal: Boolean = false): List<ChatMessage> {
        val networkIds = network.map { it.id }.toSet()
        val kept = messages.filter { msg ->
            when {
                msg.id in networkIds -> true                                  // 后端已确认
                keepLocal && msg.id.startsWith(LOCAL_ID_PREFIX) -> true        // 本会话刚发的本地消息
                else -> !(msg.isMine && msg.sequence <= 0L)                    // 历史幽灵：自己发的且无 sequence
            }
        }
        return (kept + network).distinctBy { it.id }.sortedChronologically()
    }

    /** 按后端单调 sequence 排序（本地兜底消息无 sequence，恒排末尾）；timestamp 因时区不一致不可靠，仅作 sequence 相同时的次级排序 */
    private fun List<ChatMessage>.sortedChronologically(): List<ChatMessage> =
        sortedWith(
            compareBy<ChatMessage> { if (it.sequence > 0L) it.sequence else Long.MAX_VALUE }
                .thenBy { it.timestamp }
        )

    private fun setMessages(newMessages: List<ChatMessage>) {
        // 引用摘要统一补齐：resolveReplySummary 依赖当前 messages 查找被引用消息，
        // 因此先合并查找（旧列表 + 新列表），再整体替换
        val lookup = messages + newMessages
        val resolved = newMessages.map { msg ->
            if (msg.replyToId != null && msg.replyToSummary == null) {
                val target = lookup.firstOrNull { it.id == msg.replyToId }
                msg.copy(
                    replyToSummary = target?.let { "${it.senderName}：${it.displayContent()}" } ?: "引用消息"
                )
            } else {
                msg
            }
        }
        messages.clear()
        messages.addAll(resolved)
        rows.clear()
        rows.addAll(buildRows(messages))
        adapter.notifyDataSetChanged()
        scrollToBottom()
    }

    override fun onResume() {
        super.onResume()
        startPolling()
        startEventStream()
    }

    override fun onPause() {
        super.onPause()
        stopPolling()
        stopEventStream()
        // 退出详情页时把已看到的最新消息时间回传，避免回到列表页仍显示红点
        val groupId = arguments?.getString("groupId").orEmpty()
        val lastSeen = messages.maxOfOrNull { it.timestamp }
        if (groupId.isNotEmpty() && lastSeen != null) {
            mainViewModel.markGroupRead(groupId, lastSeen)
        }
    }

    /**
     * 项目级 SSE 事件流（文档 §12.1 + message.created 补充）：
     * 只对 message.created 且 groupId 匹配当前群的事件刷新消息；
     * 其他任务/Diff 事件（无 groupId）不触发消息拉取，避免事件风暴导致列表频繁重建。
     * 3s 轮询保留作为无事件时的兜底。
     */
    private fun startEventStream() {
        val projectId = mainViewModel.currentProjectId() ?: return
        val groupId = arguments?.getString("groupId").orEmpty()
        if (groupId.isEmpty()) return
        eventStream.startProject(projectId)
        if (eventStreamJob == null) {
            eventStreamJob = viewLifecycleOwner.lifecycleScope.launch {
                eventStream.events.collect { event ->
                    when (event.type) {
                        // 消息事件：当前群有新消息 → 立即拉取一次
                        SseEventType.MESSAGE_CREATED -> {
                            val targetGroup = parseGroupId(event.data)
                            if (targetGroup == groupId) {
                                pollMessages(projectId, groupId)
                            }
                        }
                        // Diff 相关事件：缓存 taskId → diffId 映射，供 TASK_STATUS 卡片点击确认用
                        SseEventType.DIFF_CREATED,
                        SseEventType.DIFF_REVIEW_CREATED,
                        SseEventType.TASK_AWAITING_DIFF_CONFIRMATION -> cacheTaskDiffId(event.data)
                        else -> Unit
                    }
                }
            }
        }
    }

    /** 缓存事件 payload 中的 taskId → diffId 映射（后端任务详情可能不返回 diffId） */
    private fun cacheTaskDiffId(data: String) {
        runCatching {
            val obj = org.json.JSONObject(data)
            val taskId = obj.optString("taskId").takeIf { it.isNotBlank() }
            val diffId = obj.optString("diffId").takeIf { it.isNotBlank() }
            if (taskId != null && diffId != null) taskDiffIdMap[taskId] = diffId
        }
    }

    /** 从事件 payload 中解析 groupId（无该字段返回 null） */
    private fun parseGroupId(data: String): String? =
        runCatching {
            org.json.JSONObject(data).optString("groupId").takeIf { it.isNotBlank() }
        }.getOrNull()

    private fun stopEventStream() {
        eventStreamJob?.cancel()
        eventStreamJob = null
        eventStream.stop()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        binding.etInput.removeTextChangedListener(mentionWatcher)
        _binding = null
    }

    companion object {
        private const val TIME_GAP_MS = 5 * 60 * 1000L
        private const val POLL_INTERVAL_MS = 3_000L
        private const val MAX_PREVIEW_CHARS = 100_000

        /** 本地兜底消息（发送失败）id 前缀：不落缓存、合并时剔除，防止幽灵消息残留 */
        private const val LOCAL_ID_PREFIX = "local-"

        private val TEXT_EXTENSIONS = setOf(
            "txt", "md", "json", "xml", "yaml", "yml", "csv", "log", "kt", "java", "py",
            "js", "ts", "html", "css", "sql", "sh", "gradle", "properties", "ini", "conf",
            "go", "rs", "c", "cpp", "h", "hpp", "rb", "php", "swift", "vue", "toml"
        )
    }
}
