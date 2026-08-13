package com.example.qgent.ui.personal

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.example.qgent.databinding.ItemChatMemberBinding

/** 团队详情页成员列表适配器 */
class TeamMemberAdapter : RecyclerView.Adapter<TeamMemberAdapter.VH>() {

    private val items = mutableListOf<String>()

    fun submitList(newItems: List<String>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemChatMemberBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(binding)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.binding.tvMemberName.text = items[position]
    }

    override fun getItemCount(): Int = items.size

    class VH(val binding: ItemChatMemberBinding) : RecyclerView.ViewHolder(binding.root)
}
