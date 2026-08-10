package com.example.qgent.ui.personal

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.example.qgent.R
import com.example.qgent.databinding.ItemProjectBinding

class ProjectAdapter(
    private val onProjectClick: (String, Int) -> Unit
) : RecyclerView.Adapter<ProjectAdapter.VH>() {

    private val items = mutableListOf<String>()

    var selectedPosition = 0
        set(value) {
            if (field == value) return
            val old = field
            field = value
            if (old in items.indices) notifyItemChanged(old)
            if (field in items.indices) notifyItemChanged(field)
        }

    fun submitList(newItems: List<String>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    fun indexOf(project: String): Int = items.indexOf(project)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemProjectBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(binding)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val selected = position == selectedPosition
        holder.binding.tvProjectName.text = items[position]
        holder.binding.root.setBackgroundResource(
            if (selected) R.drawable.bg_team_selected else 0
        )
        holder.binding.tvProjectName.setTextColor(
            ContextCompat.getColor(
                holder.binding.root.context,
                if (selected) R.color.on_primary_container else R.color.text_primary
            )
        )
        holder.itemView.setOnClickListener {
            selectedPosition = position
            onProjectClick(items[position], position)
        }
    }

    override fun getItemCount(): Int = items.size

    class VH(val binding: ItemProjectBinding) : RecyclerView.ViewHolder(binding.root)
}
