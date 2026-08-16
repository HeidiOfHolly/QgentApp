package com.example.qgent.ui.chat

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import com.example.qgent.R
import com.example.qgent.databinding.ItemGroupMemberPickBinding

/** 创建需求群时选择群成员的条目（含勾选态） */
data class GroupMemberPick(
    val userId: String,
    val name: String,
    var role: String,               // PROJECT_MEMBER / PROJECT_ADMIN
    var checked: Boolean = false,
    /** 是否为团队 Agent（Agent 是团队级，群内作为 @ 渠道展示，见 docs/product-notes.md） */
    val isAgent: Boolean = false
)

/**
 * 群成员选择适配器：
 * - 创建群场景：勾选成员（默认全不选，可全选）
 * - 添加成员场景：勾选 + 点击身份标签在 项目成员/项目管理员 间切换（onRoleClick 非空时）
 */
class GroupMemberPickAdapter(
    private val onItemClick: (GroupMemberPick) -> Unit,
    private val onRoleClick: ((GroupMemberPick) -> Unit)? = null
) : RecyclerView.Adapter<GroupMemberPickAdapter.VH>() {

    private val items = mutableListOf<GroupMemberPick>()

    fun submitList(list: List<GroupMemberPick>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    fun toggle(member: GroupMemberPick) {
        val index = items.indexOfFirst { it.userId == member.userId }
        if (index >= 0) {
            items[index].checked = !items[index].checked
            notifyItemChanged(index)
        }
    }

    /** 切换成员身份（PROJECT_MEMBER ↔ PROJECT_ADMIN），用于添加成员场景 */
    fun toggleRole(member: GroupMemberPick) {
        val index = items.indexOfFirst { it.userId == member.userId }
        if (index >= 0) {
            items[index].role = if (items[index].role == "PROJECT_ADMIN") "PROJECT_MEMBER" else "PROJECT_ADMIN"
            notifyItemChanged(index)
        }
    }

    fun setAllChecked(checked: Boolean) {
        items.forEach { it.checked = checked }
        notifyDataSetChanged()
    }

    /** 当前勾选的成员 userId 列表 */
    fun checkedIds(): List<String> = items.filter { it.checked }.map { it.userId }

    /** 当前各成员的期望身份（userId → PROJECT_MEMBER / PROJECT_ADMIN） */
    fun roleById(): Map<String, String> = items.associate { it.userId to it.role }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(ItemGroupMemberPickBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(items[position])

    override fun getItemCount(): Int = items.size

    inner class VH(private val binding: ItemGroupMemberPickBinding) : RecyclerView.ViewHolder(binding.root) {

        fun bind(member: GroupMemberPick) {
            binding.tvMemberName.text = member.name
            binding.cbChecked.isChecked = member.checked
            // 身份标签：Agent 显示「Agent」；添加成员场景显示可点击的身份；创建群场景仅 Agent/管理员显示
            val roleClickable = onRoleClick != null
            binding.tvMemberRole.isVisible = member.isAgent || roleClickable || member.role == "PROJECT_ADMIN"
            binding.tvMemberRole.text = when {
                member.isAgent -> "Agent"
                member.role == "PROJECT_ADMIN" -> binding.root.context.getString(R.string.role_admin_short)
                else -> binding.root.context.getString(R.string.role_member)
            }
            binding.tvMemberRole.setOnClickListener {
                onRoleClick?.invoke(member)
            }
            binding.root.setOnClickListener { onItemClick(member) }
        }
    }
}
