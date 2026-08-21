package com.example.qgent.ui.tasks

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.example.qgent.data.model.TaskListItemDto
import com.example.qgent.databinding.ItemRecentTaskBinding

/** 最近任务横向卡片适配器：展示当前用户最近创建的 3 个任务（复用 item_recent_task） */
class RecentTaskAdapter(
    private val onItemClick: (TaskListItemDto) -> Unit
) : RecyclerView.Adapter<RecentTaskAdapter.VH>() {

    private val items = mutableListOf<TaskListItemDto>()

    fun submitList(newItems: List<TaskListItemDto>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemRecentTaskBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(binding)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val task = items[position]
        val binding = holder.binding
        val context = binding.root.context
        binding.tvRecentTaskTitle.text = task.displayCode + " " + task.title
        binding.tvRecentTaskStatus.text = recentStatusText(task.status)
        binding.tvRecentTaskStatus.setTextColor(context.getColor(taskStatusColorRes(task.status)))
        binding.root.setOnClickListener { onItemClick(task) }
    }

    override fun getItemCount(): Int = items.size

    private fun recentStatusText(status: String): String = when (status) {
        "PLANNING" -> "规划中"
        "PENDING" -> "待执行"
        "RUNNING" -> "执行中"
        "WAITING_DIFF_CONFIRMATION" -> "待确认 Diff"
        "DELIVERING" -> "交付中"
        "SUCCEEDED" -> "已完成"
        "DELIVERY_FAILED" -> "交付失败"
        "FAILED" -> "失败"
        "CANCELLING" -> "取消中"
        "CANCELLED" -> "已取消"
        else -> status
    }

    class VH(val binding: ItemRecentTaskBinding) : RecyclerView.ViewHolder(binding.root)
}
