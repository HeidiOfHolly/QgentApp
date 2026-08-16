package com.example.qgent.ui.tasks

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.example.qgent.databinding.ItemFilterBinding

/** 任务筛选条件项定义 */
enum class TaskFilterType { GROUP, STATUS, CREATED_BY, REPOSITORY }

/** 单个筛选项的数据：类型 + 显示标签 + 当前选中值文本（空表示未筛） */
data class FilterChip(
    val type: TaskFilterType,
    val label: String,
    val selectedValue: String? = null
)

/** 任务筛选条件横向行适配器：4 个筛选项，点击回调对应类型 */
class FilterChipAdapter(
    private val onFilterClick: (TaskFilterType) -> Unit
) : RecyclerView.Adapter<FilterChipAdapter.VH>() {

    private val items = mutableListOf<FilterChip>()

    fun submitList(newItems: List<FilterChip>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemFilterBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(binding)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val chip = items[position]
        val b = holder.binding
        b.tvFilterName.text = chip.label
        b.tvFilterValue.text = chip.selectedValue
        b.root.setOnClickListener { onFilterClick(chip.type) }
    }

    override fun getItemCount(): Int = items.size

    class VH(val binding: ItemFilterBinding) : RecyclerView.ViewHolder(binding.root)
}
