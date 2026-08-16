package com.example.qgent.ui.personal

import android.animation.ObjectAnimator
import android.app.Dialog
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.LinearLayout
import androidx.core.view.isVisible
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.qgent.R

/** 三角下拉分组：点击头部收起/展开，箭头 90°↔180° 旋转 */
fun bindCollapsibleSection(header: View, arrow: View, content: View) {
    header.setOnClickListener {
        val expanded = content.isVisible
        content.isVisible = !expanded
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
