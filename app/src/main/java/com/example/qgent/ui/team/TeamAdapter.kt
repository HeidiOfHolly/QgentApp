package com.example.qgent.ui.team

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
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

    fun submitList(newItems: List<String>) {
        items = newItems
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemTeamBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(binding)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(items[position], position == selectedPosition)
        holder.itemView.setOnClickListener {
            val selected = selectedPosition
            selectedPosition = position
            onTeamClick(items[position], position)
        }
    }

    override fun getItemCount(): Int = items.size

    class VH(private val binding: ItemTeamBinding) : RecyclerView.ViewHolder(binding.root) {

        fun bind(name: String, selected: Boolean) {
            binding.tvTeamName.text = name
            binding.tvTeamName.setBackgroundResource(
                if (selected) R.drawable.bg_team_selected_navy else 0
            )
            binding.tvTeamName.setTextColor(
                ContextCompat.getColor(
                    binding.root.context,
                    if (selected) R.color.white else R.color.team_item_normal
                )
            )
        }
    }
}
