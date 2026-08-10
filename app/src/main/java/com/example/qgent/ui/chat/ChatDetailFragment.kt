package com.example.qgent.ui.chat

import android.os.Bundle
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
import com.example.qgent.databinding.FragmentChatDetailBinding
import com.example.qgent.model.ChatMessage
import com.example.qgent.model.MessageType
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

        // 键盘 / 手势栏 insets：输入栏底部避让，消息列表不被遮住
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            binding.inputBar.setPadding(0, 0, 0, bars.bottom + ime.bottom)
            insets
        }

        // 键盘上的发送键
        binding.etInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                sendTextMessage()
                true
            } else {
                false
            }
        }

        // mock 消息，数据接入后替换
        messages.addAll(mockMessages())
        rows = buildRows(messages).toMutableList()
        adapter = ChatMessageAdapter(rows)
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
    }

    /** 加号：图片 / 文件（占位消息） */
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
            if (prev == null || msg.timestamp - prev > TIME_GAP_MS) {
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
            ChatMessage("m1", "张三", "今天把登录接口联调一下", MessageType.TEXT, now - 20 * 60 * 1000, false),
            ChatMessage("m2", "李四", "好，我负责前端部分", MessageType.TEXT, now - 18 * 60 * 1000, false),
            ChatMessage("m3", "我", "后端接口文档我已经看过了", MessageType.TEXT, now - 17 * 60 * 1000, true),
            ChatMessage("m4", "张三", "RSA 密码加密记得注意一下", MessageType.TEXT, now - 3 * 60 * 1000, false),
            ChatMessage("m5", "我", "收到，按契约来", MessageType.TEXT, now - 2 * 60 * 1000, true)
        )
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        private const val TIME_GAP_MS = 5 * 60 * 1000L
    }
}
