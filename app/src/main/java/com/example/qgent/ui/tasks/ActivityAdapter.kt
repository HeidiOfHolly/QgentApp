package com.example.qgent.ui.tasks

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.example.qgent.databinding.ItemAgentTaskBinding

/** Agent 近况列表适配器：复用 item_agent_task，展示 Agent 名称与完成任务 */
class ActivityAdapter : RecyclerView.Adapter<ActivityAdapter.VH>() {

    private val items = mutableListOf<TaskListViewModel.AgentRun>()

    fun submitList(newItems: List<TaskListViewModel.AgentRun>) {
        // 内容未变化时不刷新，避免轮询每 3 秒整列重绘造成列表闪现
        if (items == newItems) return
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemAgentTaskBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(binding)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val run = items[position]
        holder.binding.tvAgentName.text = run.agentName
        holder.binding.tvAgentWork.text = run.taskTitle
    }

    override fun getItemCount(): Int = items.size

    class VH(val binding: ItemAgentTaskBinding) : RecyclerView.ViewHolder(binding.root)
}
