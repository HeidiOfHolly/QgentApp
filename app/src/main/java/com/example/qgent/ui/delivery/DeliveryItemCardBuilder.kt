package com.example.qgent.ui.delivery

import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.isVisible
import com.example.qgent.R
import com.example.qgent.data.model.DeliveryItemDto
import com.example.qgent.data.model.DeliveryRepositoryDeliveryDto
import com.example.qgent.databinding.ItemDeliveryCardBinding

/** 交付物卡片的操作回调，由使用方（交付中心 / 交付物列表页）实现 */
interface DeliveryItemAction {
    fun onViewDiff(item: DeliveryItemDto)
    fun onConfirm(item: DeliveryItemDto)
    fun onReject(item: DeliveryItemDto)
    fun onRetry(item: DeliveryItemDto)
    /** 交付被拒绝（REJECTED）：回需求群引用 DIFF 根据拒绝意见继续修改 */
    fun onContinueModify(item: DeliveryItemDto) {}
}

/** 交付物卡片构建：交付中心与交付物列表页共用同一卡片渲染与操作（布局 item_delivery_card） */
object DeliveryItemCardBuilder {

    /** 构建单张交付物卡片；点击卡片跳转交付物详情。
     *  [canApprove]/[canReject]：客户端自判的交付确认/拒绝权限（null 时沿用后端能力位）。
     *  仅任务创建者或项目管理员可确认/拒绝（DeliveryPermission）。 */
    fun build(
        context: android.content.Context,
        item: DeliveryItemDto,
        action: DeliveryItemAction,
        onOpenTask: (DeliveryItemDto) -> Unit,
        canApprove: Boolean? = null,
        canReject: Boolean? = null
    ): View {
        val binding = ItemDeliveryCardBinding.inflate(android.view.LayoutInflater.from(context))

        binding.root.setOnClickListener { onOpenTask(item) }

        // 标题：任务展示码 + 标题
        val title = item.source?.taskDisplayCode?.let { "($it) " }.orEmpty() + item.title
        binding.tvTitle.text = title

        // 是否已创建 MR 标签（mergeRequest 非空 = 已创建，§20.1）；保持 bg_status_tag 圆角，用 tint 染色
        val hasMr = item.mergeRequest != null
        binding.tvMrLabel.isVisible = true
        binding.tvMrLabel.text = if (hasMr) "已创建 MR" else "未创建 MR"
        binding.tvMrLabel.backgroundTintList = android.content.res.ColorStateList.valueOf(
            androidx.core.content.ContextCompat.getColor(context,
                if (hasMr) R.color.teal else R.color.status_yellow)
        )

        // 仓库/分支
        val repos = item.repositories?.joinToString("、") { "${it.name} / ${it.branch}" }.orEmpty()
        if (repos.isBlank()) {
            binding.tvRepos.visibility = View.GONE
        } else {
            binding.tvRepos.text = "📦 $repos"
        }


        // Review / Delivery 状态；交付被拒绝（REJECTED）展示拒绝意见；
        // diff 未确认（reviewStatus 非 ACCEPTED）时不显示"是否交付"（Delivery 状态无意义）
        val review = item.reviewStatus ?: "-"
        val delivery = item.deliveryStatus ?: "-"
        binding.tvReviewDelivery.text = when {
            item.reviewStatus == "REJECTED" -> {
                val reason = item.reviewReason?.takeIf { it.isNotBlank() }
                if (reason != null) "已拒绝：$reason" else "已拒绝"
            }
            item.reviewStatus == "ACCEPTED" -> "Review $review · Delivery $delivery"
            else -> "Review $review"   // diff 未确认，不显示是否交付
        }

        // 逐仓库交付状态
        fillRepos(binding, item.repositoryDeliveries.orEmpty())

        // 操作行（按 capabilities + 客户端自判权限显示）
        fillActions(binding, item, action, canApprove, canReject)

        // inflate(root=null) 不生成根视图 LayoutParams，layout_marginBottom 会丢失；
        // 显式设置（与 item_delivery_card.xml 根 layout_marginBottom 一致），否则卡片间距不生效
        binding.root.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(context, CARD_BOTTOM_MARGIN_DP) }

        return binding.root
    }

    private fun fillRepos(binding: ItemDeliveryCardBinding, repos: List<DeliveryRepositoryDeliveryDto>) {
        binding.containerRepos.removeAllViews()
        repos.forEach { rd ->
            val status = rd.deliveryStatus ?: "-"
            val reason = rd.failureReason?.takeIf { it.isNotBlank() }?.let { "（$it）" }.orEmpty()
            val row = android.view.LayoutInflater.from(binding.root.context)
                .inflate(R.layout.item_delivery_repo_row, binding.containerRepos, false) as TextView
            row.text = "• ${rd.repositoryName ?: rd.repositoryId ?: "仓库"}：$status$reason"
            binding.containerRepos.addView(row)
        }
    }

    private fun fillActions(
        binding: ItemDeliveryCardBinding,
        item: DeliveryItemDto,
        action: DeliveryItemAction,
        canApproveOverride: Boolean?,
        canRejectOverride: Boolean?
    ) {
        val container = binding.containerActions
        container.removeAllViews()
        val caps = item.capabilities

        fun actionButton(text: String, onClick: () -> Unit): TextView {
            val btn = android.view.LayoutInflater.from(binding.root.context)
                .inflate(R.layout.item_delivery_action_btn, container, false) as TextView
            btn.text = text
            btn.setOnClickListener { onClick() }
            return btn
        }
        // 查看 Diff：同项目所有成员可见（后端 GET /diffs/{id}/files 权限=项目成员），
        // 不依赖 canOpenResource 能力位（后端对部分成员返回 null 会误隐藏查看入口）
        if (!item.diffId.isNullOrBlank()) {
            container.addView(actionButton("查看 Diff") { action.onViewDiff(item) })
        }
        // 交付被拒绝（REJECTED）：隐藏确认/拒绝，提供「回群继续修改」入口
        if (item.reviewStatus == "REJECTED") {
            if (item.requirementGroup?.id != null) {
                container.addView(actionButton("回群继续修改") { action.onContinueModify(item) })
            }
            return
        }
        // diff 已确认（ACCEPTED）：不再显示确认交付/拒绝入口（已确认的不再重复提示）
        if (item.reviewStatus == "ACCEPTED") {
            return
        }
        // 确认/拒绝：客户端自判（仅创建者或管理员）优先，缺省沿用后端能力位（已按同一规则派生）
        val canApprove = canApproveOverride ?: (caps?.canApprove == true)
        val canReject = canRejectOverride ?: (caps?.canReject == true)
        if (canApprove && !item.source?.taskId.isNullOrBlank()) {
            container.addView(actionButton("确认交付") { action.onConfirm(item) })
        }
        if (canReject && !item.source?.taskId.isNullOrBlank()) {
            container.addView(actionButton("拒绝") { action.onReject(item) })
        }
        if (caps?.canRetryDelivery == true && !item.source?.taskId.isNullOrBlank()) {
            container.addView(actionButton("重试交付") { action.onRetry(item) })
        }
    }

    private fun dp(context: android.content.Context, value: Int): Int = (value * context.resources.displayMetrics.density).toInt()

    /** 卡片底部间距（dp），与 item_delivery_card.xml 根 layout_marginBottom 保持一致 */
    private const val CARD_BOTTOM_MARGIN_DP = 16
}
