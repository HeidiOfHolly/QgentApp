package com.example.qgent.ui.team

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import com.example.qgent.R
import com.example.qgent.data.model.TeamInvitationDto
import com.example.qgent.databinding.ItemInviteHistoryBinding

/** 邀请记录弹窗列表适配器：邮箱 + 状态 + 撤销（仅待接受可撤销） */
class InviteHistoryAdapter(
    private val onRevoke: (TeamInvitationDto) -> Unit
) : RecyclerView.Adapter<InviteHistoryAdapter.VH>() {

    private val items = mutableListOf<TeamInvitationDto>()

    fun submitList(newItems: List<TeamInvitationDto>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemInviteHistoryBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(binding)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val invitation = items[position]
        val context = holder.binding.root.context
        holder.binding.tvEmail.text = invitation.email
        holder.binding.tvStatus.text = statusText(context, invitation.status)
        // 仅 PENDING（待接受）可撤销
        holder.binding.btnRevoke.isVisible = invitation.status == "PENDING"
        holder.binding.btnRevoke.setOnClickListener { onRevoke(invitation) }
    }

    private fun statusText(context: android.content.Context, status: String): String = when (status) {
        "PENDING" -> context.getString(R.string.invite_status_pending)
        "ACCEPTED" -> context.getString(R.string.invite_status_accepted)
        "REVOKED" -> context.getString(R.string.invite_status_revoked)
        "EXPIRED" -> context.getString(R.string.invite_status_expired)
        else -> status
    }

    override fun getItemCount(): Int = items.size

    class VH(val binding: ItemInviteHistoryBinding) : RecyclerView.ViewHolder(binding.root)
}
