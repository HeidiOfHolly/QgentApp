package com.example.qgent.ui.chat
import android.app.Dialog
import android.net.Uri
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
import androidx.core.os.bundleOf
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
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
import com.example.qgent.data.model.MessageContentDto
import com.example.qgent.data.model.toChatMessage
import com.example.qgent.data.model.toGroupMember
import com.example.qgent.data.repository.AttachmentUploader
import com.example.qgent.data.repository.ChatRepository
import com.example.qgent.databinding.BottomSheetMentionMemberBinding
import com.example.qgent.databinding.DialogImagePreviewBinding
import com.example.qgent.databinding.FragmentChatDetailBinding
import com.example.qgent.model.ChatMessage
import com.example.qgent.model.GroupMember
import com.example.qgent.model.MessageType
import com.example.qgent.viewmodel.MainViewModel
import com.google.android.material.bottomsheet.BottomSheetDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

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

    private val messages = mutableListOf<ChatMessage>()
    private lateinit var rows: MutableList<ChatRow>
    private lateinit var adapter: ChatMessageAdapter

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

    private var groupMembers = emptyList<GroupMember>()

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
            onImageClick = { uri -> showImagePreview(uri) }
        )
        binding.rvMessages.layoutManager = LinearLayoutManager(requireContext())
        binding.rvMessages.adapter = adapter

        loadInitialData()
    }

    private fun sendTextMessage() {
        val text = binding.etInput.text.toString().trim()
        if (text.isEmpty()) return
        binding.etInput.text.clear()

        val projectId = mainViewModel.currentProjectId()
        val groupId = arguments?.getString("groupId").orEmpty()

        if (projectId != null && groupId.isNotEmpty()) {
            viewLifecycleOwner.lifecycleScope.launch {
                // Idempotency-Key 后端强制要求，防重复提交；每次发送都是全新消息，生成新 UUID
                chatRepo.sendMessage(projectId, groupId, "TEXT", MessageContentDto(text = text), idempotencyKey = UUID.randomUUID().toString()).onSuccess { dto ->
                    appendMessage(dto.toChatMessage(SessionStore.user()?.id, memberNamesById))
                }.onFailure {
                    appendLocalMessage(text)
                }
            }
        } else {
            appendLocalMessage(text)
        }
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
                    chatRepo.sendMessage(projectId, groupId, type, content, idempotencyKey = UUID.randomUUID().toString())
                        .onSuccess { dto ->
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

    private fun appendLocalMessage(text: String) {
        appendMessage(
            ChatMessage(
                UUID.randomUUID().toString(),
                "我",
                text,
                MessageType.TEXT,
                System.currentTimeMillis(),
                true
            )
        )
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
        previewBinding.previewRoot.setOnClickListener { dialog.dismiss() }
        dialog.show()
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
        messages.add(message)
        val start = rows.size
        val prevTime = messages.getOrNull(messages.size - 2)?.timestamp
        if (prevTime == null || message.timestamp - prevTime > TIME_GAP_MS) {
            rows.add(ChatRow.Time(formatTime(message.timestamp)))
        }
        rows.add(ChatRow.Message(message))
        adapter.notifyItemRangeInserted(start, rows.size - start)
        scrollToBottom()
        // 发送后立即落缓存，避免退出重进后新消息丢失
        val groupId = arguments?.getString("groupId").orEmpty()
        if (groupId.isNotEmpty()) {
            viewLifecycleOwner.lifecycleScope.launch { messageCache.save(groupId, messages) }
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
                groupMembers = dtos.map { it.toGroupMember() }
                memberNamesById = dtos.associate { it.id to it.resolvedName }
            }

            val messagesResult = messagesDeferred.await()
            val myId = SessionStore.user()?.id
            messagesResult.onSuccess { dtos ->
                Log.d("ChatDetail", "getMessages raw: $dtos")
                val list = dtos.map { it.toChatMessage(myId, memberNamesById) }
                // 与本地缓存合并去重（保留刚发送但后端可能尚未返回的消息），再按时间升序
                val merged = (cached + list).distinctBy { it.id }.sortedBy { it.timestamp }
                setMessages(merged)
                messageCache.save(groupId, merged)
            }.onFailure {
                // 网络失败但已有缓存时保留缓存显示，不清空
                if (messages.isEmpty()) setMessages(emptyList())
            }
        }
    }

    private fun setMessages(newMessages: List<ChatMessage>) {
        messages.clear()
        messages.addAll(newMessages)
        rows.clear()
        rows.addAll(buildRows(messages))
        adapter.notifyDataSetChanged()
        scrollToBottom()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        binding.etInput.removeTextChangedListener(mentionWatcher)
        _binding = null
    }

    companion object {
        private const val TIME_GAP_MS = 5 * 60 * 1000L
    }
}
