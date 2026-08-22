package com.example.qgent.ui.chat
import android.app.Dialog
import android.content.Intent
import android.graphics.drawable.Drawable
import android.net.Uri
import android.webkit.MimeTypeMap
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.os.Bundle
import android.provider.OpenableColumns
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
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
import androidx.recyclerview.widget.RecyclerView
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
import com.example.qgent.data.model.ApiException
import com.example.qgent.data.model.AttachmentPreviewDto
import com.example.qgent.data.model.MentionDto
import com.example.qgent.data.model.MessageContentDto
import com.example.qgent.data.model.TaskCreateRequest
import com.example.qgent.data.model.TaskDetailDto
import com.example.qgent.data.model.TaskTriggerRequest
import com.example.qgent.data.model.toChatMessage
import com.example.qgent.data.model.toDiffFile
import com.example.qgent.data.model.toGroupMember
import com.example.qgent.data.model.parseRfc3339
import com.example.qgent.data.repository.AttachmentUploader
import com.example.qgent.data.repository.ChatRepository
import com.example.qgent.data.sse.ProjectEventStream
import com.example.qgent.ui.common.CreateTaskDialog
import com.example.qgent.ui.common.OpenMrGuidance
import com.example.qgent.data.sse.SseEventType
import com.example.qgent.databinding.BottomSheetMentionMemberBinding
import com.example.qgent.databinding.DialogImagePreviewBinding
import com.example.qgent.databinding.FragmentChatDetailBinding
import com.example.qgent.model.ChatMessage
import com.example.qgent.model.DiffFile
import com.example.qgent.model.DiffLine
import com.example.qgent.model.DiffLineType
import com.example.qgent.model.GroupMember
import com.example.qgent.model.GroupType
import com.example.qgent.model.MemberType
import com.example.qgent.model.MessageType
import com.example.qgent.model.SendState
import com.example.qgent.ui.diffreview.DiffReviewRules
import com.example.qgent.viewmodel.MainViewModel
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
    private var wsJob: Job? = null
    private var syncingIncrementalMessages = false

    /** 附件预览信息内存缓存（attachmentId → (过期毫秒, dto)）；含 token 的 previewUrl 不落盘（契约 §2.2） */
    private val previewCache = mutableMapOf<String, Pair<Long, AttachmentPreviewDto>>()

    /** 消息分页（上滑加载更早消息）：下一页游标 / 是否还有更多 / 是否正在加载 */
    private var nextCursor: String? = null
    private var hasMoreMessages = true
    private var loadingOlder = false

    /** WebSocket 实时通道（单连接用户级聚合，聊天实时主通道；规避 SSE 被 CDN 掐断） */
    private val realtimeClient: com.example.qgent.data.ws.RealtimeClient
        get() = (requireActivity().application as QgentApp).container.realtimeClient

    /**
     * 发送串行锁：同一时刻只允许一个消息发送请求在途（文本/图片/文件/重发共用）。
     * 「同时发多条消息」时后端对同群并发 POST 存在竞态（sequence 分配/幂等锁），
     * 会随机拒绝其中一条；串行化后排队逐个发送，第二条自动等待。
     */
    private val sendMutex = Mutex()

    /** 当前引用的目标消息（非空时输入框上方显示引用条，发送时带 replyToId） */
    private var quoteTarget: ChatMessage? = null

    /** taskId → diffId 缓存（SSE diff 事件填充；后端任务详情可能不返回 diffId） */
    private val taskDiffIdMap = mutableMapOf<String, String>()

    /** diffId → taskId 反向映射（DIFF 卡点击跳转 Diff 审核用；同源事件填充） */
    private val diffIdToTaskIdMap = mutableMapOf<String, String>()

    /** Diff 审核或完整 Diff 正在解析时只保留一次请求，避免连续点击叠加多个弹窗。 */
    private var isOpeningDiffDialog = false

    /** Diff 卡片预览仅保留在当前页面内；避免列表重绑时为同一张卡重复请求文件内容。 */
    private val diffPreviewCache = mutableMapOf<String, List<DiffFile>>()
    private val diffPreviewCallbacks = mutableMapOf<String, MutableList<(List<DiffFile>) -> Unit>>()

    /** delivery.started 事件去重（taskId:operationId）：重复/乱序/晚到事件不重复刷新 */
    private val deliveryStartedSeen = mutableSetOf<String>()

    /** 多选模式：长按消息选「多选」进入，点击消息切换选中，用于生成 Memory 草稿 */
    private var multiSelectMode = false
    private val selectedMessageIds = mutableSetOf<String>()

    /** §7.1 通知直达：目标消息 id（跳群后滚动高亮到该消息；null=非直达进入） */
    private val targetMessageId: String? get() = arguments?.getString(ARG_TARGET_MESSAGE_ID)

    /** §7.1 通知直达：来源是否为 @ 提及（resourceId 缺失时兜底滚到最上面一条被 @ 的消息） */
    private val fromMention: Boolean get() = arguments?.getBoolean(ARG_FROM_MENTION) == true

    /** 当前群 id（多次使用，抽成 getter） */
    private val groupId: String get() = arguments?.getString("groupId").orEmpty()

    /** 已忽略的「有人@你」消息 id（对齐 web ChatPanel：点击后按钮消失，新 @ 消息再来重新出现） */
    private var dismissedMentionId: String? = null

    /** 当前提示条指向的未读 @ 消息 id（点击跳转目标） */
    private var currentMentionMessageId: String? = null

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
        val loadingSize = (48 * resources.displayMetrics.density).toInt()
        Glide.with(this)
            .asGif()
            .load(R.drawable.blue_robot_loading_animation)
            .override(loadingSize, loadingSize)
            .into(_binding!!.ivMessageLoading)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Edge-to-edge 下由 IME inset 驱动整页平移，避免只把输入栏抬起而标题固定。
        requireActivity().window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING)

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
            binding.inputBar.setPadding(0, 0, 0, bars.bottom)
            binding.root.translationY = -ime.bottom.toFloat()
            insets
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
            onTaskStatusClick = { message -> onTaskStatusCardClick(message) },
            onDiffCardClick = { message -> onDiffCardClick(message) },
            onViewFullDiff = { message, selectedFile -> onViewFullDiffClick(message, selectedFile) }
        )
        binding.rvMessages.layoutManager = LinearLayoutManager(requireContext())
        binding.rvMessages.adapter = adapter

        // 上滑接近顶部 → 加载更早消息（游标分页）
        binding.rvMessages.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                val lm = recyclerView.layoutManager as? LinearLayoutManager ?: return
                if (hasMoreMessages && !loadingOlder && lm.findFirstVisibleItemPosition() <= 3) {
                    loadOlderMessages()
                }
            }
        })

        // 取消引用：关闭引用条，发送不再带 replyToId
        binding.btnCancelQuote.setOnClickListener { clearQuote() }

        // 未读「有人@你」提示条：点击滚动到被 @ 的消息并高亮，点击后消失（对齐 web ChatPanel）
        binding.btnMentionBar.setOnClickListener { onMentionBarClick() }

        // 多选操作条：取消 / 生成 Memory 草稿
        binding.btnCancelMultiSelect.setOnClickListener { exitMultiSelect() }
        binding.btnCreateMemoryDraft.setOnClickListener { createMemoryDraftFromSelection() }

        observeSearchTargetMessage()
        loadInitialData()

        // 团队 Agent 异步加载完成后重建成员映射（@ 弹窗始终包含 Agent）
        mainViewModel.agents.observe(viewLifecycleOwner) { _ ->
            rebuildMemberMaps()
        }
    }

    private fun sendTextMessage() {
        val text = binding.etInput.text.toString().trim()
        if (text.isEmpty()) return
        // v2.0.6 §1：mentions 随消息体提交（@用户通知 / @Agent 自动触发任务）
        val mentions = extractMentions(text)
        val replyToId = quoteTarget?.id
        val replyToSummary = quoteTarget?.let { "${it.senderName}：${it.displayContent()}" }
        // B2/C2：引用 DIFF 卡发送 = 增量修改续作（服务端复用源 Workspace）
        val quotingDiff = quoteTarget?.type == MessageType.DIFF
        // 引用消息（带 replyToId）用 type=QUOTE（v2.0.6 §1.4 结构），否则 TEXT
        val isQuote = replyToId != null
        val sendType = if (isQuote) "QUOTE" else "TEXT"
        // v2.0.6 §1.4：QUOTE content 只含 quoted* 三字段，回复正文走顶层 replyText
        val content = if (isQuote) {
            MessageContentDto(
                text = null,
                quotedText = quoteTarget?.diffTitle?.takeIf { it.isNotBlank() } ?: quoteTarget?.displayContent(),
                quotedMessageId = replyToId,
                quotedSenderName = quoteTarget?.senderName
            )
        } else {
            MessageContentDto(text = text)
        }
        Log.d("SendMsg", "send text=$text type=$sendType mentions=$mentions replyToId=$replyToId quotingDiff=$quotingDiff memberNamesById=$memberNamesById")
        binding.etInput.text.clear()
        clearQuote()

        val projectId = mainViewModel.currentProjectId()
        val groupId = arguments?.getString("groupId").orEmpty()

        if (projectId != null && groupId.isNotEmpty()) {
            // 乐观插入本地消息（发送中：旁边显示小加载标），成功后替换为服务端消息，失败标记红色感叹号。
            // clientMessageId：本地固定生成，重发/断线重试复用同一值 → 后端幂等去重（§7）
            val local = ChatMessage(
                id = LOCAL_ID_PREFIX + UUID.randomUUID(),
                senderName = "我",
                content = text,
                type = if (isQuote) MessageType.QUOTE else MessageType.TEXT,
                timestamp = System.currentTimeMillis(),
                isMine = true,
                sequence = 0,
                replyToId = replyToId,
                replyToSummary = replyToSummary,
                sendState = SendState.SENDING,
                clientMessageId = UUID.randomUUID().toString()
            )
            appendMessage(local)
            viewLifecycleOwner.lifecycleScope.launch {
                // 串行发送：排队等待前一条完成，避免同群并发 POST 被后端拒绝（同时发多条必现）
                sendMutex.withLock {
                    // Idempotency-Key 后端强制要求，防重复提交；每次发送都是全新消息，生成新 UUID
                    chatRepo.sendMessage(
                        projectId, groupId, sendType, content,
                        clientMessageId = local.clientMessageId,
                        mentions = mentions,
                        replyText = if (isQuote) text else null,
                        replyToId = replyToId,
                        idempotencyKey = UUID.randomUUID().toString()
                    )
                        .onSuccess { dto ->
                            Log.d("SendMsg", "send success id=${dto.id} senderId=${dto.senderId} mentions=${dto.mentions} replyTo=${dto.replyToId}")
                            replaceLocalMessage(local.id, dto.toChatMessage(SessionStore.user()?.id, memberNamesById))
                            // 触发任务弹窗：仅引用 DIFF 卡续作需显式触发（服务端按 replyToId 指向 DIFF 消息
                            // 判定续作、复用源 Workspace）；普通 @Agent 消息由服务端自动创建任务，不再额外触发
                            if (quotingDiff) {
                                showCreateTaskDialog(
                                    prefillTitle = text.take(30),
                                    prefillRequirement = text,
                                    messageId = dto.id,
                                    quotingDiff = true
                                )
                            }
                        }
                        .onFailure { e ->
                            Log.e("SendMsg", "send FAILED: ${e::class.simpleName} message=${e.message}", e)
                            markSendFailed(local.id, e.message)
                        }
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
        Log.v("Mention", "extract from '$text', memberById=${memberById.map { "${it.value.name}:${it.value.type}" }}")
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
        // 乐观占位消息：图片/文件气泡先展示本地内容（content=本地 uri），发送成功后替换。
        // clientMessageId：本地固定生成，重发/断线重试复用同一值 → 后端幂等去重（§7）
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
            sendState = SendState.SENDING,
            clientMessageId = UUID.randomUUID().toString()
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
                .onSuccess { uploaded ->
                    // 契约 v0.1 §6.2：IMAGE/FILE content 必填 attachmentId（多模态输入依赖），url 兼容存量
                    val content = if (type == "IMAGE") {
                        MessageContentDto(text = null, url = uploaded.contentUrl, attachmentId = uploaded.attachmentId)
                    } else {
                        MessageContentDto(
                            text = null, url = uploaded.contentUrl,
                            name = meta.fileName, size = size, mimeType = meta.mimeType,
                            attachmentId = uploaded.attachmentId
                        )
                    }
                    // 串行发送：与文本消息共用发送锁，避免同群并发 POST 被后端拒绝
                    sendMutex.withLock {
                        chatRepo.sendMessage(
                            projectId, groupId, type, content,
                            clientMessageId = local.clientMessageId,
                            replyToId = quoteTarget?.id,
                            idempotencyKey = UUID.randomUUID().toString()
                        )
                            .onSuccess { dto ->
                                clearQuote()
                                replaceLocalMessage(local.id, dto.toChatMessage(SessionStore.user()?.id, memberNamesById))
                            }
                            .onFailure { e ->
                                Log.e("SendMsg", "media send FAILED: ${e.message}", e)
                                markSendFailed(local.id, e.message)
                            }
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
                id = LOCAL_ID_PREFIX + UUID.randomUUID(),
                senderName = "我",
                content = text,
                type = MessageType.TEXT,
                timestamp = System.currentTimeMillis(),
                isMine = true,
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

    /**
     * 生成 Memory 草稿（v2.0.6 §9）：按群 AI 总结——服务端读取该群最近消息交由 AI 生成草稿，
     * 客户端只传 groupId + instruction，不再勾选消息拼接。草稿始终 DRAFT，需人工审核。
     */
    private fun createMemoryDraftFromSelection() {
        val projectId = mainViewModel.currentProjectId()
        if (projectId == null) {
            Toast.makeText(requireContext(), R.string.add_member_missing_project, Toast.LENGTH_SHORT).show()
            return
        }
        val groupId = arguments?.getString("groupId").orEmpty()
        if (groupId.isEmpty()) return
        // 弹窗：仅输入沉淀说明（instruction，可选），AI 按群自动检索最近 50 条消息总结
        val dialogBinding = com.example.qgent.databinding.DialogMemoryInstructionBinding.inflate(layoutInflater)
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.memory_draft_title)
            .setView(dialogBinding.root)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.confirm) { _, _ ->
                val instruction = dialogBinding.etInstruction.text.toString().trim().ifEmpty { null }
                submitAiMemoryDraft(projectId, groupId, instruction)
            }
            .show()
    }

    private fun submitAiMemoryDraft(projectId: String, groupId: String, instruction: String?) {
        viewLifecycleOwner.lifecycleScope.launch {
            // AI 总结生成草稿（DRAFT，始终需人工审核）
            memoryRepo().createAiDraft(
                projectId,
                com.example.qgent.data.model.AiMemoryDraftRequest(groupId = groupId, instruction = instruction),
                UUID.randomUUID().toString()
            ).onSuccess {
                Toast.makeText(requireContext(), R.string.memory_draft_created, Toast.LENGTH_SHORT).show()
                exitMultiSelect()
            }.onFailure { e ->
                // 空群/群不属于项目/AI 生成失败：给出具体提示（422/500 业务码）
                val code = (e as? com.example.qgent.data.model.ApiException)?.code
                val msg = when (code) {
                    "GROUP_NO_MESSAGES" -> "该需求群暂无消息，无需沉淀"
                    "GROUP_NOT_IN_PROJECT" -> "群不属于该项目"
                    "AI_DRAFT_FAILED" -> "AI 总结失败，请稍后重试"
                    else -> e.message ?: getString(R.string.memory_draft_failed)
                }
                Toast.makeText(requireContext(), msg, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun memoryRepo(): com.example.qgent.data.repository.MemoryRepository =
        (requireActivity().application as QgentApp).container.memoryRepository

    /** 设置引用目标：显示引用条，发送时带 replyToId；引用 DIFF 卡时提示将发起增量修改（B2） */
    private fun setQuote(message: ChatMessage) {
        quoteTarget = message
        val base = getString(R.string.quote_prefix, message.senderName) + "：" + message.displayContent()
        binding.tvQuoteBar.text = if (message.type == MessageType.DIFF) {
            "$base\n引用 Diff 卡将发起增量修改（复用源工作区）"
        } else {
            base
        }
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
        val loader = when {
            // 本地 content:// URI（发送中的乐观占位图）直接加载，无需鉴权头
            uri.startsWith("content://") || uri.startsWith("file://") ->
                Glide.with(previewBinding.ivPreview).load(uri)
            // 签名预览 URL（契约 v0.1：/preview + 短期 token 在 query）无头直连
            uri.contains("/preview") || uri.contains("token=") ->
                Glide.with(previewBinding.ivPreview).load(RetrofitClient.resolveMediaUrl(uri))
            else -> {
                val token = SessionStore.accessToken()
                val headers = LazyHeaders.Builder().apply {
                    if (!token.isNullOrEmpty()) addHeader("Authorization", "Bearer $token")
                }.build()
                Glide.with(previewBinding.ivPreview)
                    .load(GlideUrl(RetrofitClient.resolveMediaUrl(uri), headers))
            }
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
                android.util.Log.e("ChatImage", "preview load FAILED: ${RetrofitClient.resolveMediaUrl(uri)}")
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
        val projectId = mainViewModel.currentProjectId()
        if (projectId == null) {
            Toast.makeText(requireContext(), R.string.todo_placeholder, Toast.LENGTH_SHORT).show()
            return
        }
        viewLifecycleOwner.lifecycleScope.launch {
            Toast.makeText(requireContext(), "正在打开…", Toast.LENGTH_SHORT).show()
            // 契约 v0.1 §8：按 previewType 分流——IMAGE 全屏 / PDF 系统打开 / TEXT·CODE 内置预览 / UNSUPPORTED 下载
            val preview = resolvePreviewInfo(projectId, message)
            when (preview?.previewType) {
                "IMAGE" -> preview.previewUrl?.let { showImagePreview(it) } ?: fallbackOpenFile(message)
                "PDF" -> preview.previewUrl?.let { openUrlWithSystemApp(it) } ?: fallbackOpenFile(message)
                "TEXT", "CODE" -> openTextPreview(message, preview.previewUrl)
                else -> fallbackOpenFile(message)
            }
        }
    }

    /**
     * 附件预览信息：优先用消息回填的 previewUrl（后端 §7 回填），否则按 attachmentId 调
     * preview-url（内存缓存，按 expiresAt/固定 TTL 过期）；全失败返回 null（调用方回退下载）。
     * 含 token 的 previewUrl 只在内存停留，不落 Room/磁盘（契约 §2.2）。
     */
    private suspend fun resolvePreviewInfo(projectId: String, message: ChatMessage): AttachmentPreviewDto? {
        if (!message.previewUrl.isNullOrBlank()) {
            return AttachmentPreviewDto(
                attachmentId = message.attachmentId.orEmpty(),
                fileName = message.fileName,
                sizeBytes = message.fileSize,
                previewable = message.previewable ?: true,
                previewType = message.previewType,
                previewUrl = message.previewUrl,
                downloadUrl = message.downloadUrl
            )
        }
        val attachmentId = message.attachmentId ?: return null
        previewCache[attachmentId]?.let { (expireAt, dto) ->
            if (System.currentTimeMillis() < expireAt) return dto
            previewCache.remove(attachmentId)
        }
        val dto = chatRepo.getAttachmentPreview(projectId, attachmentId).getOrNull() ?: return null
        val expireAt = dto.expiresAt?.let { parseRfc3339(it) }
            ?.takeIf { it > System.currentTimeMillis() }
            ?: (System.currentTimeMillis() + PREVIEW_CACHE_TTL_MS)
        previewCache[attachmentId] = expireAt to dto
        return dto
    }

    /** TEXT/CODE 预览：previewUrl?raw=1 下载 UTF-8 文本 → 内置预览（无 previewUrl 回退 content.url） */
    private suspend fun openTextPreview(message: ChatMessage, previewUrl: String?) {
        val fileName = message.fileName ?: "file"
        val source = previewUrl?.takeIf { it.isNotBlank() }
            ?.let { if (it.contains("?")) "$it&raw=1" else "$it?raw=1" }
            ?: message.content
        val file = downloadFile(source, fileName, requireContext().cacheDir)
        if (file == null) {
            Toast.makeText(requireContext(), "下载失败", Toast.LENGTH_SHORT).show()
            return
        }
        showTextPreview(fileName, readTextContent(file))
    }

    /** PDF/UNSUPPORTED 回退下载后调系统应用打开（老逻辑） */
    private fun fallbackOpenFile(message: ChatMessage) {
        val url = message.content
        if (url.isEmpty()) {
            Toast.makeText(requireContext(), R.string.todo_placeholder, Toast.LENGTH_SHORT).show()
            return
        }
        val fileName = message.fileName ?: "file"
        val mimeType = inferMimeType(fileName)
        viewLifecycleOwner.lifecycleScope.launch {
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

    /** 用系统浏览器/应用打开内联预览 URL（PDF：token 在 query，无需下载；不记录含 token 的 URL 日志） */
    private fun openUrlWithSystemApp(url: String) {
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(RetrofitClient.resolveMediaUrl(url)))
            if (intent.resolveActivity(requireContext().packageManager) == null) {
                Toast.makeText(requireContext(), "未找到可打开此文件的应用", Toast.LENGTH_SHORT).show()
                return
            }
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(requireContext(), "打开失败：${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    /** 下载附件到 cacheDir/downloads，返回本地文件；失败返回 null */
    private suspend fun downloadFile(url: String, fileName: String, cacheDir: File): File? = withContext(Dispatchers.IO) {
        val resolved = RetrofitClient.resolveMediaUrl(url)
        val result = runCatching {
            val target = File(File(cacheDir, "downloads"), fileName)
            target.parentFile?.mkdirs()
            val request = Request.Builder().url(resolved).build()
            RetrofitClient.httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw IllegalStateException("HTTP ${response.code}")
                val body = response.body ?: throw IllegalStateException("empty body")
                target.outputStream().use { out -> body.byteStream().copyTo(out) }
            }
            target
        }
        if (result.isFailure) {
            android.util.Log.e("ChatFile", "download FAILED: $resolved, ${result.exceptionOrNull()?.message}")
        }
        result.getOrNull()
    }

    private suspend fun readTextContent(file: File): String = withContext(Dispatchers.IO) {
        val raw = runCatching { file.readText(Charsets.UTF_8) }
            .getOrElse { runCatching { file.readText(Charsets.ISO_8859_1) }.getOrDefault("") }
        if (raw.length > MAX_PREVIEW_CHARS) raw.take(MAX_PREVIEW_CHARS) + "\n…（内容过长已截断）" else raw
    }

    /** 文本文件内置预览：ScrollView + 可选中 TextView */
    private fun showTextPreview(fileName: String, content: String) {
        val dialogBinding = com.example.qgent.databinding.DialogTextContentBinding.inflate(layoutInflater)
        dialogBinding.tvContent.text = content
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(fileName)
            .setView(dialogBinding.root)
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
        messageId: String? = null,
        /** 引用 DIFF 卡续作：复用源工作区，不选仓库、不要求 requirement（C2/B3） */
        quotingDiff: Boolean = false
    ) {
        val projectId = mainViewModel.currentProjectId() ?: return
        val groupId = arguments?.getString("groupId").orEmpty()
        if (groupId.isEmpty()) return

        CreateTaskDialog(
            context = requireContext(),
            projectId = projectId,
            taskRepo = taskRepo(),
            githubRepo = githubRepo(),
            scope = viewLifecycleOwner.lifecycleScope,
            // 群聊固定当前群，不显示分支群选择器
            candidateGroups = emptyList(),
            initialGroupId = groupId,
            showGroupSelector = false,
            prefillTitle = prefillTitle,
            prefillRequirement = prefillRequirement,
            quotingDiff = quotingDiff,
            onSubmit = { pid, gid, title, requirement, repoIds, baseRef ->
                if (messageId != null) {
                    triggerTaskFromMessage(pid, gid, messageId, title, requirement, repoIds, baseRef)
                } else {
                    createTask(pid, gid, title, requirement, repoIds, baseRef)
                }
            }
        ).show()
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
                // §27.3：分支存在未合并 MR 时引导查看 MR（而非普通失败 toast）
                if (e is com.example.qgent.data.model.ApiException && DiffReviewRules.isOpenMrBlocked(e.code)) {
                    OpenMrGuidance.show(requireContext(), projectId, e)
                    return@onFailure
                }
                val rid = if (e is com.example.qgent.data.model.ApiException && e.code.startsWith("HTTP_500")) {
                    e.requestId?.let { "\nrequestId: $it" }.orEmpty()
                } else {
                    ""
                }
                Toast.makeText(requireContext(), "${getString(R.string.start_task_failed)}：${e.message}$rid", Toast.LENGTH_LONG).show()
            }
        }
    }

    /** 契约 §7：从已发送的群消息显式触发 Task（POST .../messages/{messageId}/trigger-task）。
     *  引用 DIFF 卡续作时 repositoryIds/requirement 传空 → 请求体不携带（服务端复用源 Workspace，
     *  传 repositoryIds 会 409 WORKSPACE_CONTINUATION_REPOSITORIES_FORBIDDEN，C2）。 */
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
                    requirement = requirement.ifEmpty { null },
                    repositoryIds = repoIds.ifEmpty { null },
                    baseRef = baseRef
                ),
                UUID.randomUUID().toString()
            ).onSuccess {
                Toast.makeText(requireContext(), R.string.start_task_success, Toast.LENGTH_LONG).show()
            }.onFailure { e ->
                // C3：续作引用异常（QUOTED_DIFF_INVALID / QUOTED_DIFF_NOT_ACCESSIBLE 等）toast 展示 message，不静默重试
                // §27.3：分支存在未合并 MR 时引导查看 MR（而非普通失败 toast）
                if (e is com.example.qgent.data.model.ApiException && DiffReviewRules.isOpenMrBlocked(e.code)) {
                    OpenMrGuidance.show(requireContext(), projectId, e)
                    return@onFailure
                }
                // §46：项目无可用的 ACTIVE 仓库，后端不自动建任务
                if (e is com.example.qgent.data.model.ApiException && e.code == "PROJECT_NO_ACTIVE_REPOSITORIES") {
                    Toast.makeText(requireContext(), R.string.start_task_no_active_repos, Toast.LENGTH_LONG).show()
                    return@onFailure
                }
                // 引用 DIFF 续作错误（QUOTED_DIFF_* 等 422）：直接展示服务端 error.message，不加通用前缀
                if (e is com.example.qgent.data.model.ApiException && e.code.startsWith("QUOTED_DIFF")) {
                    Toast.makeText(requireContext(), e.message, Toast.LENGTH_LONG).show()
                    return@onFailure
                }
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
        // 仅需求群可发起任务（契约 §2865：Task 必须从 ACTIVE REQUIREMENT 群创建；
        // 项目总群 PROJECT_MAIN 发起后端必返回 404 REQUIREMENT_GROUP_NOT_FOUND）
        val isMainGroup = mainViewModel.groups.value.orEmpty()
            .firstOrNull { it.id == arguments?.getString("groupId") }
            ?.type == GroupType.PROJECT_MAIN
        if (!isMainGroup) {
            popup.menu.add(getString(R.string.start_task))
        }
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
        val prevTime = messages.getOrNull(messages.size - 2)?.timestamp
        if (prevTime == null || resolved.timestamp - prevTime > TIME_GAP_MS) {
            rows.add(ChatRow.Time(formatTime(resolved.timestamp)))
        }
        rows.add(ChatRow.Message(resolved))
        adapter.submitList(rows)
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
        adapter.submitList(rows)
        scrollToBottom()
    }

    /** 发送成功后用服务端确认消息替换本地乐观消息（并落缓存）。
     *  轮询可能在发送成功前已把服务端消息插入列表（v23 网络优先合并），
     *  先清掉同 id 旧条目再替换，避免同一消息短暂显示两次。 */
    private fun replaceLocalMessage(localId: String, network: ChatMessage) {
        // 防御：后端回显 QUOTE 若缺 replyText（气泡正文空），保留用户刚输入的回复正文，避免显示成「引用：xxx」
        val local = messages.firstOrNull { it.id == localId }
        var resolved = if (local != null && network.type == MessageType.QUOTE && network.content.isBlank() && local.content.isNotBlank()) {
            network.copy(content = local.content)
        } else {
            network
        }
        if (local?.isMine == true && !resolved.isMine) {
            Log.w("SendMsg", "Preserving local sender direction for confirmed message ${network.id}")
            resolved = resolved.copy(isMine = true)
        }
        messages.removeAll { it.id == network.id }
        val idx = messages.indexOfFirst { it.id == localId }
        if (idx >= 0) {
            messages[idx] = resolved
            refreshRows()
            saveCache()
        } else {
            appendMessage(resolved)
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
            MessageType.TEXT, MessageType.QUOTE -> resendText(message, projectId, groupId)
            MessageType.IMAGE, MessageType.FILE -> resendMedia(message, projectId, groupId)
            else -> removeLocalMessage(message.id) // 其他类型不支持重发，直接移除
        }
    }

    private fun resendText(message: ChatMessage, projectId: String, groupId: String) {
        viewLifecycleOwner.lifecycleScope.launch {
            // v2.0.6 §1：mentions 随消息体提交
            val mentions = extractMentions(message.content)
            // 引用消息重发保持 type=QUOTE，并从 replyToSummary（"发送者：被引用文本"）还原引用信息
            val sendType = if (message.replyToId != null) "QUOTE" else "TEXT"
            val content = if (message.replyToId != null) {
                MessageContentDto(
                    text = null,
                    quotedText = message.replyToSummary?.substringAfter("："),
                    quotedMessageId = message.replyToId,
                    quotedSenderName = message.replyToSummary?.substringBefore("：")?.takeIf { it.isNotBlank() }
                )
            } else {
                MessageContentDto(text = message.content)
            }
            sendMutex.withLock {
                chatRepo.sendMessage(
                    projectId, groupId, sendType, content,
                    // 重发复用原 clientMessageId → 后端幂等返回原消息，不产生重复消息
                    clientMessageId = message.clientMessageId,
                    mentions = mentions,
                    replyText = if (message.replyToId != null) message.content else null,
                    replyToId = message.replyToId,
                    idempotencyKey = UUID.randomUUID().toString()
                )
                    .onSuccess { dto ->
                        replaceLocalMessage(message.id, dto.toChatMessage(SessionStore.user()?.id, memberNamesById))
                        // 触发任务弹窗：仅引用 DIFF 卡续作需显式触发（普通 @Agent 由服务端自动创建；判定同 sendTextMessage）
                        val quotingDiff = messages.firstOrNull { it.id == message.replyToId }?.type == MessageType.DIFF
                        if (quotingDiff) {
                            showCreateTaskDialog(
                                prefillTitle = message.content.take(30),
                                prefillRequirement = message.content,
                                messageId = dto.id,
                                quotingDiff = true
                            )
                        }
                    }
                    .onFailure { e ->
                        Log.e("SendMsg", "resend text FAILED: ${e.message}", e)
                        markSendFailed(message.id, e.message)
                    }
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
                .onSuccess { uploaded ->
                    // 契约 v0.1 §6.2：重发同样带 attachmentId
                    val content = if (message.type == MessageType.IMAGE) {
                        MessageContentDto(text = null, url = uploaded.contentUrl, attachmentId = uploaded.attachmentId)
                    } else {
                        MessageContentDto(
                            text = null, url = uploaded.contentUrl,
                            name = meta.fileName, size = size, mimeType = meta.mimeType,
                            attachmentId = uploaded.attachmentId
                        )
                    }
                    sendMutex.withLock {
                        chatRepo.sendMessage(
                            projectId, groupId,
                            if (message.type == MessageType.IMAGE) "IMAGE" else "FILE",
                            content,
                            // 重发复用原 clientMessageId → 后端幂等返回原消息，不产生重复消息
                            clientMessageId = message.clientMessageId,
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

    /** 拉取 DIFF 卡片预览。消息列表不等待该请求；同一 diffId 的并发请求合并为一次。 */
    private fun loadDiff(diffId: String, onLoaded: (List<DiffFile>) -> Unit) {
        diffPreviewCache[diffId]?.let(onLoaded)
        if (diffPreviewCache.containsKey(diffId)) return

        val pending = diffPreviewCallbacks[diffId]
        if (pending != null) {
            pending += onLoaded
            return
        }

        val projectId = mainViewModel.currentProjectId()
        if (projectId == null) {
            onLoaded(emptyList())
            return
        }
        diffPreviewCallbacks[diffId] = mutableListOf(onLoaded)
        viewLifecycleOwner.lifecycleScope.launch {
            val result = diffRepo.getDiffFiles(projectId, diffId)
            val files = result.getOrNull().orEmpty().map { it.toDiffFile() }
            if (result.isSuccess) diffPreviewCache[diffId] = files
            diffPreviewCallbacks.remove(diffId).orEmpty().forEach { callback -> callback(files) }
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
                .onSuccess { detail -> showTaskDiffReviewDialog(projectId, taskId, detail) }
                .onFailure { e ->
                    Toast.makeText(requireContext(), "加载任务失败：${e.message}", Toast.LENGTH_SHORT).show()
                }
        }
    }

    /**
     * 拉取 Task 详情后弹出 Diff Review 对话框（§12.3 + MR_FIRST B 方案）。
     * 也用于 delivery.started / 409 冲突后刷新状态。
     */
    private suspend fun showTaskDiffReviewDialog(projectId: String, taskId: String, detail: TaskDetailDto) {
        val diffSummary = detail.diffReviewSummary
        // 无代码变更任务（FINAL_DIFF_EMPTY）：无 Diff Review 可确认，仅提示空态，不弹确认对话框（§15.6.4/§20.3）
        if (mainViewModel.isNoCodeChangeTask(taskId)) {
            Toast.makeText(requireContext(), R.string.task_no_code_change, Toast.LENGTH_SHORT).show()
            return
        }
        // 从 JsonElement 解析 diffId：仅用于展示首个 Diff 内容；解析不到仍可确认整个批次
        val diffId = extractDiffId(diffSummary) ?: taskDiffIdMap[taskId]
        val reviewStatus = extractStringField(diffSummary, "reviewStatus")
        val confirmationSource = extractStringField(diffSummary, "confirmationSource")
        val deliveryStatus = extractStringField(diffSummary, "deliveryStatus")
        // 45 节：任务级失败原因统一走后端 statusReason（summary 优先，含持久化脱敏 failureReason），
        // 与任务详情页同一来源避免矛盾；旧后端 statusReason 缺失时回退 diffReviewSummary 解析（兼容）
        val deliveryFailedReason = detail.statusReason?.summary
            ?: detail.statusReason?.title
            ?: extractDeliveryFailedReason(diffSummary)
        // 按钮规则（MR_FIRST B 方案）：仅 PENDING_CONFIRMATION 且非 SYSTEM 显示确认/拒绝；
        // Diff 审核仅任务发起人或 Project Admin 可确认/拒绝（客户端自判：创建者比对 + 管理员校验）。
        // PARTIALLY_DELIVERED / FAILED 或任务 DELIVERY_FAILED 才显示重试（能力位优先）
        val canDecide = DiffReviewRules.canConfirmOrReject(reviewStatus, confirmationSource)
        val canOperate = com.example.qgent.ui.delivery.DeliveryPermission.canDecide(
            com.example.qgent.data.SessionStore.user()?.id,
            detail.createdByUser?.id,
            mainViewModel.isProjectAdmin(projectId)
        )
        val canConfirm = canDecide && canOperate
        val canReject = canDecide && canOperate
        val canRetry = !DiffReviewRules.isSuperseded(reviewStatus) && DiffReviewRules.canRetryDelivery(
            deliveryStatus, detail.status, detail.capabilities?.canRetryDelivery
        )
        showDiffConfirmDialog(
            projectId, taskId, diffId, detail.title, detail.status,
            reviewStatus = reviewStatus,
            confirmationSource = confirmationSource,
            canConfirm = canConfirm,
            canReject = canReject,
            canRetry = canRetry,
            deliveryStatus = deliveryStatus,
            deliveryFailedReason = deliveryFailedReason
        )
    }

    /** 从 diffReviewSummary JsonElement 解析交付失败原因（兼容 deliveryFailedReason/failedReason/errorMessage/message 等字段名） */
    private fun extractDeliveryFailedReason(diffSummary: com.google.gson.JsonElement?): String? {
        if (diffSummary == null || !diffSummary.isJsonObject) return null
        val obj = diffSummary.asJsonObject
        val candidates = listOf("deliveryFailedReason", "failedReason", "deliveryError", "errorMessage", "message")
        for (key in candidates) {
            val v = obj.get(key)
            if (v != null && !v.isJsonNull && !v.isJsonObject && !v.isJsonArray) {
                val s = v.asString
                if (s.isNotBlank()) return s
            }
        }
        return null
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
     * Diff Review 确认对话框（§12.3 + MR_FIRST B 方案）：
     * - 内容区：任务状态 + 总体交付状态 + 批次摘要（仓库数/文件数/增删行）+ 逐仓库交付进度
     *   + 首个 Diff 的文件内容（有 diffId 时）
     * - 按钮：PENDING_CONFIRMATION 且非 SYSTEM 前提下，仅任务发起人或 Project Admin
     *   可确认/拒绝（后端能力位 canConfirmDiffReview/canRejectDiffReview，缺省兜底 true）；
     *   ACCEPTED+USER 显示「已由用户确认」、ACCEPTED+SYSTEM 显示「自动交付」，均只读；
     *   canRetry（部分失败/失败）时提供「重试交付」
     * - MR 链接仅在 mergeRequest.webUrl 非空时展示
     */
    private fun showDiffConfirmDialog(
        projectId: String,
        taskId: String,
        diffId: String?,
        taskTitle: String,
        taskStatus: String,
        reviewStatus: String? = null,
        confirmationSource: String? = null,
        canConfirm: Boolean = true,
        canReject: Boolean = true,
        canRetry: Boolean = false,
        deliveryStatus: String? = null,
        deliveryFailedReason: String? = null
    ) {
        val density = resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()

        // 弹窗内容：批次摘要（固定头部） + 文件区（整页横向滚动：长行不换行、短行留白，文件按序排列）
        val contentView = com.example.qgent.databinding.DialogDiffReviewBinding.inflate(layoutInflater)
        val summaryTv = contentView.summaryTv
        val fileSummaryContainer = contentView.codeBlockContainer
        // 文件区：加载完成前转圈，完成后放入整页横向滚动的 Diff 代码块
        fileSummaryContainer.addView(ProgressBar(requireContext()).apply {
            layoutParams = FrameLayout.LayoutParams(dp(36), dp(36), Gravity.CENTER)
        })

        // 拉取批次摘要 + Diff 文件内容（DTO → UI DiffFile 再渲染）
        viewLifecycleOwner.lifecycleScope.launch {
            val sb = StringBuilder("任务状态：").append(taskStatus).append("\n\n")
            DiffReviewRules.reviewStatusCaption(reviewStatus)?.let {
                sb.append("审核状态：").append(it).append("\n")
            }
            when {
                // 部分失败/失败：展示稳定文案 + 失败原因（后端脱敏文本）
                deliveryStatus == "PARTIALLY_DELIVERED" || deliveryStatus == "FAILED" || deliveryStatus == "DELIVERY_FAILED" ->
                    sb.append("⚠️ ").append(DiffReviewRules.deliveryStatusCaption(deliveryStatus))
                        .append("：").append(deliveryFailedReason ?: "详见任务运行执行日志").append("\n\n")
                // 其余非待确认状态：展示交付状态文案（不展示原始枚举）
                !deliveryStatus.isNullOrBlank() && deliveryStatus != "PENDING_CONFIRMATION" ->
                    sb.append("交付状态：").append(DiffReviewRules.deliveryStatusCaption(deliveryStatus) ?: deliveryStatus).append("\n\n")
            }
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
                        // 逐仓库交付进度（MR_FIRST B 方案）
                        batch.repositoryDeliveries?.forEach { rd ->
                            val repoName = rd.repositoryName ?: rd.repositoryId
                            sb.append("• ").append(repoName)
                                .append("：").append(DiffReviewRules.repositoryDeliveryCaption(rd.deliveryStatus))
                            rd.failureReason?.takeIf { it.isNotBlank() }?.let { sb.append("（").append(it).append("）") }
                            // MR 链接仅在 webUrl 非空时展示
                            val mrUrl = rd.mergeRequest?.webUrl
                            if (!mrUrl.isNullOrBlank()) {
                                sb.append("  MR #").append(rd.mergeRequest?.number ?: "")
                            }
                            sb.append("\n")
                        }
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
            summaryTv.text = if (sb.isBlank()) "暂无 Diff 内容" else sb.toString()

            // 文件区：整页横向滚动查看（长行不换行、短行留白）
            fileSummaryContainer.removeAllViews()
            if (!diffId.isNullOrBlank()) {
                val files = diffRepo.getDiffFiles(projectId, diffId).getOrNull().orEmpty().map { it.toDiffFile() }
                if (files.isNotEmpty()) {
                    fileSummaryContainer.addView(buildDiffFileSummaryBlock(files))
                } else {
                    summaryTv.append("\n\n（该 Diff 无文件内容）")
                    fileSummaryContainer.addView(TextView(requireContext()).apply {
                        text = "（该 Diff 无文件内容）"
                        textSize = 13f
                        gravity = Gravity.CENTER
                    })
                }
            } else {
                fileSummaryContainer.addView(TextView(requireContext()).apply {
                    text = "（无文件内容）"
                    textSize = 13f
                    gravity = Gravity.CENTER
                })
            }
        }

        val title = when {
            canConfirm || canReject -> "待确认 Diff · $taskTitle"
            DiffReviewRules.isSuperseded(reviewStatus) -> "已被后续修改取代 · $taskTitle"
            reviewStatus == "ACCEPTED" -> "${DiffReviewRules.acceptedCaption(confirmationSource)} · $taskTitle"
            else -> "Diff · $taskTitle"
        }
        val dialogBuilder = MaterialAlertDialogBuilder(requireContext())
            .setTitle(title)
            .setView(contentView.root)
        if (canConfirm || canReject) {
            if (canReject) {
                dialogBuilder.setNegativeButton(R.string.reject_diff) { _, _ -> rejectDiffReview(projectId, taskId) }
            }
            if (canConfirm) {
                dialogBuilder.setPositiveButton(R.string.confirm_diff) { _, _ -> confirmDiffReview(projectId, taskId) }
            }
        } else {
            dialogBuilder.setPositiveButton(R.string.close, null)
        }
        if (canRetry) {
            dialogBuilder.setNeutralButton(R.string.retry_delivery) { _, _ -> retryDiffDelivery(projectId, taskId) }
        }
        dialogBuilder.show()
    }

    /** Diff 审核弹窗文件区：只显示文件路径和增删行数，避免完整代码撑满弹窗。 */
    private fun buildDiffFileSummaryBlock(files: List<DiffFile>): View {
        return ScrollView(requireContext()).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            addView(LinearLayout(requireContext()).apply {
                orientation = LinearLayout.VERTICAL
                files.forEach { file ->
                    addView(TextView(requireContext()).apply {
                        text = "${file.fileName}\n+${file.additions} -${file.deletions}"
                        setTextColor(requireContext().getColor(R.color.text_primary))
                        setBackgroundColor(requireContext().getColor(R.color.diff_header_bg))
                        setPadding(dp(12), dp(8), dp(12), dp(8))
                        textSize = 13f
                        setLineSpacing(dp(2).toFloat(), 1f)
                    }, LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { bottomMargin = dp(6) })
                }
            })
        }
    }

    /** 确认整个最终 Diff 批次（POST .../tasks/{taskId}/diff-review/confirm，§12.3；Idempotency-Key 必填） */
    private fun confirmDiffReview(projectId: String, taskId: String) {
        viewLifecycleOwner.lifecycleScope.launch {
            diffRepo.confirmDiffReview(projectId, taskId, UUID.randomUUID().toString())
                .onSuccess {
                    Toast.makeText(requireContext(), "已确认 Diff，Agent 提交合并请求等待审核", Toast.LENGTH_LONG).show()
                }
                .onFailure { e ->
                    // §27.4：分支存在未合并 MR 时引导查看 MR（不得转为普通失败/DELIVERY_FAILED）
                    if (e is ApiException && DiffReviewRules.isOpenMrBlocked(e.code)) {
                        OpenMrGuidance.show(requireContext(), projectId, e)
                    } else if (e is ApiException && DiffReviewRules.isConflict(e.code)) {
                        refreshTaskDiffReviewAfterConflict(projectId, taskId)
                    } else {
                        Toast.makeText(requireContext(), "确认失败：${e.message}", Toast.LENGTH_LONG).show()
                    }
                }
        }
    }

    /** 拒绝整个最终 Diff 批次（POST .../tasks/{taskId}/diff-review/reject，§12.3；Idempotency-Key 必填） */
    private fun rejectDiffReview(projectId: String, taskId: String) {
        // 拒绝可填原因（布局 dialog_reject_diff）
        val input = layoutInflater.inflate(R.layout.dialog_reject_diff, null) as EditText
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
                            if (e is ApiException && DiffReviewRules.isOpenMrBlocked(e.code)) {
                                OpenMrGuidance.show(requireContext(), projectId, e)
                            } else if (e is ApiException && DiffReviewRules.isConflict(e.code)) {
                                refreshTaskDiffReviewAfterConflict(projectId, taskId)
                            } else {
                                Toast.makeText(requireContext(), "拒绝失败：${e.message}", Toast.LENGTH_LONG).show()
                            }
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
                    if (e is ApiException && DiffReviewRules.isOpenMrBlocked(e.code)) {
                        OpenMrGuidance.show(requireContext(), projectId, e)
                    } else if (e is ApiException && DiffReviewRules.isConflict(e.code)) {
                        refreshTaskDiffReviewAfterConflict(projectId, taskId)
                    } else {
                        Toast.makeText(requireContext(), "重试交付失败：${e.message}", Toast.LENGTH_LONG).show()
                    }
                }
        }
    }

    /** 收到 409 冲突：刷新 Task 详情与 DiffReview 后再决定按钮状态（§v1.10.0 B 方案） */
    private fun refreshTaskDiffReviewAfterConflict(projectId: String, taskId: String) {
        viewLifecycleOwner.lifecycleScope.launch {
            taskRepo().getTaskDetail(projectId, taskId)
                .onSuccess { detail -> showTaskDiffReviewDialog(projectId, taskId, detail) }
                .onFailure { e ->
                    Toast.makeText(requireContext(), "刷新任务状态失败：${e.message}", Toast.LENGTH_SHORT).show()
                }
        }
    }

    /**
     * DIFF 卡点击（§v1.9.4 A3）：
     * 优先用 DIFF 卡自带 taskId（content 含 taskId）→ 打开 Task Diff 审核对话框（与 TASK_STATUS 卡同一审核面板，
     * 含确认/拒绝按钮）；taskId 缺失时用 diffId 反向映射（diff.created 事件缓存）兜底；
     * 都拿不到才退化纯 diff 文件查看。修复「diffIdToTaskIdMap 事件缓存未命中时看不到确认按钮」。
     */
    private fun onDiffCardClick(message: ChatMessage) {
        if (isOpeningDiffDialog) return
        isOpeningDiffDialog = true
        val projectId = mainViewModel.currentProjectId() ?: run {
            isOpeningDiffDialog = false
            return
        }
        // REJECTED：回群引用续作（根据拒绝意见继续修改），不打开审核弹窗
        if (message.reviewStatus == "REJECTED") {
            startContinueModify(projectId, message)
            isOpeningDiffDialog = false
            return
        }
        Toast.makeText(requireContext(), "正在加载 Diff 审核...", Toast.LENGTH_SHORT).show()
        val taskId = message.taskId ?: message.diffId?.let { diffIdToTaskIdMap[it] }
        if (!taskId.isNullOrBlank()) {
            openTaskDiffReview(projectId, taskId) { isOpeningDiffDialog = false }
            return
        }
        val diffId = message.diffId
        if (!diffId.isNullOrBlank()) {
            showDiffFilesDialog(projectId, diffId, message.diffTitle)
            isOpeningDiffDialog = false
            return
        }
        Toast.makeText(requireContext(), "DIFF 卡缺少 diffId", Toast.LENGTH_SHORT).show()
        isOpeningDiffDialog = false
    }

    /** REJECTED DIFF 卡 → 回群引用续作：先查预检状态阻止（预检中/有 MR），无阻止则引用 + 预填拒绝意见模板 */
    private fun startContinueModify(projectId: String, message: ChatMessage) {
        val taskId = message.taskId ?: message.diffId?.let { diffIdToTaskIdMap[it] }
        viewLifecycleOwner.lifecycleScope.launch {
            if (taskId != null && blockedByPreflight(projectId, taskId)) return@launch
            // 预填：输入框为空才填拒绝意见模板，已有输入不覆盖
            val reason = message.reviewReason?.takeIf { it.isNotBlank() }
            if (reason != null && binding.etInput.text.isNullOrBlank()) {
                binding.etInput.setText(getString(R.string.diff_continue_modify_prefill, reason))
                binding.etInput.setSelection(binding.etInput.length())
            }
            setQuote(message)
        }
    }

    /** 预检/已有 MR 阻止续作：返回 true 表示应阻止引用 */
    private suspend fun blockedByPreflight(projectId: String, taskId: String): Boolean {
        val status = taskRepo().getTaskMergeRequestPreflight(projectId, taskId).getOrNull()?.firstOrNull() ?: return false
        val blocking = status.status in setOf("REQUESTED", "DRY_RUN_QUEUED", "DRY_RUN_RUNNING", "WAITING_CQ", "CREATING_MR", "MR_CREATED")
        val hasMr = status.status == "MR_CREATED" || status.mergeRequest != null
        if (blocking || hasMr) {
            val msg = when (status.status) {
                "REQUESTED", "DRY_RUN_QUEUED", "DRY_RUN_RUNNING" -> "预检进行中，暂不能继续修改"
                "WAITING_CQ" -> "等待 CQ+1，暂不能继续修改"
                "CREATING_MR" -> "正在创建 MR，暂不能继续修改"
                "MR_CREATED" -> "该 Diff 已创建 MR，无需继续修改"
                else -> "该 Diff 已有合并请求，暂不能继续修改"
            }
            Toast.makeText(requireContext(), msg, Toast.LENGTH_LONG).show()
            return true
        }
        return false
    }

    /** 拉取任务详情 → 弹 Diff Review 审核对话框（TASK_STATUS 卡 / DIFF 卡共用） */
    private fun openTaskDiffReview(projectId: String, taskId: String, onFinished: () -> Unit = {}) {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                taskRepo().getTaskDetail(projectId, taskId)
                    .onSuccess { detail -> showTaskDiffReviewDialog(projectId, taskId, detail) }
                    .onFailure { e ->
                        Toast.makeText(requireContext(), "加载任务失败：${e.message}", Toast.LENGTH_SHORT).show()
                    }
            } finally {
                onFinished()
            }
        }
    }

    /** DIFF 卡「完整 Diff」：优先显示卡片当前选中的文件；未加载时回退全量 Diff。 */
    private fun onViewFullDiffClick(message: ChatMessage, selectedFile: DiffFile?) {
        if (isOpeningDiffDialog) return
        isOpeningDiffDialog = true
        Toast.makeText(requireContext(), "正在加载完整 Diff...", Toast.LENGTH_SHORT).show()
        val projectId = mainViewModel.currentProjectId() ?: run {
            isOpeningDiffDialog = false
            return
        }
        if (selectedFile != null) {
            showDiffFilesDialog(message.diffTitle ?: selectedFile.fileName, listOf(selectedFile))
            isOpeningDiffDialog = false
            return
        }
        val diffId = message.diffId
        if (!diffId.isNullOrBlank()) {
            showDiffFilesDialog(projectId, diffId, message.diffTitle)
            isOpeningDiffDialog = false
            return
        }
        val taskId = message.taskId
        if (!taskId.isNullOrBlank()) {
            viewLifecycleOwner.lifecycleScope.launch {
                try {
                    taskRepo().getTaskDetail(projectId, taskId)
                        .onSuccess { detail ->
                            val resolved = extractDiffId(detail.diffReviewSummary) ?: taskDiffIdMap[taskId]
                            if (resolved.isNullOrBlank()) {
                                Toast.makeText(requireContext(), "DIFF 卡缺少 diffId", Toast.LENGTH_SHORT).show()
                            } else {
                                showDiffFilesDialog(projectId, resolved, message.diffTitle)
                            }
                        }
                        .onFailure { e ->
                            Toast.makeText(requireContext(), "加载任务失败：${e.message}", Toast.LENGTH_SHORT).show()
                        }
                } finally {
                    // 页面销毁导致协程取消时也必须释放锁，避免重进群聊后入口失效。
                    isOpeningDiffDialog = false
                }
            }
            return
        }
        Toast.makeText(requireContext(), "DIFF 卡缺少 diffId", Toast.LENGTH_SHORT).show()
        isOpeningDiffDialog = false
    }

    /** 全屏查看 diff 文件：可滑动，绿加红减，文件头显示 basename（卡片点击 / 「完整 Diff」入口） */
    private fun showDiffFilesDialog(projectId: String, diffId: String, title: String?) {
        // 完整 Diff 弹窗（布局 dialog_diff_files）：整页横向滚动查看（长行不换行、短行留白）
        val dialogBinding = com.example.qgent.databinding.DialogDiffFilesBinding.inflate(layoutInflater)
        val container = dialogBinding.container
        container.addView(ProgressBar(requireContext()).apply {
            layoutParams = FrameLayout.LayoutParams(dp(40), dp(40), Gravity.CENTER)
        })
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(title ?: "Diff")
            .setView(dialogBinding.root)
            .setPositiveButton(R.string.close, null)
            .show()
        viewLifecycleOwner.lifecycleScope.launch {
            val files = diffRepo.getDiffFiles(projectId, diffId).getOrNull().orEmpty().map { it.toDiffFile() }
            container.removeAllViews()
            if (files.isEmpty()) {
                container.addView(TextView(requireContext()).apply {
                    text = "（该 Diff 无文件内容）"
                    textSize = 13f
                    gravity = Gravity.CENTER
                })
            } else {
                container.addView(buildDiffCodeBlock(files))
            }
        }
    }

    /** 已在 DIFF 卡加载的当前文件不再重复请求，直接打开对应文件的完整代码视图。 */
    private fun showDiffFilesDialog(title: String, files: List<DiffFile>) {
        val dialogBinding = com.example.qgent.databinding.DialogDiffFilesBinding.inflate(layoutInflater)
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(title)
            .setView(dialogBinding.root)
            .setPositiveButton(R.string.close, null)
            .show()
        dialogBinding.container.addView(buildDiffCodeBlock(files))
    }

    /** dp 转 px（弹窗内代码行布局用） */
    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    /**
     * 构建 Diff 代码块（弹窗用）：垂直 ScrollView（上下浏览文件/行）+ 横向 HorizontalScrollView
     * （整页一起横向滑动，超长代码行不换行、短行右侧留白）。所有文件按序排列，每文件带文件头。
     */
    private fun buildDiffCodeBlock(files: List<DiffFile>): View {
        // 容器布局：垂直 ScrollView > 横向 HorizontalScrollView > 行容器（整页横滚，短行留白）
        val binding = com.example.qgent.databinding.DialogDiffScrollBinding.inflate(layoutInflater)
        val content = binding.container
        files.forEach { file ->
            // 文件头：basename + 变更统计
            content.addView(TextView(requireContext()).apply {
                text = "${file.fileName.substringAfterLast('/')}  +${file.additions} -${file.deletions}"
                setTextColor(requireContext().getColor(R.color.text_primary))
                setBackgroundColor(requireContext().getColor(R.color.diff_header_bg))
                setTypeface(null, android.graphics.Typeface.BOLD)
                setPadding(dp(10), dp(6), dp(10), dp(6))
                textSize = 13f
            })
            if (file.lines.isEmpty()) {
                content.addView(TextView(requireContext()).apply {
                    text = "（该文件无行内容）"
                    textSize = 12f
                    setPadding(dp(10), dp(4), dp(10), dp(4))
                })
            }
            file.lines.forEach { line -> content.addView(buildDiffLineView(line)) }
        }
        return binding.root
    }

    /** 单行 Diff 代码（布局 item_diff_full_line：wrap_content 超长行不换行，由外层横向滚动整页移动） */
    private fun buildDiffLineView(line: DiffLine): View {
        val binding = com.example.qgent.databinding.ItemDiffFullLineBinding.inflate(layoutInflater)
        binding.root.setBackgroundColor(requireContext().getColor(when (line.type) {
            DiffLineType.ADD -> R.color.diff_add_bg
            DiffLineType.DELETE -> R.color.diff_del_bg
            else -> R.color.white
        }))
        binding.tvSign.text = when (line.type) {
            DiffLineType.ADD -> "+"
            DiffLineType.DELETE -> "-"
            else -> " "
        }
        binding.tvSign.setTextColor(requireContext().getColor(when (line.type) {
            DiffLineType.ADD -> R.color.diff_add_fg
            DiffLineType.DELETE -> R.color.diff_del_fg
            else -> R.color.diff_line_no
        }))
        binding.tvCode.text = line.text
        return binding.root
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
        val rv = binding.rvMessages
        rv.post {
            // view 已销毁（onDestroyView → _binding=null）时跳过，避免 getBinding 抛 NPE
            if (_binding != null) rv.scrollToPosition(adapter.itemCount - 1)
        }
    }

    /** 从群设置的搜索结果返回时，定位并高亮被点击的消息。 */
    private fun observeSearchTargetMessage() {
        val entry = findNavController().currentBackStackEntry ?: return
        entry.savedStateHandle
            .getLiveData<String>(RESULT_SEARCH_TARGET_MESSAGE_ID)
            .observe(viewLifecycleOwner) { messageId ->
                if (messageId.isNullOrBlank()) return@observe
                entry.savedStateHandle.remove<String>(RESULT_SEARCH_TARGET_MESSAGE_ID)
                val projectId = mainViewModel.currentProjectId() ?: return@observe
                if (groupId.isEmpty()) return@observe
                arguments?.putString(ARG_TARGET_MESSAGE_ID, messageId)
                locateTargetMessage(projectId, groupId)
            }
    }

    // ── §7.1 通知直达被 @ 消息：滚动 + 高亮 ──

    /**
     * 直达定位：目标消息 id 在已加载分页内 → 直接滚动高亮；
     * 在分页窗口外（较旧）→ 单消息 GET 分页外定位（接口文档 §7.1），
     * 拉取后合并进本地列表再滚动高亮；GET 失败或 resourceId 缺失（@ 提及来源）→
     * 兜底滚到列表中最上面一条被 @ 的消息。
     */
    private fun locateTargetMessage(projectId: String, groupId: String) {
        val target = targetMessageId
        // 消费一次：定位后移除参数，避免轮询刷新/配置变更重复触发
        arguments?.remove(ARG_TARGET_MESSAGE_ID)
        if (target.isNullOrEmpty()) {
            if (fromMention) scrollToTopMentioned()
            return
        }
        if (messages.any { it.id == target }) {
            scrollToMessage(target)
            return
        }
        viewLifecycleOwner.lifecycleScope.launch {
            chatRepo.getMessage(projectId, groupId, target)
                .onSuccess { dto ->
                    val msg = dto.toChatMessage(SessionStore.user()?.id, memberNamesById)
                    setMessages(mergeWithNetwork(listOf(msg)))
                    scrollToMessage(target)
                }
                .onFailure {
                    scrollToTopMentioned()
                }
        }
    }

    /** 滚动到目标消息行并高亮（高亮 3s 后自动清除） */
    private fun scrollToMessage(messageId: String) {
        val rowIndex = rows.indexOfFirst { (it as? ChatRow.Message)?.message?.id == messageId }
        if (rowIndex < 0) return
        val rv = binding.rvMessages
        rv.post {
            if (_binding != null) {
                rv.scrollToPosition(rowIndex)
                highlightMessage(messageId)
            }
        }
    }

    /** §7.1 resourceId 缺失兜底：滚动到列表中最上面（最旧）一条 @ 我的消息 */
    private fun scrollToTopMentioned() {
        val myId = SessionStore.user()?.id ?: return
        val rowIndex = rows.indexOfFirst { row ->
            val m = (row as? ChatRow.Message)?.message ?: return@indexOfFirst false
            m.senderId != myId && m.mentionIds?.contains(myId) == true
        }
        if (rowIndex < 0) return
        val id = (rows[rowIndex] as ChatRow.Message).message.id
        val rv = binding.rvMessages
        rv.post {
            if (_binding != null) {
                rv.scrollToPosition(rowIndex)
                highlightMessage(id)
            }
        }
    }

    /** 目标消息整行高亮，HIGHLIGHT_DURATION_MS 后自动清除（滚动/轮询重绘期间保持） */
    private fun highlightMessage(messageId: String) {
        adapter.setHighlightMessageId(messageId)
        binding.root.postDelayed({ adapter.setHighlightMessageId(null) }, HIGHLIGHT_DURATION_MS)
    }

    // ── 未读「↑ 有人@你」提示条（对齐 web ChatPanel：seq > 已读游标 且 mentions 含我 且非本人发送） ──

    /** 未读「@ 我」消息（升序）；lastReadSeq 为空（进群全读完成前）不提示，避免把历史 @ 消息当未读 */
    private fun unreadMentionMessages(): List<ChatMessage> {
        val lastRead = mainViewModel.lastReadSeq(groupId) ?: return emptyList()
        val myId = SessionStore.user()?.id ?: return emptyList()
        return messages.filter { m ->
            m.senderId != myId &&
                m.sequence > lastRead &&
                m.mentionIds?.contains(myId) == true
        }
    }

    /** 更新提示条显隐：最新一条未读 @ 消息存在且未被忽略时显示（消息列表变化后调用） */
    private fun updateMentionBar() {
        val mentionMessage = unreadMentionMessages().lastOrNull()
        currentMentionMessageId = mentionMessage?.id
        val show = mentionMessage != null && mentionMessage.id != dismissedMentionId
        binding.btnMentionBar.isVisible = show
        // 诊断：提示条未显示时核对判定条件（游标 / 未读 @ 候选）
        Log.d("MentionBar", "lastReadSeq=${mainViewModel.lastReadSeq(groupId)} myId=${SessionStore.user()?.id} " +
            "mentionCandidates=${unreadMentionMessages().size} dismissed=${dismissedMentionId} show=$show")
    }

    /** 点击提示条：滚动高亮到被 @ 的消息，并忽略该条（新 @ 消息再来时重新出现，对齐 web） */
    private fun onMentionBarClick() {
        val id = currentMentionMessageId ?: return
        dismissedMentionId = id
        binding.btnMentionBar.isVisible = false
        scrollToMessage(id)
    }

    private fun loadInitialData() {
        val projectId = mainViewModel.currentProjectId()
        val groupId = arguments?.getString("groupId").orEmpty()

        if (projectId == null || groupId.isEmpty()) {
            setMessages(emptyList())
            return
        }

        binding.messageLoadingState.isVisible = true
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                // 先读本地缓存秒开，无缓存才展示遮罩等待网络
                val cached = messageCache.load(groupId)
                if (cached.isNotEmpty()) {
                    setMessages(cached)
                    binding.messageLoadingState.isVisible = false
                }

                // 并行拉成员表 + 消息（原串行改并发）
                val membersDeferred = async { chatRepo.getMembers(projectId, groupId) }
                val messagesDeferred = async { chatRepo.getMessagesPage(projectId, groupId) }

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
                messagesResult.onSuccess { page ->
                    nextCursor = page.nextCursor
                    hasMoreMessages = page.hasMore
                    val list = page.messages.map { it.toChatMessage(myId, memberNamesById) }
                    // 合并时必须以「当前内存列表」为基准（而非开头读的 cached 快照）：
                    // 若初始 getMessages 较慢，期间用户已发出消息并 append 到 messages，
                    // 用 cached 会把这几天新消息连同网络结果一起覆盖掉，导致「发出后几秒消失」。
                    val merged = mergeWithNetwork(list)
                    setMessages(merged)
                    messageCache.save(groupId, merged)
                    // §7.1 通知直达被 @ 消息：目标在分页窗口内直接滚动高亮，否则单消息 GET 分页外定位
                    locateTargetMessage(projectId, groupId)
                }.onFailure {
                    // 网络失败但已有缓存时保留缓存显示，不清空
                    if (messages.isEmpty()) setMessages(emptyList())
                }
            } finally {
                binding.messageLoadingState.isVisible = false
            }
        }
    }

    /**
     * 上滑加载更早消息（游标分页）：拉下一页合并到列表顶部，保持滚动位置；
     * 新拉到的消息 sequence 更小，按序插入前方（与 sortedChronologically 排序一致）。
     */
    private fun loadOlderMessages() {
        val projectId = mainViewModel.currentProjectId() ?: return
        val groupId = arguments?.getString("groupId").orEmpty()
        val cursor = nextCursor
        if (projectId == null || groupId.isEmpty() || cursor.isNullOrEmpty()) return
        if (loadingOlder || !hasMoreMessages) return
        loadingOlder = true
        binding.pbLoadingOlder.isVisible = true
        viewLifecycleOwner.lifecycleScope.launch {
            // 记录当前首个可见项位置，插入顶部后恢复（避免列表跳动）
            val lm = binding.rvMessages.layoutManager as? LinearLayoutManager
            val firstPos = lm?.findFirstVisibleItemPosition() ?: 0
            val firstTop = binding.rvMessages.getChildAt(0)?.top ?: 0
            val before = messages.size
            chatRepo.getMessagesPage(projectId, groupId, cursor)
                .onSuccess { page ->
                    nextCursor = page.nextCursor
                    hasMoreMessages = page.hasMore
                    val older = page.messages.map {
                        it.toChatMessage(SessionStore.user()?.id, memberNamesById)
                    }
                    if (older.isNotEmpty()) {
                        val merged = (older + messages).distinctBy { it.id }.sortedChronologically()
                        setMessages(merged)
                        // 顶部插入了 (merged.size - before) 条 → 原首个可见项下移对应行数
                        val inserted = merged.size - before
                        lm?.scrollToPositionWithOffset(firstPos + inserted, firstTop)
                    }
                }
                .onFailure { e ->
                    Log.w("ChatPage", "加载更早消息失败: ${e.message}")
                }
            loadingOlder = false
            binding.pbLoadingOlder.isVisible = false
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
            // 群聊中的唯一 @ 入口必须与后端发送任务通知/Diff 卡的 ORCHESTRATOR 使用同一 Agent ID。
            // 没有可用编排助手时不以普通执行 Agent 冒充，避免 @ 到错误的对象。
            mainViewModel.agents.value.orEmpty()
                .firstOrNull {
                    it.roleWire?.equals("ORCHESTRATOR", ignoreCase = true) == true &&
                        it.status.name == "ACTIVE" && it.visibility.name == "TEAM"
                }
                ?.let {
                    listOf(
                        GroupMember(
                            id = it.id,
                            name = getString(R.string.chat_group_agent_name),
                            type = MemberType.AGENT,
                            avatar = it.avatar
                        )
                    )
                }
                .orEmpty()
        }
        val agentIds = teamAgents.map { it.id }.toSet()
        // 后端群成员中可能含 Agent（memberType=AGENT）：全部过滤，只保留合并的单一 Agent（避免叠加成多个）
        val mergedMembers = baseGroupMembers.filter { it.id !in agentIds && it.type != MemberType.AGENT } + teamAgents
        groupMembers = mergedMembers
        memberNamesById = mergedMembers.associate { it.id to it.name }
        memberById = mergedMembers.associate { it.id to it }
        // 成员头像映射 → 消息气泡旁展示他人头像
        adapter.setMemberAvatars(mergedMembers.mapNotNull { m -> m.avatar?.takeIf { it.isNotBlank() }?.let { m.id to it } }.toMap())
    }

    /** 群成员变动（group.member.updated）后刷新成员表：@ 列表/成员映射立即包含新成员 */
    private fun refreshGroupMembers() {
        val projectId = mainViewModel.currentProjectId() ?: return
        val groupId = arguments?.getString("groupId").orEmpty()
        if (groupId.isEmpty()) return
        // 成员变动 → 清 MainViewModel 成员关系缓存（isGroupMember 结果可能过期）
        mainViewModel.clearGroupMemberCache()
        viewLifecycleOwner.lifecycleScope.launch {
            chatRepo.getMembers(projectId, groupId)
                .onSuccess { dtos ->
                    Log.v("ChatMember", "刷新群成员成功: ${dtos.size} 人 ${dtos.map { it.resolvedName }}")
                    baseGroupMembers = dtos.map { it.toGroupMember() }
                    rebuildMemberMaps()
                }
                .onFailure { e ->
                    Log.w("ChatDetail", "刷新群成员失败: ${e.message}")
                }
        }
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
            // 诊断日志：SYSTEM 消息（"XXX 加入群聊"）是否存在；无 SYSTEM 时为 verbose 级避免刷屏
            val systemMsgs = list.filter { it.type == MessageType.SYSTEM }
            if (systemMsgs.isNotEmpty()) {
                Log.v("ChatPoll", "SYSTEM消息 ${systemMsgs.size} 条: ${systemMsgs.joinToString { it.content }}")
            } else {
                Log.v("ChatPoll", "本次拉取 ${list.size} 条，无 SYSTEM 消息")
            }
            // 轮询时保留本会话内刚发的本地兜底消息（keepLocal=true），
            // 避免用户刚发送失败的消息被下一次轮询立刻删掉；下次进页面时由 loadInitialData 清掉
            val merged = mergeWithNetwork(list, keepLocal = true)
            if (merged != messages) {
                setMessages(merged)
                messageCache.save(groupId, merged.filterNot { it.id.startsWith(LOCAL_ID_PREFIX) })
            }
        }
    }

    /** Fetches every page newer than the newest loaded sequence after a realtime create event. */
    private suspend fun syncMessagesIncrementally(projectId: String, groupId: String) {
        if (syncingIncrementalMessages) return
        val afterSequence: Long = messages.maxOfOrNull { it.sequence }?.takeIf { it > 0L } ?: run {
            pollMessages(projectId, groupId)
            return
        }

        syncingIncrementalMessages = true
        try {
            var cursor: Long = afterSequence
            var hasMore: Boolean
            do {
                val result = chatRepo.getMessagesIncrementalPage(projectId, groupId, cursor)
                var shouldContinue = false
                result.onSuccess { page ->
                    val incoming = page.messages.map {
                        it.toChatMessage(SessionStore.user()?.id, memberNamesById)
                    }
                    if (incoming.isNotEmpty()) {
                        val merged = mergeWithNetwork(incoming, keepLocal = true)
                        if (merged != messages) {
                            setMessages(merged)
                            messageCache.save(groupId, merged.filterNot { it.id.startsWith(LOCAL_ID_PREFIX) })
                        }
                    }
                    val next = page.nextSequence
                    shouldContinue = page.hasMore && next != null && next > cursor
                    if (page.hasMore && !shouldContinue) {
                        Log.w("ChatSync", "Incremental page has no advancing cursor for group=$groupId")
                    }
                    cursor = next ?: cursor
                }.onFailure { error ->
                    Log.w("ChatSync", "Incremental message sync failed: ${error.message}")
                }
                hasMore = shouldContinue
            } while (hasMore)
        } finally {
            syncingIncrementalMessages = false
        }
    }

    private suspend fun refreshUpdatedMessage(projectId: String, groupId: String, data: String?) {
        val messageId = data?.let(::parseMessageId)
        if (messageId == null) {
            pollMessages(projectId, groupId)
            return
        }
        chatRepo.getMessage(projectId, groupId, messageId).onSuccess { dto ->
            val merged = mergeWithNetwork(
                listOf(dto.toChatMessage(SessionStore.user()?.id, memberNamesById)),
                keepLocal = true
            )
            if (merged != messages) {
                setMessages(merged)
                messageCache.save(groupId, merged.filterNot { it.id.startsWith(LOCAL_ID_PREFIX) })
            }
        }.onFailure { error ->
            Log.w("ChatSync", "Updated message refresh failed: ${error.message}")
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
     *
     * v23：同 id 以网络内容为准（network 在前），保证 TASK_STATUS/DIFF 卡「单消息持续更新」
     * 的 content 变更能覆盖本地旧内容（原先 kept 在前会把旧卡片内容保留住，更新不生效）。
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
        // 防御：QUOTE 网络回显缺 replyText 时沿用旧内容（避免轮询把气泡正文刷成「引用：xxx」）
        return (network + kept).distinctBy { it.id }
            .map { net ->
                if (net.type == MessageType.QUOTE && net.content.isBlank()) {
                    val old = messages.firstOrNull { it.id == net.id }
                    if (old != null && old.content.isNotBlank()) net.copy(content = old.content) else net
                } else {
                    net
                }
            }
            .sortedChronologically()
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
        // 是否保持吸底：初始为空（首次加载）或用户正停在底部附近时自动滚动；
        // 向上翻阅历史时（轮询触发刷新）保持当前位置，不强制跳底
        val wasEmpty = rows.isEmpty()
        val pinned = wasEmpty || isNearBottom()
        messages.clear()
        messages.addAll(resolved)
        rows.clear()
        rows.addAll(buildRows(messages))
        adapter.submitList(rows)
        if (pinned) scrollToBottom()
        // 消息列表变化 → 重算未读「有人@你」提示条
        updateMentionBar()
    }

    /** 用户是否停留在消息列表底部附近（最后可见项距底部 ≤2 行视为吸底） */
    private fun isNearBottom(): Boolean {
        val lm = binding.rvMessages.layoutManager as? LinearLayoutManager ?: return true
        val lastVisible = lm.findLastVisibleItemPosition()
        return lastVisible >= adapter.itemCount - 2
    }

    override fun onResume() {
        super.onResume()
        startPolling()
        startEventStream()
        // 进入群聊即标记已读（v2.0.6 §1.2 后端游标推进），未读/@我 立即清零
        val groupId = arguments?.getString("groupId").orEmpty()
        if (groupId.isNotEmpty()) {
            mainViewModel.markGroupRead(groupId)
            // 等 markRead 返回游标后刷新「有人@你」提示条（未读 @ 判定依赖 lastReadSeq；轮询 3s 兜底）
            viewLifecycleOwner.lifecycleScope.launch {
                delay(1_500L)
                if (_binding != null) updateMentionBar()
            }
        }
    }

    override fun onPause() {
        super.onPause()
        stopPolling()
        stopEventStream()
        // 退出详情页时标记已读（后端游标推进到当前已看到的最新消息）
        val groupId = arguments?.getString("groupId").orEmpty()
        if (groupId.isNotEmpty()) mainViewModel.markGroupRead(groupId)
    }

    /**
     * 实时事件（SSE §12.1 + WebSocket 单连接聚合，后端 2026-08-17）：
     * 只对 message.created 且 groupId 匹配当前群的事件刷新消息；
     * 其他任务/Diff 事件（无 groupId）不触发消息拉取，避免事件风暴导致列表频繁重建。
     * WS 为主实时通道（规避 SSE 长连接被 CDN/网关掐断），SSE 保留兜底；事件幂等，重复到达无害。
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
                        // 消息事件：当前群有新消息 → 立即拉取一次。
                        // groupId 解析失败时不静默丢弃：当前页面只显示一个群，收到消息事件直接刷新当前群
                        SseEventType.MESSAGE_CREATED,
                        SseEventType.MESSAGE_UPDATED -> {
                            val targetGroup = parseGroupId(event.data)
                            Log.v("ChatSSE", "${event.type.wire} payload=${event.data} targetGroup=$targetGroup currentGroup=$groupId")
                            if (targetGroup == null || targetGroup == groupId) {
                                if (event.type == SseEventType.MESSAGE_CREATED) {
                                    syncMessagesIncrementally(projectId, groupId)
                                } else {
                                    refreshUpdatedMessage(projectId, groupId, event.data)
                                }
                            }
                        }
                        // 群成员变动（成员进群/退群）：后端会推送 SYSTEM 消息（"XXX 加入群聊"）——
                        // 立即刷新消息让注释实时出现（不等 3s 轮询），并刷新成员表让 @ 列表包含新成员
                        SseEventType.GROUP_MEMBER_UPDATED -> {
                            val targetGroup = parseGroupId(event.data)
                            Log.v("ChatSSE", "group.member.updated payload=${event.data} targetGroup=$targetGroup currentGroup=$groupId")
                            if (targetGroup == groupId) {
                                pollMessages(projectId, groupId)
                                refreshGroupMembers()
                            }
                        }
                        // Diff 相关事件：缓存 taskId → diffId 映射，供 TASK_STATUS 卡片点击确认用
                        SseEventType.DIFF_CREATED,
                        SseEventType.DIFF_REVIEW_CREATED,
                        SseEventType.TASK_AWAITING_DIFF_CONFIRMATION -> cacheTaskDiffId(event.data)
                        // 无代码变更（FINAL_DIFF_EMPTY）：记录任务，卡片点击不弹 Diff 确认（§15.6.4/§20.3）
                        SseEventType.DIFF_REVIEW_SKIPPED -> {
                            val taskIdFromEvent = runCatching {
                                org.json.JSONObject(event.data).optString("taskId")
                            }.getOrNull()
                            if (!taskIdFromEvent.isNullOrBlank()) mainViewModel.recordNoCodeChangeTask(taskIdFromEvent)
                        }
                        SseEventType.DIFF_REVIEW_SUPERSEDED -> pollMessages(projectId, groupId)
                        // delivery.started（MR_FIRST）：以 taskId+operationId 去重，重复/乱序/晚到只刷一次消息，
                        // 让 TASK_STATUS 卡片状态同步；真实状态以查询接口为准
                        SseEventType.DELIVERY_STARTED -> {
                            val key = DiffReviewRules.deliveryStartedKeyFromPayload(event.data)
                            if (key == null || deliveryStartedSeen.add(key)) {
                                pollMessages(projectId, groupId)
                            }
                        }
                        // 交付事件：交付失败/完成 → 刷新消息，让 TASK_STATUS 卡片状态同步（如"交付失败"）
                        SseEventType.DELIVERY_FAILED,
                        SseEventType.DELIVERY_COMPLETED,
                        SseEventType.DELIVERY_REPOSITORY_UPDATED -> pollMessages(projectId, groupId)
                        else -> Unit
                    }
                }
            }
        }
        // WebSocket 通道：帧 type 与 SSE 事件名一致，逻辑对齐 SSE
        if (wsJob == null) {
            wsJob = viewLifecycleOwner.lifecycleScope.launch {
                realtimeClient.events.collect { frame ->
                    when (frame.type) {
                        "message.created", "message.updated" -> {
                            val targetGroup = frame.groupId
                            Log.v("ChatSSE", "ws ${frame.type} groupId=$targetGroup currentGroup=$groupId")
                            if (targetGroup == null || targetGroup == groupId) {
                                if (frame.type == "message.created") {
                                    syncMessagesIncrementally(projectId, groupId)
                                } else {
                                    refreshUpdatedMessage(projectId, groupId, frame.payload)
                                }
                            }
                        }
                        "group.member.updated" -> {
                            pollMessages(projectId, groupId)
                            refreshGroupMembers()
                        }
                        "diff.created", "diff-review.created", "task.awaiting-diff-confirmation" -> {
                            frame.payload?.let { cacheTaskDiffId(it) }
                        }
                        "diff-review.superseded" -> pollMessages(projectId, groupId)
                        "delivery.started" -> {
                            val key = frame.payload?.let { DiffReviewRules.deliveryStartedKeyFromPayload(it) }
                            if (key == null || deliveryStartedSeen.add(key)) {
                                pollMessages(projectId, groupId)
                            }
                        }
                        "delivery.failed", "delivery.completed", "delivery.repository.updated" ->
                            pollMessages(projectId, groupId)
                        else -> Unit
                    }
                }
            }
        }
    }

    /** 缓存事件 payload 中的 taskId → diffId 映射（后端任务详情可能不返回 diffId）；
     *  同时维护 diffId → taskId 反向映射，供 DIFF 卡点击跳转 Diff 审核用（A3）。 */
    private fun cacheTaskDiffId(data: String) {
        runCatching {
            val obj = org.json.JSONObject(data)
            val taskId = obj.optString("taskId").takeIf { it.isNotBlank() }
            val diffId = obj.optString("diffId").takeIf { it.isNotBlank() }
            if (taskId != null && diffId != null) {
                taskDiffIdMap[taskId] = diffId
                diffIdToTaskIdMap[diffId] = taskId
            }
        }
    }

    /** 从事件 payload 中解析 groupId（无该字段返回 null） */
    private fun parseGroupId(data: String): String? =
        runCatching {
            org.json.JSONObject(data).optString("groupId").takeIf { it.isNotBlank() }
        }.getOrNull()

    private fun parseMessageId(data: String): String? =
        runCatching {
            org.json.JSONObject(data).optString("messageId").takeIf { it.isNotBlank() }
        }.getOrNull()

    private fun stopEventStream() {
        eventStreamJob?.cancel()
        eventStreamJob = null
        wsJob?.cancel()
        wsJob = null
        eventStream.stop()
    }

    override fun onDestroyView() {
        diffPreviewCallbacks.clear()
        binding.etInput.removeTextChangedListener(mentionWatcher)
        binding.root.translationY = 0f
        requireActivity().window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        _binding = null
        super.onDestroyView()
    }

    companion object {
        private const val TIME_GAP_MS = 5 * 60 * 1000L
        private const val POLL_INTERVAL_MS = 3_000L
        private const val MAX_PREVIEW_CHARS = 100_000

        /** 附件预览信息缓存 TTL（preview-url 未给 expiresAt 时的兜底，5 分钟） */
        private const val PREVIEW_CACHE_TTL_MS = 5 * 60 * 1000L

        /** §7.1 通知直达：目标消息 id 参数（跳群后滚动高亮到该消息） */
        const val ARG_TARGET_MESSAGE_ID = "targetMessageId"

        /** 群设置搜索结果返回群聊详情页时使用的目标消息 id。 */
        const val RESULT_SEARCH_TARGET_MESSAGE_ID = "searchTargetMessageId"

        /** §7.1 通知直达：来源是否为 @ 提及（resourceId 缺失时兜底滚到最上面一条被 @ 的消息） */
        const val ARG_FROM_MENTION = "fromMention"

        /** 直达高亮持续时长（自动清除） */
        private const val HIGHLIGHT_DURATION_MS = 3_000L

        /** 本地兜底消息（发送失败）id 前缀：不落缓存、合并时剔除，防止幽灵消息残留 */
        private const val LOCAL_ID_PREFIX = "local-"

        private val TEXT_EXTENSIONS = setOf(
            "txt", "md", "json", "xml", "yaml", "yml", "csv", "log", "kt", "java", "py",
            "js", "ts", "html", "css", "sql", "sh", "gradle", "properties", "ini", "conf",
            "go", "rs", "c", "cpp", "h", "hpp", "rb", "php", "swift", "vue", "toml"
        )
    }
}
