package com.example.qgent.ui.chat

import android.graphics.drawable.Drawable
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.bumptech.glide.Glide
import com.bumptech.glide.load.model.GlideUrl
import com.bumptech.glide.load.model.LazyHeaders
import com.bumptech.glide.request.target.CustomTarget
import com.bumptech.glide.request.transition.Transition
import com.example.qgent.R
import com.example.qgent.data.SessionStore
import com.example.qgent.data.api.RetrofitClient
import com.example.qgent.databinding.ItemMessageBinding
import com.example.qgent.databinding.ItemMessageDiffBinding
import com.example.qgent.databinding.ItemMessageSystemBinding
import com.example.qgent.databinding.ItemMessageTaskStatusBinding
import com.example.qgent.databinding.ItemMessageTimeBinding
import com.example.qgent.model.ChatMessage
import com.example.qgent.model.DiffFile
import com.example.qgent.model.MessageType
import com.example.qgent.model.SendState
import java.util.Locale

sealed class ChatRow {
    data class Time(val text: String) : ChatRow()
    data class Message(val message: ChatMessage) : ChatRow()
}

/**
 * 消息列表适配器。
 * 行类型：时间 / 普通消息（含图片/文件/引用）/ Diff / 系统提示（SYSTEM，居中灰色小字）。
 * 普通消息支持长按菜单（引用/复制/多选，由 [onMessageLongClick] 回调到 Fragment 弹出）。
 * Diff 行通过 [onLoadDiff] 异步拉取文件内容（真实接口优先，失败由数据层 mock 保底）。
 */
