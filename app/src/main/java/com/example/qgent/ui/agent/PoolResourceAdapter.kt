package com.example.qgent.ui.agent

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import com.example.qgent.databinding.ItemPoolResourceBinding
import com.example.qgent.model.MemoryItem
import com.example.qgent.model.ResourceStatus
import com.example.qgent.model.SkillItem

/** 资源池列表项：展示名称/描述/待审核标签；点击回调携带原始 item（SkillItem/MemoryItem） */
class PoolResourceAdapter<T : Any>(
    private val items: List<T>,
    private val onItemClick: (T) -> Unit
) : RecyclerView.Adapter<PoolResourceAdapter<T>.VH>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemPoolResourceBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(binding)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    inner class VH(private val binding: ItemPoolResourceBinding) : RecyclerView.ViewHolder(binding.root) {

        fun bind(item: T) {
            val name: String
            val desc: String
            val status: ResourceStatus

            when (item) {
                is MemoryItem -> {
                    name = item.name
                    desc = item.description
                    status = item.status
                }
                is SkillItem -> {
                    name = item.name
                    desc = item.description
                    status = item.status
                }
                else -> return
            }

            binding.tvResourceName.text = name
            binding.tvResourceDesc.text = desc
            binding.tvStatusTag.isVisible = status == ResourceStatus.PENDING
            binding.root.setOnClickListener { onItemClick(item) }
        }
    }
}
