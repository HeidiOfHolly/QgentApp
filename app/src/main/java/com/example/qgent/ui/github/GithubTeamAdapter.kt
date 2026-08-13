package com.example.qgent.ui.github

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.example.qgent.R
import com.example.qgent.data.model.TeamDto
import com.example.qgent.databinding.ItemTeamGithubBinding

/** GitHub 页的团队列表适配器：展示团队名与仓库数（仓库数暂为占位，待 GitHub 数据接入） */
class GithubTeamAdapter(
    private val onItemClick: (TeamDto) -> Unit
) : RecyclerView.Adapter<GithubTeamAdapter.VH>() {

    private val items = mutableListOf<TeamDto>()

    fun submitList(newItems: List<TeamDto>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemTeamGithubBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(binding)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    inner class VH(private val binding: ItemTeamGithubBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(team: TeamDto) {
            binding.tvTeamName.text = team.name
            binding.tvRepositoryNum.text = binding.root.context.getString(R.string.github_repo_count, 0)
            binding.root.setOnClickListener { onItemClick(team) }
        }
    }
}
