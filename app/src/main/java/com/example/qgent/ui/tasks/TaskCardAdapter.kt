package com.example.qgent.ui.tasks

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import com.example.qgent.data.model.TaskListItemDto
import com.example.qgent.databinding.ItemTaskBinding

/** 任务卡片列表适配器：复用 item_task 布局，展示任务名/信息/状态/进度 */
class TaskCardAdapter(
    private val onItemClick: (TaskListItemDto) -> Unit
) : RecyclerView.Adapter<TaskCardAdapter.VH>() {

    private val items = mutableListOf<TaskListItemDto>()

    companion object {
        /** 可能卡死的中间态（任务 ID 提示用，清单四） */
        private val STUCK_STATUSES = setOf("PLANNING", "PENDING", "RUNNING")
        private const val STUCK_THRESHOLD_MS = 5 * 60 * 1000L
    }

    fun submitList(newItems: List<TaskListItemDto>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemTaskBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(binding)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    inner class VH(private val binding: ItemTaskBinding) : RecyclerView.ViewHolder(binding.root) {

        fun bind(task: TaskListItemDto) {
            val context = binding.root.context
            binding.tvTaskName.text = task.displayCode + " " + task.title
            binding.tvTaskInformation.text = task.requirementSummary ?: task.title
            binding.tvTaskStatus.text = statusText(context, task.status)
            val progress = progressOf(task)
            binding.pbTask.progress = progress
            binding.tvTaskPrgress.text = "$progress%"
            // 任务卡在中间态（PLANNING/RUNNING）且久未更新 → 显示任务 ID 便于排查（清单四）
            val stuck = task.status in STUCK_STATUSES && isStuck(task.updatedAt)
            binding.tvTaskId.isVisible = stuck
            if (stuck) binding.tvTaskId.text = "任务ID: ${task.id}"
            binding.root.setOnClickListener { onItemClick(task) }
        }

        /** 中间态是否超时未更新（> 5 分钟视为可能卡死） */
        private fun isStuck(updatedAt: String): Boolean {
            val ts = com.example.qgent.data.model.parseRfc3339(updatedAt)
            return System.currentTimeMillis() - ts > STUCK_THRESHOLD_MS
        }

        private fun statusText(context: android.content.Context, status: String): String = when (status) {
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

        /** 文档 §16 明确不返回伪造进度百分比；此处用已成功步骤数估算，仅作视觉提示 */
        private fun progressOf(task: TaskListItemDto): Int {
            val summary = task.executionSummary ?: return 0
            val total = summary.totalSteps
            if (total <= 0) return 0
            return ((summary.succeededSteps + summary.runningSteps) * 100 / total).coerceIn(0, 100)
        }
    }
}
