package com.example.qgent.ui.chat

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.PopupMenu
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.qgent.R
import com.example.qgent.databinding.BottomSheetMentionMemberBinding
import com.example.qgent.databinding.FragmentChatDetailBinding
import com.example.qgent.model.ChatMessage
import com.example.qgent.model.GroupMember
import com.example.qgent.model.MemberType
import com.example.qgent.model.MessageType
import com.google.android.material.bottomsheet.BottomSheetDialog
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

class ChatDetailFragment : Fragment() {

    private var _binding: FragmentChatDetailBinding? = null
    private val binding get() = _binding!!

    private val messages = mutableListOf<ChatMessage>()
    private lateinit var rows: MutableList<ChatRow>
    private lateinit var adapter: ChatMessageAdapter
    private val handler = Handler(Looper.getMainLooper())

    // 群成员（mock），含 AgentOrchestrator
    private val groupMembers = listOf(
        GroupMember("张三"),
        GroupMember("李四"),
        GroupMember("王五"),
        GroupMember("赵六"),
        GroupMember("AgentOrchestrator", MemberType.AGENT)
    )

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
            findNavController().navigate(R.id.action_chatDetail_to_chatSettings)
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

        messages.addAll(mockMessages())
        rows = buildRows(messages).toMutableList()
        adapter = ChatMessageAdapter(rows) { senderName ->
            insertMention(senderName)
        }
        binding.rvMessages.layoutManager = LinearLayoutManager(requireContext())
        binding.rvMessages.adapter = adapter
        scrollToBottom()
    }

    private fun sendTextMessage() {
        val text = binding.etInput.text.toString().trim()
        if (text.isEmpty()) return

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
        binding.etInput.text.clear()

        // 如果消息中 @ 了 AgentOrchestrator，模拟 Agent 回复
        if (text.contains("@AgentOrchestrator")) {
            handler.postDelayed({
                appendMessage(
                    ChatMessage(
                        UUID.randomUUID().toString(),
                        "AgentOrchestrator",
                        mockAgentReply(text),
                        MessageType.TEXT,
                        System.currentTimeMillis(),
                        false
                    )
                )
            }, 1500L)
        }
    }

    private fun mockAgentReply(userMessage: String): String {
        return when {
            userMessage.contains("登录") || userMessage.contains("注册") ->
                "收到，我来处理登录相关任务。已调度「前端开发」和「后端开发」Agent 协同工作。"
            userMessage.contains("部署") || userMessage.contains("发布") ->
                "部署任务已接收，正在调用「Docker 部署脚本」Skill 进行构建和推送。"
            userMessage.contains("审查") || userMessage.contains("review") ->
                "代码审查任务已启动，「代码审查」Agent 正在检查代码质量。"
            userMessage.contains("测试") || userMessage.contains("test") ->
                "测试任务已分配，「测试 Agent」正在编写和执行测试用例。"
            else ->
                "已收到你的指令，正在分析任务并分派给合适的 Agent 处理。"
        }
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
            val type = if (item.title == getString(R.string.image)) {
                MessageType.IMAGE
            } else {
                MessageType.FILE
            }
            appendMessage(
                ChatMessage(
                    UUID.randomUUID().toString(),
                    "我",
                    "",
                    type,
                    System.currentTimeMillis(),
                    true
                )
            )
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

    private fun mockMessages(): List<ChatMessage> {
        val now = System.currentTimeMillis()
        return listOf(
            ChatMessage("m1", "张三", "今天把登录接口联调一下", MessageType.TEXT, now - 25 * 60 * 1000, false),
            ChatMessage("m2", "李四", "好，我负责前端部分", MessageType.TEXT, now - 23 * 60 * 1000, false),
            ChatMessage("m3", "AgentOrchestrator", "我是 AgentOrchestrator，群里 @我 即可派发任务，我会调度 Agent 团队为你工作。", MessageType.TEXT, now - 21 * 60 * 1000, false),
            ChatMessage("m4", "我", "后端接口文档我已经看过了", MessageType.TEXT, now - 17 * 60 * 1000, true),
            ChatMessage("m5", "张三", "RSA 密码加密记得注意一下", MessageType.TEXT, now - 3 * 60 * 1000, false),
            ChatMessage("m6", "我", "收到，按契约来", MessageType.TEXT, now - 2 * 60 * 1000, true)
        )
    }

    override fun onDestroyView() {
        super.onDestroyView()
        handler.removeCallbacksAndMessages(null)
        binding.etInput.removeTextChangedListener(mentionWatcher)
        _binding = null
    }

    companion object {
        private const val TIME_GAP_MS = 5 * 60 * 1000L
    }
}
