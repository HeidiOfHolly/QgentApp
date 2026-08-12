package com.example.qgent.ui.chat

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import com.example.qgent.databinding.ItemMentionMemberBinding
import com.example.qgent.model.GroupMember
import com.example.qgent.model.MemberType

class MentionMemberAdapter(
    private val members: List<GroupMember>,
    private val onMemberClick: (GroupMember) -> Unit
) : RecyclerView.Adapter<MentionMemberAdapter.VH>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(ItemMentionMemberBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) {
        val member = members[position]
        holder.binding.tvMemberName.text = member.name
        holder.binding.tvAgentTag.isVisible = member.type == MemberType.AGENT
        holder.binding.root.setOnClickListener { onMemberClick(member) }
    }

    override fun getItemCount(): Int = members.size

    class VH(val binding: ItemMentionMemberBinding) : RecyclerView.ViewHolder(binding.root)
}
