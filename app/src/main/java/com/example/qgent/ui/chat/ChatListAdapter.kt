package com.example.qgent.ui.chat

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import com.example.qgent.databinding.ItemChatBinding
import com.example.qgent.model.ChatGroup

class ChatListAdapter(
    private val items: List<ChatGroup>,
    private val onGroupClick: (ChatGroup) -> Unit
) : RecyclerView.Adapter<ChatListAdapter.VH>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemChatBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(binding)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(items[position])
        holder.itemView.setOnClickListener { onGroupClick(items[position]) }
    }

    override fun getItemCount(): Int = items.size

    class VH(private val binding: ItemChatBinding) : RecyclerView.ViewHolder(binding.root) {

        fun bind(group: ChatGroup) {
            binding.tvName.text = group.name
            binding.tvLastMessage.text = group.lastMessage
            binding.tvTime.text = group.time
            binding.tvUnread.isVisible = group.unread > 0
            binding.tvUnread.text = if (group.unread > 99) "99+" else group.unread.toString()
        }
    }
}
