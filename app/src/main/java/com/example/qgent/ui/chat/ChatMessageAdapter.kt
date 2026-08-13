package com.example.qgent.ui.chat

import android.view.Gravity
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.example.qgent.R
import com.example.qgent.databinding.ItemMessageBinding
import com.example.qgent.databinding.ItemMessageDiffBinding
import com.example.qgent.databinding.ItemMessageTimeBinding
import com.example.qgent.model.ChatMessage
import com.example.qgent.model.MessageType

sealed class ChatRow {
    data class Time(val text: String) : ChatRow()
    data class Message(val message: ChatMessage) : ChatRow()
}

class ChatMessageAdapter(
    private val rows: List<ChatRow>,
    private val onAvatarLongClick: ((String) -> Unit)? = null
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    override fun getItemViewType(position: Int): Int = when (val row = rows[position]) {
        is ChatRow.Time -> TYPE_TIME
        is ChatRow.Message -> if (row.message.type == MessageType.DIFF) TYPE_DIFF else TYPE_MESSAGE
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return when (viewType) {
            TYPE_TIME -> TimeVH(ItemMessageTimeBinding.inflate(LayoutInflater.from(parent.context), parent, false))
            TYPE_DIFF -> DiffVH(ItemMessageDiffBinding.inflate(LayoutInflater.from(parent.context), parent, false))
            else -> MessageVH(ItemMessageBinding.inflate(LayoutInflater.from(parent.context), parent, false), onAvatarLongClick)
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val row = rows[position]) {
            is ChatRow.Time -> (holder as TimeVH).binding.tvTime.text = row.text
            is ChatRow.Message -> {
                if (row.message.type == MessageType.DIFF) {
                    (holder as DiffVH).bind(row.message)
                } else {
                    (holder as MessageVH).bind(row.message)
                }
            }
        }
    }

    override fun getItemCount(): Int = rows.size

    class TimeVH(val binding: ItemMessageTimeBinding) : RecyclerView.ViewHolder(binding.root)

    class MessageVH(private val binding: ItemMessageBinding, private val onAvatarLongClick: ((String) -> Unit)?) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(message: ChatMessage) {
            val mine = message.isMine
            binding.rowContainer.gravity = if (mine) Gravity.END else Gravity.START
            binding.rowInner.gravity = if (mine) Gravity.END else Gravity.START
            binding.ivAvatar.isVisible = !mine
            binding.tvSenderName.isVisible = !mine
            binding.tvSenderName.text = message.senderName
            binding.tvBubble.text = message.displayContent()
            binding.tvBubble.setBackgroundResource(
                if (mine) R.drawable.bg_bubble_mine else R.drawable.bg_bubble_other
            )
            binding.tvBubble.setTextColor(
                ContextCompat.getColor(
                    binding.root.context,
                    if (mine) R.color.white else R.color.text_primary
                )
            )
            binding.ivAvatar.setOnLongClickListener {
                onAvatarLongClick?.invoke(message.senderName)
                true
            }
        }
    }

    class DiffVH(private val binding: ItemMessageDiffBinding) : RecyclerView.ViewHolder(binding.root) {

        private val pagerAdapter = DiffFilePagerAdapter()

        init {
            binding.viewPagerDiff.adapter = pagerAdapter
            binding.viewPagerDiff.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
                override fun onPageSelected(position: Int) = updateHeader(position)
            })
        }

        fun bind(message: ChatMessage) {
            val files = message.diff ?: emptyList()
            pagerAdapter.submitList(files)
            binding.tvSenderName.text = message.senderName
            if (files.isNotEmpty()) {
                binding.viewPagerDiff.setCurrentItem(0, false)
                updateHeader(0)
            }
        }

        private fun updateHeader(position: Int) {
            val file = pagerAdapter.fileAt(position) ?: return
            binding.tvDiffFileName.text = file.fileName
            binding.tvDiffStats.text = "+${file.additions} -${file.deletions}"
            binding.tvDiffIndicator.text = "${position + 1}/${pagerAdapter.count}"
        }
    }

    companion object {
        private const val TYPE_TIME = 0
        private const val TYPE_MESSAGE = 1
        private const val TYPE_DIFF = 2
    }
}
