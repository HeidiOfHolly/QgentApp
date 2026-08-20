package com.example.qgent.ui.chat

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import com.example.qgent.R
import com.example.qgent.databinding.ItemChatBinding
import com.example.qgent.model.ChatGroup
import com.example.qgent.model.GroupType

class ChatListAdapter(
    private var items: List<ChatGroup>,
    private val onGroupClick: (ChatGroup) -> Unit,
    private val onGroupLongClick: ((View, ChatGroup) -> Unit)? = null
) : RecyclerView.Adapter<ChatListAdapter.VH>() {

    fun submitList(newItems: List<ChatGroup>) {
        // 排序与 MainViewModel.sortGroups 一致：项目总群恒置顶 → 手动置顶 → 最新活跃倒序
        items = newItems.sortedWith(
            compareByDescending<ChatGroup> { it.type == GroupType.PROJECT_MAIN }
                .thenByDescending { it.isPinned }
                .thenByDescending { it.lastActiveTime }
        )
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemChatBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(binding)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(items[position])
        holder.itemView.setOnClickListener { onGroupClick(items[position]) }
        holder.itemView.setOnLongClickListener {
            onGroupLongClick?.invoke(it, items[position])
            true
        }
    }

    override fun getItemCount(): Int = items.size

    class VH(private val binding: ItemChatBinding) : RecyclerView.ViewHolder(binding.root) {

        fun bind(group: ChatGroup) {
            binding.tvName.text = group.name
            binding.tvTime.text = group.time
            // 群头像：成员头像拼图（loadGroups 已缓存成员头像）
            binding.groupAvatar.setAvatars(group.memberAvatars)
            // 项目总群标识（PROJECT_MAIN 恒置顶，标「总群」）
            binding.tvGroupTag.isVisible = group.type == GroupType.PROJECT_MAIN
            binding.tvUnread.isVisible = group.unread > 0
            binding.tvUnread.text = if (group.unread > 99) "99+" else group.unread.toString()
            val context = binding.root.context
            if (group.mentionedMe) {
                binding.tvLastMessage.text = context.getString(R.string.mentioned_me) + " " + group.lastMessage
                binding.tvLastMessage.setTextColor(ContextCompat.getColor(context, R.color.unread_badge))
            } else {
                binding.tvLastMessage.text = group.lastMessage
                binding.tvLastMessage.setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
            }
            // 总群（PROJECT_MAIN 默认置顶）与手动置顶群都显示置顶背景色
            val pinned = group.isPinned || group.type == GroupType.PROJECT_MAIN
            binding.root.setBackgroundColor(
                ContextCompat.getColor(
                    context,
                    if (pinned) R.color.bg_chat_pinned_row else android.R.color.transparent
                )
            )
        }
    }
}
