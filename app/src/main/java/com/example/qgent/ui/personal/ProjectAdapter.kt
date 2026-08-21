package com.example.qgent.ui.personal

import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.example.qgent.R
import com.example.qgent.data.api.RetrofitClient
import com.example.qgent.databinding.ItemProjectBinding

/** 点击回调返回是否允许选中（false 表示未选中团队等场景，点击无效且不高亮） */
class ProjectAdapter(
    private val onProjectClick: (String, Int) -> Boolean
) : RecyclerView.Adapter<ProjectAdapter.VH>() {

    private val items = mutableListOf<String>()

    var selectedPosition = -1
        set(value) {
            if (field == value) return
            val old = field
            field = value
            if (old in items.indices) notifyItemChanged(old)
            if (field in items.indices) notifyItemChanged(field)
        }

    /** 有未读的项目名集合（抽屉项目栏红点） */
    private var unreadProjectNames = emptySet<String>()

    /** 项目名 → 头像 URL 映射（§31.1，抽屉项目列表显示） */
    private var avatarMap: Map<String, String> = emptyMap()

    fun setAvatarMap(map: Map<String, String>) {
        avatarMap = map
        notifyDataSetChanged()
    }

    fun setUnreadProjectNames(names: Set<String>) {
        if (unreadProjectNames == names) return
        unreadProjectNames = names
        notifyDataSetChanged()
    }

    fun submitList(newItems: List<String>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    fun indexOf(project: String): Int = items.indexOf(project)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemProjectBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(binding)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val selected = position == selectedPosition
        holder.binding.tvProjectName.text = items[position]
        // 项目头像（§31.1）：有则显示，无则默认文件夹图标
        val avatarUrl = avatarMap[items[position]]
        if (avatarUrl.isNullOrBlank()) {
            holder.binding.ivProjectAvatar.imageTintList = ColorStateList.valueOf(
                ContextCompat.getColor(holder.binding.root.context, R.color.text_secondary)
            )
            holder.binding.ivProjectAvatar.setImageResource(R.drawable.ic_folder)
        } else {
            holder.binding.ivProjectAvatar.imageTintList = null
            Glide.with(holder.binding.ivProjectAvatar)
                .load(RetrofitClient.resolveMediaUrl(avatarUrl))
                .centerCrop()
                .placeholder(R.drawable.ic_folder)
                .error(R.drawable.ic_folder)
                .into(holder.binding.ivProjectAvatar)
        }
        holder.binding.root.setBackgroundResource(
            if (selected) R.drawable.bg_team_selected else 0
        )
        holder.binding.tvProjectName.setTextColor(
            ContextCompat.getColor(
                holder.binding.root.context,
                if (selected) R.color.on_primary_container else R.color.text_primary
            )
        )
        holder.binding.ivUnread.isVisible = items[position] in unreadProjectNames
        holder.itemView.setOnClickListener {
            if (onProjectClick(items[position], position)) {
                selectedPosition = position
            }
        }
    }

    override fun getItemCount(): Int = items.size

    class VH(val binding: ItemProjectBinding) : RecyclerView.ViewHolder(binding.root)
}