class ChatMessageAdapter(
    initialRows: List<ChatRow>,
    private val onAvatarLongClick: ((String) -> Unit)? = null,
    private val onImageClick: ((String) -> Unit)? = null,
    private val onFileClick: ((ChatMessage) -> Unit)? = null,
    private val onMessageLongClick: ((View, ChatMessage) -> Unit)? = null,
    private val onLoadDiff: ((String, (List<DiffFile>) -> Unit) -> Unit)? = null,
    private val onMessageClick: ((ChatMessage) -> Unit)? = null,
    private val onSendFailedClick: ((ChatMessage) -> Unit)? = null,
    private val onTaskStatusClick: ((ChatMessage) -> Unit)? = null,
    /** DIFF 卡点击 → 跳转 Diff 审核面板（§v1.9.4 A3） */
    private val onDiffCardClick: ((ChatMessage) -> Unit)? = null,
    /** DIFF 卡「完整 Diff」→ 查看当前选中文件；未选中时由调用方回退完整 Diff */
    private val onViewFullDiff: ((ChatMessage, DiffFile?) -> Unit)? = null
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    /** 当前行列表（构造快照；后续更新一律走 [submitList] 做 DiffUtil 增量更新） */
    private var items: List<ChatRow> = initialRows

    /**
     * 增量更新行列表：DiffUtil 对比新旧列表，只派发变化（新增/更新/删除）的行，
     * 未变行不重绑（图片不重载、布局不动）——替代全量 notifyDataSetChanged。
     * 时间行按文本、消息行按 messageId 判同一项；内容用 data class 全等比较。
     * ⚠️ 必须拷贝 newRows：调用方传入的是 Fragment 持有的 MutableList 引用，
     * 若直接引用赋值，外部 clear/addAll 会改掉 items（DiffUtil 的 old 列表），
     * 导致 diff 恒为 no-op、界面不刷新（如发送成功仍显示转圈）。
     */
    fun submitList(newRows: List<ChatRow>) {
        val old = items
        val diff = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
            override fun getOldListSize(): Int = old.size
            override fun getNewListSize(): Int = newRows.size
            override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
                val oldRow = old[oldItemPosition]
                val newRow = newRows[newItemPosition]
                return when {
                    oldRow is ChatRow.Time && newRow is ChatRow.Time -> oldRow.text == newRow.text
                    oldRow is ChatRow.Message && newRow is ChatRow.Message -> oldRow.message.id == newRow.message.id
                    else -> false
                }
            }

            override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean =
                old[oldItemPosition] == newRows[newItemPosition]
        })
        items = newRows.toList()
        diff.dispatchUpdatesTo(this)
    }

    /** 多选模式下被选中的消息 id（非多选模式为空集，不参与高亮） */
    private var selectedIds: Set<String> = emptySet()

    /** 是否多选模式：控制复选框显示 */
    private var multiSelectMode = false

    /** 群成员 id → 头像 URL（群成员接口返回，用于他人消息气泡旁展示） */
    private var memberAvatarById: Map<String, String> = emptyMap()

    /** 直达定位的目标消息 id：命中行整行高亮（通知点击直达被 @ 消息；由 Fragment 定时清除） */
    private var highlightMessageId: String? = null

    /** 设置目标消息高亮（null 清除）；配合 notifyDataSetChanged 重绘 */
    fun setHighlightMessageId(id: String?) {
        highlightMessageId = id
        notifyDataSetChanged()
    }

    /** 更新成员头像映射（成员表加载/刷新后调用） */
    fun setMemberAvatars(avatars: Map<String, String>) {
        memberAvatarById = avatars
        notifyDataSetChanged()
    }

    /** 更新选中集合（多选模式切换选中时调用） */
    fun setSelectedIds(ids: Set<String>) {
        selectedIds = ids
        notifyDataSetChanged()
    }

    /** 进入/退出多选模式：显示/隐藏复选框 */
    fun setMultiSelectMode(enabled: Boolean) {
        multiSelectMode = enabled
        notifyDataSetChanged()
    }

    override fun getItemViewType(position: Int): Int = when (val row = items[position]) {
        is ChatRow.Time -> TYPE_TIME
        is ChatRow.Message -> when (row.message.type) {
            MessageType.SYSTEM -> TYPE_SYSTEM
            MessageType.DIFF -> TYPE_DIFF
            MessageType.TASK_STATUS -> TYPE_TASK_STATUS
            else -> TYPE_MESSAGE
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return when (viewType) {
            TYPE_TIME -> TimeVH(ItemMessageTimeBinding.inflate(LayoutInflater.from(parent.context), parent, false))
            TYPE_DIFF -> DiffVH(
                ItemMessageDiffBinding.inflate(LayoutInflater.from(parent.context), parent, false),
                onLoadDiff,
                onMessageLongClick,
                onDiffCardClick,
                onViewFullDiff
            )
            TYPE_SYSTEM -> SystemVH(ItemMessageSystemBinding.inflate(LayoutInflater.from(parent.context), parent, false))
            TYPE_TASK_STATUS -> TaskStatusVH(
                ItemMessageTaskStatusBinding.inflate(LayoutInflater.from(parent.context), parent, false),
                onTaskStatusClick
            )
            else -> MessageVH(
                ItemMessageBinding.inflate(LayoutInflater.from(parent.context), parent, false),
                onAvatarLongClick,
                onImageClick,
                onFileClick,
                onMessageLongClick,
                onMessageClick,
                onSendFailedClick
            )
        }
    }


    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val row = items[position]
        when (row) {
            is ChatRow.Time -> (holder as TimeVH).binding.tvTime.text = row.text
            is ChatRow.Message -> when (row.message.type) {
                MessageType.SYSTEM -> (holder as SystemVH).bind(row.message)
                MessageType.DIFF -> (holder as DiffVH).bind(row.message)
                MessageType.TASK_STATUS -> (holder as TaskStatusVH).bind(row.message)
                else -> (holder as MessageVH).bind(
                    row.message,
                    selectedIds.contains(row.message.id),
                    multiSelectMode
                )
            }
        }
        // 直达定位高亮：命中目标消息的行铺一层浅色底（两分支都设置，保证回收复用后状态正确）
        val highlight = (row as? ChatRow.Message)?.message?.id?.let { it == highlightMessageId && it.isNotEmpty() } == true
        holder.itemView.setBackgroundColor(
            if (highlight) holder.itemView.context.getColor(R.color.message_highlight)
            else android.graphics.Color.TRANSPARENT
        )
    }

    override fun getItemCount(): Int = items.size

    class TimeVH(val binding: ItemMessageTimeBinding) : RecyclerView.ViewHolder(binding.root)

    /** 系统提示行：居中灰色小字（成员进群/退群等） */
    class SystemVH(private val binding: ItemMessageSystemBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(message: ChatMessage) {
            binding.tvSystemMessage.text = message.content
        }
    }

    /** 任务状态卡片行：状态标签 + 执行节点 + 说明（Agent 任务进度）。
     *  待办：senderType=SYSTEM 时（ORCHESTRATOR 缺失降级）展示"系统"，不读 senderId/Agent 详情。
     *  可点击：待确认 Diff 时点击弹确认详情（onTaskStatusClick，由 Fragment 提供）。 */
    class TaskStatusVH(
        private val binding: ItemMessageTaskStatusBinding,
        private val onTaskStatusClick: ((ChatMessage) -> Unit)?
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind(message: ChatMessage) {
            // senderType=SYSTEM：系统降级消息，标识"系统"（不读 senderId/头像/Agent 详情）
            val isSystem = message.senderType == "SYSTEM"
            binding.tvTaskStatus.text = if (isSystem) "系统" else (message.taskStatus ?: "运行中")
            binding.tvTaskNode.isVisible = !isSystem && !message.taskNode.isNullOrBlank()
            if (!isSystem) message.taskNode?.let { binding.tvTaskNode.text = it }
            binding.tvTaskMessage.isVisible = message.content.isNotBlank()
            binding.tvTaskMessage.text = message.content
            // 点击卡片 → 查看任务状态/Diff 确认详情
            binding.root.setOnClickListener { onTaskStatusClick?.invoke(message) }
        }
    }

    inner class MessageVH(
        private val binding: ItemMessageBinding,
        private val onAvatarLongClick: ((String) -> Unit)?,
        private val onImageClick: ((String) -> Unit)?,
        private val onFileClick: ((ChatMessage) -> Unit)?,
        private val onMessageLongClick: ((View, ChatMessage) -> Unit)?,
        private val onMessageClick: ((ChatMessage) -> Unit)? = null,
        private val onSendFailedClick: ((ChatMessage) -> Unit)? = null
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(message: ChatMessage, isSelected: Boolean, multiSelect: Boolean) {
            val mine = message.isMine
            val isImage = message.type == MessageType.IMAGE
            val isFile = message.type == MessageType.FILE
            val isAgent = message.senderType == "AGENT"
            binding.rowContainer.gravity = if (mine) Gravity.END else Gravity.START
            binding.rowInner.gravity = if (mine) Gravity.END else Gravity.START
            // 头像：他人显示在气泡左侧，自己显示在气泡右侧（运行时移动子 View；注意回收时恢复位置）
            if (mine) {
                binding.rowContainer.removeView(binding.ivAvatar)
                binding.rowContainer.addView(binding.ivAvatar)
            } else if (binding.rowContainer.indexOfChild(binding.ivAvatar) != 0) {
                binding.rowContainer.removeView(binding.ivAvatar)
                binding.rowContainer.addView(binding.ivAvatar, 0)
            }
            binding.ivAvatar.isVisible = true
            binding.tvSenderName.isVisible = !mine
            binding.tvSenderName.text = message.senderName
            // 头像与气泡间距：他人头像在左 → 右侧留 8dp；自己头像在右 → 左侧留 8dp
            val innerLp = binding.rowInner.layoutParams as ViewGroup.MarginLayoutParams
            innerLp.marginStart = if (mine) 0 else dp(8)
            binding.rowInner.layoutParams = innerLp
            val avatarLp = binding.ivAvatar.layoutParams as ViewGroup.MarginLayoutParams
            avatarLp.marginStart = if (mine) dp(8) else 0
            avatarLp.marginEnd = 0
            binding.ivAvatar.layoutParams = avatarLp
            // 头像内容：自己的消息用当前用户头像；Agent 用 Agent 图标；他人用群成员头像（缺省默认占位）
            when {
                mine -> {
                    val avatarUrl = SessionStore.user()?.avatarUrl
                    if (avatarUrl.isNullOrBlank()) {
                        binding.ivAvatar.setImageResource(R.drawable.ic_avatar_default)
                    } else {
                        Glide.with(binding.ivAvatar)
                            .load(RetrofitClient.resolveMediaUrl(avatarUrl))
                            .centerCrop()
                            .placeholder(R.drawable.ic_avatar_default)
                            .error(R.drawable.ic_avatar_default)
                            .into(binding.ivAvatar)
                    }
                }
                isAgent -> binding.ivAvatar.setImageResource(R.drawable.ic_nav_agent)
                else -> {
                    val memberAvatar = message.senderId?.let { memberAvatarById[it] }
                    if (memberAvatar.isNullOrBlank()) {
                        binding.ivAvatar.setImageResource(R.drawable.ic_avatar_default)
                    } else {
                        Glide.with(binding.ivAvatar)
                            .load(RetrofitClient.resolveMediaUrl(memberAvatar))
                            .centerCrop()
                            .placeholder(R.drawable.ic_avatar_default)
                            .error(R.drawable.ic_avatar_default)
                            .into(binding.ivAvatar)
                    }
                }
            }
            binding.tvAgentTag.isVisible = !mine && isAgent

            // 引用消息：气泡上方显示被引用摘要（摘要缺失时兜底文案，避免空条）
            binding.tvQuoteSummary.isVisible = message.replyToId != null
            binding.tvQuoteSummary.text = message.replyToSummary ?: "引用消息"

            binding.tvBubble.isVisible = !isImage && !isFile
            binding.ivBubbleImage.isVisible = isImage
            binding.llBubbleFile.isVisible = isFile
            when {
                isImage -> {
                    // 图片消息无气泡背景
                    binding.flBubble.background = null
                    bindImage(message)
                }
                isFile -> {
                    binding.flBubble.setBackgroundResource(
                        if (mine) R.drawable.bg_bubble_mine else R.drawable.bg_bubble_other
                    )
                    binding.tvFileName.text = message.fileName ?: "[文件]"
                    binding.tvFileSize.text = formatFileSize(message.fileSize)
                    binding.llBubbleFile.setOnClickListener { onFileClick?.invoke(message) }
                    // 文件气泡可点击（打开），长按同样弹菜单（引用/复制/多选），避免被点击消费
                    binding.llBubbleFile.setOnLongClickListener {
                        onMessageLongClick?.invoke(binding.root, message)
                        true
                    }
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
            // 发送状态：仅自己发送的消息显示（发送中 → 小加载标；失败 → 红色感叹号，点击重发/删除）
            val sendState = if (mine) message.sendState else null
            binding.pbSendLoading.isVisible = sendState == SendState.SENDING
            binding.tvSendFailed.isVisible = sendState == SendState.FAILED
            binding.tvSendFailed.setOnClickListener { onSendFailedClick?.invoke(message) }
            // 长按整行 → 引用/复制/多选菜单（系统提示行除外，由 viewType 隔离）
            binding.root.setOnLongClickListener {
                onMessageLongClick?.invoke(it, message)
                true
            }
            // 点击：多选模式下切换选中；普通模式走原有点击（图片/文件气泡内部已单独绑定）
            binding.root.setOnClickListener {
                onMessageClick?.invoke(message)
            }
            // 多选模式：显示复选框并反映勾选态；选中行加深背景
            binding.cbMultiSelect.isVisible = multiSelect
            binding.cbMultiSelect.isChecked = isSelected
            val bgRes = when {
                isSelected -> R.color.bg_chat_selected
                multiSelect -> R.color.bg_chat_multi_select
                else -> android.R.color.transparent
            }
            binding.root.setBackgroundColor(
                ContextCompat.getColor(binding.root.context, bgRes)
            )
        }

        /** 图片消息：宽为屏幕宽度的一半，高度按宽高比自适应，完整显示；加载中显示居中加载态。
         *  优先用签名预览 URL（契约 v0.1：/preview + 短期 token 在 query，无头直连）；
         *  无 previewUrl 时回退 content.url（后端鉴权接口，Glide 带 Bearer 头）。 */
        private fun bindImage(message: ChatMessage) {
            val view = binding.ivBubbleImage
            // 清掉复用残留的旧图
            view.setImageDrawable(null)
            val density = view.resources.displayMetrics.density
            val halfScreenW = (view.resources.displayMetrics.widthPixels / 2f / density).toInt()
            val lp = view.layoutParams
            lp.width = halfScreenW
            lp.height = ViewGroup.LayoutParams.WRAP_CONTENT
            view.layoutParams = lp
            // 加载中显示居中小加载标，加载完成/失败后隐藏
            val loading = binding.pbImageLoading
            loading.isVisible = true
            val uri = message.content
            val previewUrl = message.previewUrl?.takeIf { it.isNotBlank() }
            val loader = when {
                isLocalUri(uri) -> {
                    // 本地 content:// URI（发送中的乐观占位图）直接加载，无需鉴权头
                    Glide.with(view).load(uri)
                }
                previewUrl != null -> {
                    // 签名预览 URL：token 在 query，无需自定义头
                    Glide.with(view).load(RetrofitClient.resolveMediaUrl(previewUrl))
                }
                else -> {
                    // content.url 是后端鉴权接口，Glide 需带 Bearer 头才能下载
                    Glide.with(view).load(authedGlideUrl(uri))
                }
            }
            loader
                .placeholder(R.drawable.bg_chat_image_placeholder)
                .into(object : CustomTarget<Drawable>() {
                    override fun onResourceReady(resource: Drawable, transition: Transition<in Drawable>?) {
                        loading.isVisible = false
                        view.setImageDrawable(resource)
                    }
                    override fun onLoadCleared(placeholder: Drawable?) {
                        loading.isVisible = false
                    }

                    override fun onLoadFailed(errorDrawable: Drawable?) {
                        loading.isVisible = false
                        // 预览 URL 可能过期（token 15 分钟），失败回退 content.url 重试一次
                        if (previewUrl != null) {
                            view.setImageDrawable(null)
                            Glide.with(view).load(authedGlideUrl(uri))
                                .placeholder(R.drawable.bg_chat_image_placeholder)
                                .into(object : CustomTarget<Drawable>() {
                                    override fun onResourceReady(resource: Drawable, transition: Transition<in Drawable>?) {
                                        view.setImageDrawable(resource)
                                    }
                                    override fun onLoadCleared(placeholder: Drawable?) = Unit
                                    override fun onLoadFailed(errorDrawable: Drawable?) {
                                        android.util.Log.e("ChatImage", "bubble image load FAILED: ${RetrofitClient.resolveMediaUrl(uri)}")
                                    }
                                })
                        } else {
                            android.util.Log.e("ChatImage", "bubble image load FAILED: ${RetrofitClient.resolveMediaUrl(uri)}")
                        }
                    }
                })
            view.setOnClickListener { onImageClick?.invoke(previewUrl ?: uri) }
            // 图片可点击（全屏预览），长按同样弹菜单（引用/复制/多选），避免被点击消费
            view.setOnLongClickListener {
                onMessageLongClick?.invoke(binding.root, message)
                true
            }
        }

        private fun dp(value: Int): Int =
            (value * binding.root.resources.displayMetrics.density).toInt()

        private fun isLocalUri(uri: String): Boolean =
            uri.startsWith("content://") || uri.startsWith("file://")

        private fun authedGlideUrl(uri: String): GlideUrl {
            val token = SessionStore.accessToken()
            val headers = LazyHeaders.Builder().apply {
                if (!token.isNullOrEmpty()) addHeader("Authorization", "Bearer $token")
            }.build()
            return GlideUrl(RetrofitClient.resolveMediaUrl(uri), headers)
        }
    }

    class DiffVH(
        private val binding: ItemMessageDiffBinding,
        private val onLoadDiff: ((String, (List<DiffFile>) -> Unit) -> Unit)?,
        private val onMessageLongClick: ((View, ChatMessage) -> Unit)? = null,
        private val onDiffCardClick: ((ChatMessage) -> Unit)? = null,
        private val onViewFullDiff: ((ChatMessage, DiffFile?) -> Unit)? = null
    ) : RecyclerView.ViewHolder(binding.root) {

        private val pagerAdapter = DiffFilePagerAdapter()
        private var boundDiffId: String? = null

        init {
            binding.viewPagerDiff.adapter = pagerAdapter
            binding.viewPagerDiff.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
                override fun onPageSelected(position: Int) = updateHeader(position)
            })
        }

        fun bind(message: ChatMessage) {
            binding.tvSenderName.text = message.senderName
            // DIFF 卡标题（content.title）+ 总变更统计（content.additions/deletions；缺省待文件加载后汇总）
            binding.tvDiffTitle.text = message.diffTitle?.takeIf { it.isNotBlank() } ?: "代码变更"
            binding.tvDiffTotalStats.text = if (message.diffAdditions != null || message.diffDeletions != null) {
                "+${message.diffAdditions ?: 0} -${message.diffDeletions ?: 0}"
            } else {
                ""
            }
            val superseded = message.reviewStatus == "SUPERSEDED"
            binding.tvDiffStatusLine.isVisible = superseded
            binding.tvDiffStatusLine.text = if (superseded) {
                binding.root.context.getString(R.string.diff_review_superseded)
            } else {
                ""
            }
            // 操作行：Diff 审核（确认/拒绝/重试）/ 完整 Diff 全屏查看
            binding.tvActionReview.setOnClickListener { onDiffCardClick?.invoke(message) }
            binding.tvActionFull.setOnClickListener { onViewFullDiff?.invoke(message, selectedFile()) }
            // 点击卡片 → 全屏查看完整代码（可滑动，绿加红减）
            binding.diffCard.setOnClickListener { onViewFullDiff?.invoke(message, selectedFile()) }
            // 长按 → 引用/复制/多选（引用 DIFF 卡发起增量修改，B1）
            binding.root.setOnLongClickListener {
                onMessageLongClick?.invoke(it, message)
                true
            }
            // 卡片/操作行本身可点击 → 长按被子 view 消费不冒泡，需显式转发同一菜单
            val cardLongClick = View.OnLongClickListener {
                onMessageLongClick?.invoke(binding.root, message)
                true
            }
            binding.diffCard.setOnLongClickListener(cardLongClick)
            binding.tvActionReview.setOnLongClickListener(cardLongClick)
            binding.tvActionFull.setOnLongClickListener(cardLongClick)
            // 优先用内存中已有的 diff；否则按 diffId 异步拉取（真实接口优先，失败 mock 保底）
            val diffId = message.diffId
            val files = message.diff
            boundDiffId = diffId
            if (!files.isNullOrEmpty()) {
                setPreviewLoading(false)
                render(files)
            } else if (!diffId.isNullOrBlank() && onLoadDiff != null) {
                clearPreview()
                setPreviewLoading(true)
                onLoadDiff?.let { load ->
                    load(diffId) callback@{ loaded ->
                        // RecyclerView 复用后，这个 ViewHolder 可能已经展示了另一张 Diff 卡。
                        if (boundDiffId != diffId) return@callback
                        setPreviewLoading(false)
                        render(loaded)
                    }
                }
            } else {
                setPreviewLoading(false)
                render(emptyList())
            }
        }

        private fun setPreviewLoading(loading: Boolean) {
            binding.diffLoading.isVisible = loading
            binding.diffPreview.isVisible = !loading
        }

        private fun clearPreview() {
            pagerAdapter.submitList(emptyList())
            binding.llFileChips.removeAllViews()
            binding.tvDiffFileName.text = ""
            binding.tvDiffStats.text = ""
            binding.tvDiffIndicator.text = ""
        }

        private fun render(files: List<DiffFile>) {
            pagerAdapter.submitList(files)
            renderChips(files)
            if (files.isNotEmpty()) {
                // content 未带统计时按已加载文件汇总
                if (binding.tvDiffTotalStats.text.isNullOrBlank()) {
                    binding.tvDiffTotalStats.text = "+${files.sumOf { it.additions }} -${files.sumOf { it.deletions }}"
                }
                binding.viewPagerDiff.setCurrentItem(0, false)
                updateHeader(0)
            }
        }

        /** 文件列表 chips：只显示 basename，点击跳转对应文件页（替代盲滑页码） */
        private fun renderChips(files: List<DiffFile>) {
            binding.llFileChips.removeAllViews()
            files.forEachIndexed { index, file ->
                val chip = TextView(binding.root.context).apply {
                    text = file.fileName.basename()
                    textSize = 11f
                    setTextColor(ContextCompat.getColor(binding.root.context, R.color.text_primary))
                    setBackgroundResource(R.drawable.bg_chip)
                    setPadding(dp(8), dp(3), dp(8), dp(3))
                    isClickable = true
                    isFocusable = true
                    maxWidth = dp(140)
                    ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
                    maxLines = 1
                }
                chip.layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { marginEnd = dp(6) }
                chip.setOnClickListener {
                    binding.viewPagerDiff.setCurrentItem(index, false)
                    updateHeader(index)
                }
                binding.llFileChips.addView(chip)
            }
        }

        private fun dp(value: Int): Int =
            (value * binding.root.resources.displayMetrics.density).toInt()

        private fun updateHeader(position: Int) {
            val file = pagerAdapter.fileAt(position) ?: return
            binding.tvDiffFileName.text = file.fileName.basename()
            binding.tvDiffStats.text = "+${file.additions} -${file.deletions}"
            binding.tvDiffIndicator.text = "${position + 1}/${pagerAdapter.count}"
        }

        private fun selectedFile(): DiffFile? =
            pagerAdapter.fileAt(binding.viewPagerDiff.currentItem)

        /** 文件名只显示 basename（去掉完整路径） */
        private fun String.basename(): String = substringAfterLast('/')
    }

    companion object {
        private const val TYPE_TIME = 0
        private const val TYPE_MESSAGE = 1
        private const val TYPE_DIFF = 2
        private const val TYPE_SYSTEM = 3
        private const val TYPE_TASK_STATUS = 4

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
