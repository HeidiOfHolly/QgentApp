package com.example.qgent.ui.team

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import com.example.qgent.R
import com.example.qgent.databinding.ItemTeamBinding

class TeamAdapter(
    private var items: List<String>,
    private val onTeamClick: (String, Int) -> Unit
) : RecyclerView.Adapter<TeamAdapter.VH>() {

    var selectedPosition = -1
        set(value) {
            if (field == value) return
            val old = field
            field = value
            if (old >= 0 && old < itemCount) notifyItemChanged(old)
            if (field >= 0 && field < itemCount) notifyItemChanged(field)
        }

    /** 有未读的团队名集合（抽屉团队栏红点） */
    private var unreadTeamNames = emptySet<String>()

    fun setUnreadTeamNames(names: Set<String>) {
        if (unreadTeamNames == names) return
        unreadTeamNames = names
        notifyDataSetChanged()
    }

    fun submitList(newItems: List<String>) {
        items = newItems
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemTeamBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(binding)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(items[position], position == selectedPosition, unreadTeamNames)
        holder.itemView.setOnClickListener {
            val selected = selectedPosition
            selectedPosition = position
            onTeamClick(items[position], position)
        }
    }

    override fun getItemCount(): Int = items.size

    class VH(private val binding: ItemTeamBinding) : RecyclerView.ViewHolder(binding.root) {

        fun bind(name: String, selected: Boolean, unreadNames: Set<String>) {
            binding.tvTeamName.text = name
            // 高光包裹整个团队子项（根布局整行），而非仅文字
            binding.root.setBackgroundResource(
                if (selected) R.drawable.bg_team_selected_navy else 0
            )
            binding.tvTeamName.setTextColor(
                ContextCompat.getColor(
                    binding.root.context,
                    if (selected) R.color.white else R.color.team_item_normal
                )
            )
            binding.ivUnread.isVisible = name in unreadNames
        }
    }
}
