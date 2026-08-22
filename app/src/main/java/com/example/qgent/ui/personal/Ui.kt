package com.example.qgent.ui.personal

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.app.Dialog
import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.FrameLayout
import androidx.core.view.isVisible
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.qgent.R
import com.example.qgent.data.model.ApiException

/**
 * 接受团队邀请失败提示（§19.3）：
 * 网络错误 / 邀请已过期（INVITATION_EXPIRED）/ 已接受或已撤销（INVITATION_NOT_PENDING）/
 * 后端其余业务提示 / 通用兜底。
 */
fun joinTeamErrorMessage(context: Context, e: Throwable, fallback: String): String = when {
    e is java.io.IOException -> context.getString(R.string.join_team_network_error)
    e is ApiException -> when (e.code) {
        "INVITATION_EXPIRED" -> context.getString(R.string.join_team_expired)
        "INVITATION_NOT_PENDING" -> context.getString(R.string.join_team_processed)
        else -> e.message?.takeIf { it.isNotBlank() } ?: fallback
    }
    else -> fallback
}

/** 三角下拉分组：点击头部收起/展开，箭头 90°↔180° 旋转 */
fun bindCollapsibleSection(header: View, arrow: View, content: View) {
    header.setOnClickListener {
        val skeleton = content.getTag(R.id.inline_skeleton_view) as? View
        val skeletonActive = skeleton?.getTag(R.id.inline_skeleton_active) as? Boolean ?: false
        val expanded = content.isVisible || (skeletonActive && skeleton?.isVisible == true)
        content.isVisible = !expanded && !skeletonActive
        if (skeletonActive) setSkeletonLoading(skeleton, !expanded)
        // 展开时强制内容区重新布局：收起状态下 RecyclerView 测量高度不准确（不可见时测为 0），
        // 直接 toggle 可见性不会触发重新测量，导致列表只渲染部分项
        if (!expanded) content.requestLayout()
        ObjectAnimator.ofFloat(arrow, View.ROTATION, if (expanded) 90f else 180f)
            .setDuration(180)
            .start()
    }
}

/** RecyclerView 通用初始化 */
fun setupRecyclerList(rv: RecyclerView, adapter: RecyclerView.Adapter<*>) {
    rv.layoutManager = LinearLayoutManager(rv.context)
    rv.adapter = adapter
}

/**
 * 通用输入弹窗：bg_card 背景 + 屏宽 85%。
 * 根布局 match_parent 时默认窗口 WRAP_CONTENT 会被压成窄条，故显式设宽。
 */
fun newInputDialog(root: View): Dialog {
    val dialog = Dialog(root.context)
    dialog.setContentView(root)
    dialog.window?.setBackgroundDrawableResource(R.drawable.bg_card)
    dialog.window?.setLayout(
        (root.resources.displayMetrics.widthPixels * 0.85f).toInt(),
        WindowManager.LayoutParams.WRAP_CONTENT
    )
    return dialog
}

/**
 * 用 LinearLayout 填充列表（ScrollView 内用 LinearLayout 替代 RecyclerView，
 * 避免嵌套滚动时子列表内容无法完整展示）。每次调用重建全部 item。
 */
fun <T> fillLinearLayout(
    container: LinearLayout,
    items: List<T>,
    itemLayoutRes: Int,
    bind: (View, T) -> Unit
) {
    container.removeAllViews()
    val inflater = LayoutInflater.from(container.context)
    items.forEach { item ->
        val view = inflater.inflate(itemLayoutRes, container, false)
        bind(view, item)
        container.addView(view)
    }
}

/**
 * 骨架屏呼吸动画：加载中显示占位并做轻微 alpha 呼吸（替代转圈）；
 * 隐藏时停止动画。动画句柄挂在 view.tag 上（复用同一 view 时避免重复启动）。
 */
fun setSkeletonLoading(view: View, active: Boolean) {
    if (active) {
        view.isVisible = true
        val anim = view.tag as? ValueAnimator
        if (anim == null || !anim.isRunning) {
            view.tag = ObjectAnimator.ofFloat(view, View.ALPHA, 0.45f, 1f).apply {
                duration = 700
                repeatMode = ValueAnimator.REVERSE
                repeatCount = ValueAnimator.INFINITE
                start()
            }
        }
    } else {
        view.isVisible = false
        (view.tag as? ValueAnimator)?.cancel()
        view.tag = null
        view.alpha = 1f
    }
}

/**
 * 在纵向内容区旁动态放入通用骨架，避免每个列表页重复维护一套 XML。
 * 仅支持可安全容纳多个子 View 的 LinearLayout / FrameLayout。
 */
fun setInlineSkeletonLoading(content: View, active: Boolean) {
    val parent = content.parent as? ViewGroup ?: return
    if (parent !is LinearLayout && parent !is FrameLayout) return
    val skeleton = (content.getTag(R.id.inline_skeleton_view) as? View)
        ?: LayoutInflater.from(content.context)
            .inflate(R.layout.view_skeleton_rows, parent, false)
            .also { placeholder ->
                if (parent is LinearLayout) {
                    parent.addView(placeholder, parent.indexOfChild(content) + 1)
                } else {
                    parent.addView(placeholder)
                }
                content.setTag(R.id.inline_skeleton_view, placeholder)
            }
    skeleton.setTag(R.id.inline_skeleton_active, active)
    content.isVisible = !active
    setSkeletonLoading(skeleton, active)
}
