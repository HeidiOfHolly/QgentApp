package com.example.qgent.ui.chat

import android.view.Gravity
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.bumptech.glide.Glide
import com.bumptech.glide.load.model.GlideUrl
import com.bumptech.glide.load.model.LazyHeaders
import com.example.qgent.R
import com.example.qgent.data.SessionStore
import com.example.qgent.data.api.RetrofitClient
import com.example.qgent.databinding.ItemMessageBinding
import com.example.qgent.databinding.ItemMessageDiffBinding
import com.example.qgent.databinding.ItemMessageTimeBinding
import com.example.qgent.model.ChatMessage
import com.example.qgent.model.MessageType
import java.util.Locale

sealed class ChatRow {
    data class Time(val text: String) : ChatRow()
    data class Message(val message: ChatMessage) : ChatRow()
}

class ChatMessageAdapter(
    private val rows: List<ChatRow>,
    private val onAvatarLongClick: ((String) -> Unit)? = null,
    private val onImageClick: ((String) -> Unit)? = null,
    private val onFileClick: ((ChatMessage) -> Unit)? = null
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    override fun getItemViewType(position: Int): Int = when (val row = rows[position]) {
        is ChatRow.Time -> TYPE_TIME
        is ChatRow.Message -> if (row.message.type == MessageType.DIFF) TYPE_DIFF else TYPE_MESSAGE
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return when (viewType) {
            TYPE_TIME -> TimeVH(ItemMessageTimeBinding.inflate(LayoutInflater.from(parent.context), parent, false))
            TYPE_DIFF -> DiffVH(ItemMessageDiffBinding.inflate(LayoutInflater.from(parent.context), parent, false))
            else -> MessageVH(
                ItemMessageBinding.inflate(LayoutInflater.from(parent.context), parent, false),
                onAvatarLongClick,
                onImageClick,
                onFileClick
            )
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

    class MessageVH(
        private val binding: ItemMessageBinding,
        private val onAvatarLongClick: ((String) -> Unit)?,
        private val onImageClick: ((String) -> Unit)?,
        private val onFileClick: ((ChatMessage) -> Unit)?
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(message: ChatMessage) {
            val mine = message.isMine
            val isImage = message.type == MessageType.IMAGE
            val isFile = message.type == MessageType.FILE
            binding.rowContainer.gravity = if (mine) Gravity.END else Gravity.START
            binding.rowInner.gravity = if (mine) Gravity.END else Gravity.START
            binding.ivAvatar.isVisible = !mine
            binding.tvSenderName.isVisible = !mine
            binding.tvSenderName.text = message.senderName

            binding.tvBubble.isVisible = !isImage && !isFile
            binding.ivBubbleImage.isVisible = isImage
            binding.llBubbleFile.isVisible = isFile
            when {
                isImage -> {
                    // 图片消息无气泡背景
                    binding.flBubble.background = null
                    bindImage(message.content)
                }
                isFile -> {
                    binding.flBubble.setBackgroundResource(
                        if (mine) R.drawable.bg_bubble_mine else R.drawable.bg_bubble_other
                    )
                    binding.tvFileName.text = message.fileName ?: "[文件]"
                    binding.tvFileSize.text = formatFileSize(message.fileSize)
                    binding.llBubbleFile.setOnClickListener { onFileClick?.invoke(message) }
                }
                else -> {
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
            // content.url 是后端鉴权接口，Glide 需带 Bearer 头才能下载；adjustViewBounds 按比例自动算高
            Glide.with(view).load(authedGlideUrl(uri)).into(view)
            view.setOnClickListener { onImageClick?.invoke(uri) }
        }

        private fun authedGlideUrl(uri: String): GlideUrl {
            val token = SessionStore.accessToken()
            val headers = LazyHeaders.Builder().apply {
                if (!token.isNullOrEmpty()) addHeader("Authorization", "Bearer $token")
            }.build()
            return GlideUrl(RetrofitClient.resolveMediaUrl(uri), headers)
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

        private fun formatFileSize(size: Long?): String {
            if (size == null || size <= 0) return ""
            val kb = size / 1024.0
            return if (kb < 1024) {
                String.format(Locale.US, "%.1f KB", kb)
            } else {
                String.format(Locale.US, "%.1f MB", kb / 1024.0)
            }
        }
    }
}
