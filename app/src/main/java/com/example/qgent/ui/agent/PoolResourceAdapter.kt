package com.example.qgent.ui.agent

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import com.example.qgent.databinding.ItemPoolResourceBinding
import com.example.qgent.model.MemoryItem
import com.example.qgent.model.ResourceStatus
import com.example.qgent.model.SkillItem

/** 资源池列表项：展示名称/描述/待审核标签；点击弹详情，长按可触发审核（列表页设置） */
class PoolResourceAdapter<T : Any>(
    private val items: List<T>,
    private val onItemClick: (T) -> Unit
) : RecyclerView.Adapter<PoolResourceAdapter<T>.VH>() {

    private var onItemLongClick: ((T, View) -> Unit)? = null

    /** 设置长按回调（审核队列项用：Admin 长按弹审核操作） */
    fun setOnItemLongClick(listener: (T, View) -> Unit) {
        onItemLongClick = listener
    }

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
            binding.root.setOnLongClickListener {
                onItemLongClick?.invoke(item, it)
                true
            }
        }
    }
}
