package com.example.qgent.ui.agent

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.example.qgent.R
import com.example.qgent.databinding.ItemAgentCardBinding
import com.example.qgent.model.Agent
import com.example.qgent.model.AgentStatus

class AgentCardAdapter(
    private val items: List<Agent>,
    private val onClick: (Agent) -> Unit
) : RecyclerView.Adapter<AgentCardAdapter.VH>() {

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
            binding.tvAgentRole.text = agent.role.displayName()
            binding.tvAgentStatus.text = when (agent.status) {
                AgentStatus.ACTIVE -> "闲置"
                AgentStatus.RUNNING -> "运行中"
                AgentStatus.ERROR -> "异常"
                AgentStatus.ARCHIVED -> "已下线"
            }
            val ctx = binding.root.context
            val dotColor = when (agent.status) {
                AgentStatus.ACTIVE -> R.color.mint
                AgentStatus.RUNNING -> R.color.primary
                AgentStatus.ERROR -> R.color.red_danger
                AgentStatus.ARCHIVED -> R.color.gray
            }
            binding.vStatusDot.background.setTint(ContextCompat.getColor(ctx, dotColor))
            binding.root.setOnClickListener { onClick(agent) }
        }
    }
}
