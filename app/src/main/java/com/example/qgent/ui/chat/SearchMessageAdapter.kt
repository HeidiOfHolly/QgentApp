package com.example.qgent.ui.chat

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.example.qgent.data.model.GroupMessageDto
import com.example.qgent.databinding.ItemSearchMessageBinding

/** 聊天记录搜索结果：展示匹配消息的发送者 + 文本内容 */
class SearchMessageAdapter : RecyclerView.Adapter<SearchMessageAdapter.VH>() {

    private var items = emptyList<GroupMessageDto>()

    fun submitList(newItems: List<GroupMessageDto>) {
        items = newItems
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemSearchMessageBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(binding)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    class VH(private val binding: ItemSearchMessageBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(message: GroupMessageDto) {
            binding.tvResultSender.text = message.senderName ?: "成员"
            binding.tvResultContent.text = message.content?.text ?: ""
        }
    }
}
