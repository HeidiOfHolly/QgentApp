package com.example.qgent.ui.chat
import android.app.Dialog
import android.content.Intent
import android.net.Uri
import android.webkit.MimeTypeMap
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
import com.example.qgent.QgentApp
import com.example.qgent.R
import com.example.qgent.data.SessionStore
import com.example.qgent.data.api.RetrofitClient
import com.example.qgent.data.local.MessageCache
import com.example.qgent.data.model.CreateMemoryRequest
import com.example.qgent.data.model.MentionDto
import com.example.qgent.data.model.MessageContentDto
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
import com.example.qgent.model.MemberType
import com.example.qgent.model.MessageType
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
    private var baseMemberNamesById: Map<String, String> = emptyMap()
    private var baseMemberById: Map<String, GroupMember> = emptyMap()

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
            onMessageClick = { message -> onMessageRowClick(message) }
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
        val mentions = extractMentions(text)
        val replyToId = quoteTarget?.id
        Log.d("SendMsg", "send text=$text mentions=$mentions replyToId=$replyToId memberNamesById=$memberNamesById")
        binding.etInput.text.clear()
        clearQuote()

        val projectId = mainViewModel.currentProjectId()
        val groupId = arguments?.getString("groupId").orEmpty()

        if (projectId != null && groupId.isNotEmpty()) {
            viewLifecycleOwner.lifecycleScope.launch {
                // Idempotency-Key 后端强制要求，防重复提交；每次发送都是全新消息，生成新 UUID
                chatRepo.sendMessage(projectId, groupId, "TEXT", MessageContentDto(text = text), mentions = mentions, replyToId = replyToId, idempotencyKey = UUID.randomUUID().toString())
                    .onSuccess { dto ->
                        Log.d("SendMsg", "send success id=${dto.id} senderId=${dto.senderId} mentions=${dto.mentions} replyTo=${dto.replyToId}")
                        appendMessage(dto.toChatMessage(SessionStore.user()?.id, memberNamesById))
                    }
                    .onFailure { e ->
                        Log.e("SendMsg", "send FAILED: ${e::class.simpleName} message=${e.message}", e)
                        Toast.makeText(requireContext(), "发送失败，仅自己可见", Toast.LENGTH_SHORT).show()
                        appendLocalMessage(text)
                    }
            }
        } else {
            Log.d("SendMsg", "no projectId/groupId → local fallback. projectId=$projectId groupId=$groupId")
            appendLocalMessage(text)
        }
    }

    /**
     * 解析输入文本中的 @成员名，反查成员 id 组装结构化 mentions（后端据此实现 @ 通知）。
     * 按成员类型生成 mention：普通用户 → USER，Agent → AGENT（文档 §7）。
     */
    private fun extractMentions(text: String): List<MentionDto> {
        if (memberById.isEmpty()) return emptyList()
        val mentions = mutableListOf<MentionDto>()
        Regex("@([^@\\s]+)").findAll(text).forEach { match ->
            val name = match.groupValues[1]
            memberById.entries.firstOrNull { it.value.name == name }?.let {
                mentions.add(
                    MentionDto(
                        type = if (it.value.type == MemberType.AGENT) "AGENT" else "USER",
                        id = it.key
                    )
                )
            }
        }
        return mentions.distinct()
    }

    /** 上传附件 → 发送 IMAGE/FILE 消息 */
    private fun sendMediaMessage(type: String, uri: Uri) {
        val projectId = mainViewModel.currentProjectId()
        val groupId = arguments?.getString("groupId").orEmpty()
        if (projectId == null || groupId.isEmpty()) {
            Toast.makeText(requireContext(), R.string.todo_placeholder, Toast.LENGTH_SHORT).show()
            return
        }

        viewLifecycleOwner.lifecycleScope.launch {
            Toast.makeText(requireContext(), "正在上传…", Toast.LENGTH_SHORT).show()
            val meta = readFileMeta(uri)
            val bytes = readBytes(uri)
            if (bytes == null) {
                Toast.makeText(requireContext(), "读取文件失败", Toast.LENGTH_SHORT).show()
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
                            appendMessage(dto.toChatMessage(SessionStore.user()?.id, memberNamesById))
                        }
                        .onFailure { e ->
                            Toast.makeText(requireContext(), "发送失败：${e.message}", Toast.LENGTH_LONG).show()
                        }
                }
                .onFailure { e ->
                    Toast.makeText(requireContext(), "上传失败：${e.message}", Toast.LENGTH_LONG).show()
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

    /** 发送失败时的本地兜底消息：id 加 local- 前缀标记，
     *  仅在本次会话内展示，不写入缓存、不与网络消息混淆（修复幽灵 @ 消息残留） */
    private fun appendLocalMessage(text: String) {
        appendMessage(
            ChatMessage(
                LOCAL_ID_PREFIX + UUID.randomUUID(),
                "我",
                text,
                MessageType.TEXT,
                System.currentTimeMillis(),
                true
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

    /** 生成 Memory 草稿：选中消息拼接为标题+内容，调 POST /memories 提交审核 */
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
        // 标题取第一条消息摘要，内容拼接选中消息（发送者：内容）
        val title = selected.first().displayContent().take(30)
        val content = selected.joinToString("\n") { "${it.senderName}：${it.displayContent()}" }

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

    /** 点击图片：全屏预览放大后的原图，点击任意处关闭 */
    private fun showImagePreview(uri: String) {
        val dialog = Dialog(requireContext())
        val previewBinding = DialogImagePreviewBinding.inflate(layoutInflater)
        dialog.setContentView(previewBinding.root)
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.window?.setLayout(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT
        )
        val token = SessionStore.accessToken()
        val headers = LazyHeaders.Builder().apply {
            if (!token.isNullOrEmpty()) addHeader("Authorization", "Bearer $token")
        }.build()
        Glide.with(previewBinding.ivPreview)
            .load(GlideUrl(RetrofitClient.resolveMediaUrl(uri), headers))
            .into(previewBinding.ivPreview)
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

    private fun showAttachmentMenu() {
        val popup = PopupMenu(requireContext(), binding.btnPlus)
        popup.menu.add(getString(R.string.image))
        popup.menu.add(getString(R.string.file))
        popup.setOnMenuItemClickListener { item ->
            if (item.title == getString(R.string.image)) {
                pickImage.launch(
                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                )
            } else {
                pickFile.launch(arrayOf("*/*"))
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
        // 发送后立即落缓存，避免退出重进后新消息丢失；
        // 本地兜底消息（发送失败）不落缓存，防止幽灵消息持久化残留
        val groupId = arguments?.getString("groupId").orEmpty()
        if (groupId.isNotEmpty()) {
            viewLifecycleOwner.lifecycleScope.launch {
                messageCache.save(groupId, messages.filterNot { it.id.startsWith(LOCAL_ID_PREFIX) })
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
                // 保存原始群成员；Agent 合并由 rebuildMemberMaps 统一处理（agents 可能异步后加载）
                baseGroupMembers = dtos.map { it.toGroupMember() }
                baseMemberNamesById = dtos.associate { it.id to it.resolvedName }
                baseMemberById = dtos.associate { it.id to it.toGroupMember() }
                rebuildMemberMaps()
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
     * 在群成员加载完成和 agents 变化时调用，保证 @ 弹窗始终有 Agent。
     */
    private fun rebuildMemberMaps() {
        val teamAgents = mainViewModel.agents.value.orEmpty()
            .filter { it.status.name != "ARCHIVED" }
            .map { GroupMember(id = it.id, name = it.name, type = MemberType.AGENT) }
        groupMembers = baseGroupMembers + teamAgents
        memberNamesById = baseMemberNamesById + teamAgents.associate { it.id to it.name }
        memberById = baseMemberById + teamAgents.associate { it.id to it }
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
                    // 只处理消息事件：当前群有新消息 → 立即拉取一次
                    if (event.type == SseEventType.MESSAGE_CREATED) {
                        val targetGroup = parseGroupId(event.data)
                        if (targetGroup == groupId) {
                            pollMessages(projectId, groupId)
                        }
                    }
                }
            }
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
