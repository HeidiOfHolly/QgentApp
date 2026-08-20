package com.example.qgent.ui.delivery

import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.example.qgent.R
import com.example.qgent.data.model.DeliveryItemDto
import com.example.qgent.data.model.DeliveryRepositoryDeliveryDto
import com.example.qgent.databinding.ItemDeliveryCardBinding

/** 交付物卡片的操作回调，由使用方（交付中心 / 交付物列表页）实现 */
interface DeliveryItemAction {
    fun onViewDiff(diffId: String)
    fun onConfirm(item: DeliveryItemDto)
    fun onReject(item: DeliveryItemDto)
    fun onRetry(item: DeliveryItemDto)
}

/** 交付物卡片构建：交付中心与交付物列表页共用同一卡片渲染与操作（布局 item_delivery_card） */
object DeliveryItemCardBuilder {

    /** 构建单张交付物卡片；点击卡片跳转交付物详情 */
    fun build(context: android.content.Context, item: DeliveryItemDto, action: DeliveryItemAction, onOpenTask: (DeliveryItemDto) -> Unit): View {
        val binding = ItemDeliveryCardBinding.inflate(android.view.LayoutInflater.from(context))

        binding.root.setOnClickListener { onOpenTask(item) }

        // 标题：任务展示码 + 标题
        val title = item.source?.taskDisplayCode?.let { "($it) " }.orEmpty() + item.title
        binding.tvTitle.text = title

        // 仓库/分支
        val repos = item.repositories?.joinToString("、") { "${it.name} / ${it.branch}" }.orEmpty()
        if (repos.isBlank()) {
            binding.tvRepos.visibility = View.GONE
        } else {
            binding.tvRepos.text = "📦 $repos"
        }


        // Review / Delivery 状态
        val review = item.reviewStatus ?: "-"
        val delivery = item.deliveryStatus ?: "-"
        binding.tvReviewDelivery.text = "Review $review · Delivery $delivery"

        // 逐仓库交付状态
        fillRepos(binding, item.repositoryDeliveries.orEmpty())

        // 操作行（按 capabilities 显示）
        fillActions(binding, item, action)

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
            val row = TextView(binding.root.context).apply {
                text = "• ${rd.repositoryName ?: rd.repositoryId ?: "仓库"}：$status$reason"
                textSize = 12f
                setTextColor(binding.root.context.getColor(R.color.text_primary))
                setPadding(0, dp(binding.root.context, 2), 0, dp(binding.root.context, 2))
            }
            binding.containerRepos.addView(row)
        }
    }

    private fun fillActions(binding: ItemDeliveryCardBinding, item: DeliveryItemDto, action: DeliveryItemAction) {
        val container = binding.containerActions
        container.removeAllViews()
        val caps = item.capabilities

        fun actionButton(text: String, onClick: () -> Unit): TextView =
            TextView(binding.root.context).apply {
                this.text = text
                setTextColor(binding.root.context.getColor(R.color.primary))
                textSize = 13f
                setPadding(dp(binding.root.context, 10), dp(binding.root.context, 6), dp(binding.root.context, 10), dp(binding.root.context, 6))
                setOnClickListener { onClick() }
            }
        // 查看 Diff：同项目所有成员可见（后端 GET /diffs/{id}/files 权限=项目成员），
        // 不依赖 canOpenResource 能力位（后端对部分成员返回 null 会误隐藏查看入口）
        if (!item.diffId.isNullOrBlank()) {
            container.addView(actionButton("查看 Diff") { action.onViewDiff(item.diffId!!) })
        }
        if (caps?.canApprove == true && !item.source?.taskId.isNullOrBlank()) {
            container.addView(actionButton("确认交付") { action.onConfirm(item) })
        }
        if (caps?.canReject == true && !item.source?.taskId.isNullOrBlank()) {
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
