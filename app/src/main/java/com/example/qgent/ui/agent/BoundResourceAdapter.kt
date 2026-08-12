package com.example.qgent.ui.agent

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.example.qgent.databinding.ItemBoundResourceBinding

class BoundResourceAdapter(
    private val items: MutableList<String>,
    private val onRemove: (String) -> Unit
) : RecyclerView.Adapter<BoundResourceAdapter.VH>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemBoundResourceBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(binding)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    inner class VH(private val binding: ItemBoundResourceBinding) : RecyclerView.ViewHolder(binding.root) {

        fun bind(name: String) {
            binding.tvResourceName.text = name
            binding.ivRemove.setOnClickListener { onRemove(name) }
        }
    }
}
