package com.example.qgent.ui.team

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.example.qgent.databinding.ItemRepositoryBinding

/** 团队详情页仓库列表适配器 */
class TeamRepositoryAdapter : RecyclerView.Adapter<TeamRepositoryAdapter.VH>() {

    private val items = mutableListOf<String>()

    fun submitList(newItems: List<String>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemRepositoryBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(binding)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.binding.tvRepositoryName.text = items[position]
    }

    override fun getItemCount(): Int = items.size

    class VH(val binding: ItemRepositoryBinding) : RecyclerView.ViewHolder(binding.root)
}
