package com.example.qgent.ui.chat

import android.view.Gravity
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.example.qgent.R
import com.example.qgent.databinding.ItemMessageBinding
import com.example.qgent.databinding.ItemMessageTimeBinding
import com.example.qgent.model.ChatMessage
import com.example.qgent.model.MessageType

sealed class ChatRow {
    data class Time(val text: String) : ChatRow()
    data class Message(val message: ChatMessage) : ChatRow()
}

class ChatMessageAdapter(
    private val rows: List<ChatRow>,
    private val onAvatarLongClick: ((String) -> Unit)? = null,
    private val onImageClick: ((String) -> Unit)? = null
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    override fun getItemViewType(position: Int): Int =
        if (rows[position] is ChatRow.Time) TYPE_TIME else TYPE_MESSAGE

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return if (viewType == TYPE_TIME) {
            TimeVH(ItemMessageTimeBinding.inflate(LayoutInflater.from(parent.context), parent, false))
        } else {
            MessageVH(
                ItemMessageBinding.inflate(LayoutInflater.from(parent.context), parent, false),
                onAvatarLongClick,
                onImageClick
            )
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val row = rows[position]) {
            is ChatRow.Time -> (holder as TimeVH).binding.tvTime.text = row.text
            is ChatRow.Message -> (holder as MessageVH).bind(row.message)
        }
    }

    override fun getItemCount(): Int = rows.size

    class TimeVH(val binding: ItemMessageTimeBinding) : RecyclerView.ViewHolder(binding.root)

    class MessageVH(
        private val binding: ItemMessageBinding,
        private val onAvatarLongClick: ((String) -> Unit)?,
        private val onImageClick: ((String) -> Unit)?
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(message: ChatMessage) {
            val mine = message.isMine
            val isImage = message.type == MessageType.IMAGE
            binding.rowContainer.gravity = if (mine) Gravity.END else Gravity.START
            binding.rowInner.gravity = if (mine) Gravity.END else Gravity.START
            binding.ivAvatar.isVisible = !mine
            binding.tvSenderName.isVisible = !mine
            binding.tvSenderName.text = message.senderName

            binding.tvBubble.isVisible = !isImage
            binding.ivBubbleImage.isVisible = isImage
            if (isImage) {
                // 图片消息无气泡背景
                binding.flBubble.background = null
                bindImage(message.content)
            } else {
                binding.flBubble.setBackgroundResource(
                    if (mine) R.drawable.bg_bubble_mine else R.drawable.bg_bubble_other
                )
                binding.tvBubble.text = message.displayContent()
                binding.tvBubble.setTextColor(
                    ContextCompat.getColor(
                        binding.root.context,
                        if (mine) R.color.white else R.color.text_primary
                    )
                )
            }
            binding.ivAvatar.setOnLongClickListener {
                onAvatarLongClick?.invoke(message.senderName)
                true
            }
        }

        /** 图片消息：宽为屏幕宽度的一半，高度按宽高比自适应，完整显示 */
        private fun bindImage(uri: String) {
            val view = binding.ivBubbleImage
            // 清掉复用残留的旧图
            view.setImageDrawable(null)
            val density = view.resources.displayMetrics.density
            val halfScreenW = (view.resources.displayMetrics.widthPixels / 2f / density).toInt()
            val lp = view.layoutParams
            lp.width = halfScreenW
            lp.height = ViewGroup.LayoutParams.WRAP_CONTENT
            view.layoutParams = lp
            // adjustViewBounds 会根据加载到的图片比例自动算高度
            Glide.with(view).load(uri).into(view)
            view.setOnClickListener { onImageClick?.invoke(uri) }
        }
    }

    companion object {
        private const val TYPE_TIME = 0
        private const val TYPE_MESSAGE = 1
    }
}
