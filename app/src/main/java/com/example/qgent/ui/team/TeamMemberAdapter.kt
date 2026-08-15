package com.example.qgent.ui.team

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import com.example.qgent.data.model.TeamMemberDto
import com.example.qgent.databinding.ItemChatMemberBinding

/** 团队详情页成员列表适配器 */
class TeamMemberAdapter : RecyclerView.Adapter<TeamMemberAdapter.VH>() {

    private val items = mutableListOf<TeamMemberDto>()

    /** 是否允许移除成员（仅我创建的团队）；创建者行始终不显示删除按钮 */
    var showDelete: Boolean = false

    /** 点击删除按钮回调，参数为对应成员 */
    var onDeleteMember: ((TeamMemberDto) -> Unit)? = null

    fun submitList(newItems: List<TeamMemberDto>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemChatMemberBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(binding)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val member = items[position]
        holder.binding.tvMemberName.text = member.displayName
        holder.binding.tvAgentTag.isVisible = member.role == "TEAM_OWNER"
        holder.binding.tvAgentTag.text = "创建者"
        // 删除按钮：仅我创建的团队可见，创建者行不可移除
        holder.binding.ivDeleteMember.isVisible = showDelete && member.role != "TEAM_OWNER"
        holder.binding.ivDeleteMember.setOnClickListener { onDeleteMember?.invoke(member) }
    }

    override fun getItemCount(): Int = items.size

    class VH(val binding: ItemChatMemberBinding) : RecyclerView.ViewHolder(binding.root)
}
