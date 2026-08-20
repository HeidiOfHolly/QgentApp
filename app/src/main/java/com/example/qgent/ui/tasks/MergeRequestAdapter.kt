package com.example.qgent.ui.tasks

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.example.qgent.data.model.MergeRequestDto
import com.example.qgent.databinding.ItemMrCardBinding

/** MR 列表适配器：复用 item_mr_card，展示仓库名、分支群与状态 */
class MergeRequestAdapter(
    private val onItemClick: (MergeRequestDto) -> Unit = {}
) : RecyclerView.Adapter<MergeRequestAdapter.VH>() {

    private val repoNameById = mutableMapOf<String, String>()

    private val items = mutableListOf<MergeRequestDto>()

    fun submitList(newItems: List<MergeRequestDto>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    fun updateRepoNames(repoNameById: Map<String, String>) {
        this.repoNameById.clear()
        this.repoNameById.putAll(repoNameById)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemMrCardBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(binding)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val mr = items[position]
        val b = holder.binding
        b.tvMrRepo.text = repoNameById[mr.repositoryId] ?: "仓库"
        b.tvMrBranches.text = "${mr.sourceBranch} → ${mr.targetBranch}"
        b.tvMrStatus.text = statusText(mr.status)
        b.root.setOnClickListener {
            // PENDING_CREATE 是列表投影占位（§43：number=0/webUrl=null，真实 MR 未创建），
            // 不得用占位 id 调真实 MR 详情；点击仅提示，不跳转
            if (mr.status == "PENDING_CREATE") {
                android.widget.Toast.makeText(
                    b.root.context, "MR 待创建，请先通过预检与 CQ+1", android.widget.Toast.LENGTH_SHORT
                ).show()
            } else {
                onItemClick(mr)
            }
        }
    }

    private fun statusText(status: String): String = when (status) {
        "OPEN" -> "进行中"
        "MERGED" -> "已合并"
        "CLOSED" -> "已关闭"
        // §43：列表投影占位，真实 MR 未创建（number=0/webUrl=null），待预检/CQ+1 通过后创建
        "PENDING_CREATE" -> "待创建"
        else -> status
    }

    override fun getItemCount(): Int = items.size

    class VH(val binding: ItemMrCardBinding) : RecyclerView.ViewHolder(binding.root)
}
