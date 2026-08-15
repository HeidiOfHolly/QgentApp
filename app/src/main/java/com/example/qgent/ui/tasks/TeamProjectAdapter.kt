package com.example.qgent.ui.tasks

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.example.qgent.databinding.ItemProjectBinding

/** 团队详情页项目列表适配器 */
class TeamProjectAdapter : RecyclerView.Adapter<TeamProjectAdapter.VH>() {

    private val items = mutableListOf<String>()

    fun submitList(newItems: List<String>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemProjectBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(binding)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.binding.tvProjectName.text = items[position]
    }

    override fun getItemCount(): Int = items.size

    class VH(val binding: ItemProjectBinding) : RecyclerView.ViewHolder(binding.root)
}
