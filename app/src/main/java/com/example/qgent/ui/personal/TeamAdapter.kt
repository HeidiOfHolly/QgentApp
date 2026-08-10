package com.example.qgent.ui.personal

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.example.qgent.R
import com.example.qgent.databinding.ItemTeamBinding

class TeamAdapter(
    private val items: List<String>,
    private val onTeamClick: (String, Int) -> Unit
) : RecyclerView.Adapter<TeamAdapter.VH>() {

    var selectedPosition = 0
        set(value) {
            if (field == value) return
            val old = field
            field = value
            notifyItemChanged(old)
            notifyItemChanged(value)
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
                if (selected) R.drawable.bg_team_selected else 0
            )
            binding.tvTeamName.setTextColor(
                ContextCompat.getColor(
                    binding.root.context,
                    if (selected) R.color.on_primary_container else R.color.text_secondary
                )
            )
        }
    }
}
