package com.example.qgent.ui.personal

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.example.qgent.databinding.ItemSelectableBinding

/** 共享多选页的行适配器：label + checkbox，行点击切换选中态 */
class SelectionAdapter(
    private val onToggle: (String, Boolean) -> Unit
) : RecyclerView.Adapter<SelectionAdapter.VH>() {

    data class Item(val id: String, val label: String, val selected: Boolean)

    private val items = mutableListOf<Item>()

    fun submitList(newItems: List<Item>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemSelectableBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(binding)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        holder.binding.tvLabel.text = item.label
        holder.binding.checkbox.isChecked = item.selected
        holder.binding.root.setOnClickListener { onToggle(item.id, !item.selected) }
    }

    override fun getItemCount(): Int = items.size

    class VH(val binding: ItemSelectableBinding) : RecyclerView.ViewHolder(binding.root)
}
