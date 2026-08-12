package com.example.qgent.ui.personal

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import com.example.qgent.R
import com.example.qgent.data.model.TeamDto
import com.example.qgent.databinding.ItemTeamManageBinding

/** 团队管理页的团队列表适配器：展示团队名、成员数与“我创建的”标签 */
class TeamManageAdapter(
    private val onTeamClick: (TeamDto) -> Unit
) : RecyclerView.Adapter<TeamManageAdapter.VH>() {

    private val items = mutableListOf<TeamDto>()

    fun submitList(newItems: List<TeamDto>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemTeamManageBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(binding)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    inner class VH(private val binding: ItemTeamManageBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(team: TeamDto) {
            binding.tvTeamName.text = team.name
            binding.tvMemberCount.text = binding.root.context.getString(
                R.string.team_member_count, team.memberCount
            )
            binding.root.setOnClickListener { onTeamClick(team) }
        }
    }
}
