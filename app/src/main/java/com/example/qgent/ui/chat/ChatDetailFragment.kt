package com.example.qgent.ui.chat
import android.app.Dialog
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
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
import com.example.qgent.QgentApp
import com.example.qgent.R
import com.example.qgent.data.SessionStore
import com.example.qgent.data.model.toChatMessage
import com.example.qgent.data.model.toGroupMember
import com.example.qgent.data.repository.ChatRepository
import com.example.qgent.databinding.BottomSheetMentionMemberBinding
import com.example.qgent.databinding.DialogImagePreviewBinding
import com.example.qgent.databinding.FragmentChatDetailBinding
import com.example.qgent.model.ChatMessage
import com.example.qgent.model.GroupMember
import com.example.qgent.model.MessageType
import com.example.qgent.viewmodel.MainViewModel
import com.google.android.material.bottomsheet.BottomSheetDialog
import kotlinx.coroutines.launch
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

    private val messages = mutableListOf<ChatMessage>()
    private lateinit var rows: MutableList<ChatRow>
    private lateinit var adapter: ChatMessageAdapter

    // 系统相册选图：免存储权限，返回图片 content:// URI
    private val pickImage = registerForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            appendMessage(
                ChatMessage(
                    UUID.randomUUID().toString(),
                    "我",
                    uri.toString(),
                    MessageType.IMAGE,
                    System.currentTimeMillis(),
                    true
                )
            )
        }
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
                chatRepo.sendMessage(projectId, groupId, text).onSuccess { dto ->
                    appendMessage(dto.toChatMessage(SessionStore.user()?.id, memberNamesById))
                }.onFailure {
                    appendLocalMessage(text)
                }
            }
        } else {
            appendLocalMessage(text)
        }
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
        Glide.with(previewBinding.ivPreview).load(uri).into(previewBinding.ivPreview)
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
                Toast.makeText(requireContext(), R.string.todo_placeholder, Toast.LENGTH_SHORT).show()
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

        if (projectId != null && groupId.isNotEmpty()) {
            viewLifecycleOwner.lifecycleScope.launch {
                // 先取成员表再拉消息，保证 senderName 能按 senderId 反查（顺序 await）
                chatRepo.getMembers(projectId, groupId).onSuccess { dtos ->
                    groupMembers = dtos.map { it.toGroupMember() }
                    memberNamesById = dtos.associate { it.id to (it.nickname ?: "成员") }
                }
                chatRepo.getMessages(projectId, groupId).onSuccess { dtos ->
                    val myId = SessionStore.user()?.id
                    setMessages(dtos.map { it.toChatMessage(myId, memberNamesById) })
                }.onFailure {
                    setMessages(emptyList())
                }
            }
        } else {
            setMessages(emptyList())
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
