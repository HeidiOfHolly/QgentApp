package com.example.qgent.ui.personal

import android.animation.ObjectAnimator
import android.app.Dialog
import android.view.View
import android.view.WindowManager
import androidx.core.view.isVisible
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.qgent.R

/** 三角下拉分组：点击头部收起/展开，箭头 90°↔180° 旋转 */
fun bindCollapsibleSection(header: View, arrow: View, content: View) {
    header.setOnClickListener {
        val expanded = content.isVisible
        content.isVisible = !expanded
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
