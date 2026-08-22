package com.example.qgent.ui.search

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.example.qgent.R
import com.example.qgent.databinding.ItemLocalSearchBinding
import com.example.qgent.databinding.ItemLocalSearchGroupBinding
import com.example.qgent.model.ChatGroup
import com.example.qgent.model.ChatMessage
import com.example.qgent.model.GroupType
import com.example.qgent.data.model.formatGroupTime

/**
 * 本地搜索结果适配器（不依赖后端 /search）：
 * - 群匹配行：群名 + 类型标签（总群/需求群）
 * - 消息匹配行：群名 + 时间 + 发送者 + 内容（点击进群并定位到该消息）
 */
class LocalSearchAdapter(
    private val onGroupClick: (ChatGroup) -> Unit,
    private val onMessageClick: (groupName: String, groupId: String, message: ChatMessage) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    sealed class Row {
        data class GroupHit(val group: ChatGroup) : Row()
        data class MessageHit(val groupName: String, val groupId: String, val message: ChatMessage) : Row()
    }

    private var items: List<Row> = emptyList()

    fun submitList(newItems: List<Row>) {
        items = newItems
        notifyDataSetChanged()
    }

    override fun getItemViewType(position: Int): Int = when (items[position]) {
        is Row.GroupHit -> TYPE_GROUP
        is Row.MessageHit -> TYPE_MESSAGE
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder =
        if (viewType == TYPE_GROUP) {
            GroupVH(ItemLocalSearchGroupBinding.inflate(LayoutInflater.from(parent.context), parent, false))
        } else {
            MessageVH(ItemLocalSearchBinding.inflate(LayoutInflater.from(parent.context), parent, false))
        }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val row = items[position]) {
            is Row.GroupHit -> (holder as GroupVH).bind(row.group)
            is Row.MessageHit -> (holder as MessageVH).bind(row.groupName, row.groupId, row.message)
        }
    }

    override fun getItemCount(): Int = items.size

    inner class GroupVH(private val binding: ItemLocalSearchGroupBinding) :
        RecyclerView.ViewHolder(binding.root) {
        fun bind(group: ChatGroup) {
            binding.tvGroupName.text = group.name
            binding.tvGroupType.text = if (group.type == GroupType.PROJECT_MAIN) {
                binding.root.context.getString(R.string.group_tag_main)
            } else {
                binding.root.context.getString(R.string.group_tag_requirement)
            }
            binding.root.setOnClickListener { onGroupClick(group) }
        }
    }

    inner class MessageVH(private val binding: ItemLocalSearchBinding) :
        RecyclerView.ViewHolder(binding.root) {
        fun bind(groupName: String, groupId: String, message: ChatMessage) {
            binding.tvGroupName.text = groupName
            binding.tvTime.text = formatGroupTime(message.timestamp)
            binding.tvSender.text = message.senderName
            binding.tvContent.text = message.displayContent()
            binding.root.setOnClickListener { onMessageClick(groupName, groupId, message) }
        }
    }

    companion object {
        private const val TYPE_GROUP = 0
        private const val TYPE_MESSAGE = 1
    }
}
