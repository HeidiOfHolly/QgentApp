package com.example.qgent.ui.agent

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import com.example.qgent.R
import com.example.qgent.databinding.ItemAgentCardBinding
import com.example.qgent.model.Agent
import com.example.qgent.model.AgentStatus
import com.example.qgent.model.AgentVisibility

class AgentCardAdapter(
    private var items: List<Agent>,
    private val onClick: (Agent) -> Unit
) : RecyclerView.Adapter<AgentCardAdapter.VH>() {

    /** 正在工作流中（有活跃 TaskRun）的 Agent id 集合；由调用方轮询刷新 */
    private var workingIds: Set<String> = emptySet()

    fun submitList(newItems: List<Agent>) {
        items = newItems
        notifyDataSetChanged()
    }

    fun setWorkingIds(ids: Set<String>) {
        workingIds = ids
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemAgentCardBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(binding)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    inner class VH(private val binding: ItemAgentCardBinding) : RecyclerView.ViewHolder(binding.root) {

        fun bind(agent: Agent) {
            binding.tvAgentName.text = agent.name
            binding.tvAgentRole.text = agent.roleLabel()
            // 能力标签：最多 2 个 + "…"，无能力时隐藏
            binding.tvAgentCaps.text = agent.capabilities.take(2).joinToString(" · ")
            binding.tvAgentCaps.isVisible = agent.capabilities.isNotEmpty()
            // 状态：有活跃 TaskRun → 运行中；否则闲置（后端 Agent 状态恒 ACTIVE，运行态由 task-runs 推导）
            val working = agent.id in workingIds
            binding.tvAgentStatus.text = when {
                agent.status == AgentStatus.ARCHIVED -> "已下线"
                agent.visibility == AgentVisibility.PENDING -> "等待审核"
                agent.visibility == AgentVisibility.TEAM -> "团队可用"
                working -> "运行中"
                else -> "闲置"
            }
            val ctx = binding.root.context
            val dotColor = when {
                agent.status == AgentStatus.ARCHIVED -> R.color.gray
                agent.visibility == AgentVisibility.PENDING -> R.color.status_yellow
                working -> R.color.primary
                else -> R.color.mint
            }
            binding.vStatusDot.background.setTint(ContextCompat.getColor(ctx, dotColor))
            binding.root.setOnClickListener { onClick(agent) }
        }
    }
}
