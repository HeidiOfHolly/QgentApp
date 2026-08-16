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

class ChatListAdapter(
    private var items: List<ChatGroup>,
    private val onGroupClick: (ChatGroup) -> Unit,
    private val onGroupLongClick: ((View, ChatGroup) -> Unit)? = null
) : RecyclerView.Adapter<ChatListAdapter.VH>() {

    fun submitList(newItems: List<ChatGroup>) {
        items = newItems.sortedWith(
            compareByDescending<ChatGroup> { it.isPinned }
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
            binding.root.setBackgroundColor(
                ContextCompat.getColor(
                    context,
                    if (group.isPinned) R.color.bg_chat_pinned else android.R.color.transparent
                )
            )
        }
    }
}
