package com.example.qgent.ui.agent

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.example.qgent.databinding.ItemPreviewResourceBinding
import com.example.qgent.model.MemoryItem
import com.example.qgent.model.SkillItem

class PreviewAdapter(
    private val items: List<Any>,
    private val onItemClick: (Any) -> Unit
) : RecyclerView.Adapter<PreviewAdapter.VH>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemPreviewResourceBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(binding)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    inner class VH(private val binding: ItemPreviewResourceBinding) : RecyclerView.ViewHolder(binding.root) {

        fun bind(item: Any) {
            val (name, desc) = when (item) {
                is MemoryItem -> item.name to item.description
                is SkillItem -> item.name to item.description
                else -> "" to ""
            }
            binding.tvPreviewName.text = "$name · $desc"
            binding.root.setOnClickListener { onItemClick(item) }
        }
    }
}
