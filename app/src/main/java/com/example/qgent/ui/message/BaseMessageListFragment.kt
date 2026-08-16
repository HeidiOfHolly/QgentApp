package com.example.qgent.ui.message

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.os.bundleOf
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.qgent.QgentApp
import com.example.qgent.R
import com.example.qgent.data.model.NotificationDto
import com.example.qgent.data.model.formatGroupTime
import com.example.qgent.data.model.parseRfc3339
import com.example.qgent.data.repository.UserRepository
import com.example.qgent.databinding.FragmentMessageListBinding
import com.example.qgent.databinding.ItemNotificationBinding
import com.example.qgent.viewmodel.MainViewModel
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * 消息列表页公共基类（通知中心 §7.1）：抽屉铃铛与任务界面铃铛两个入口共用。
 * 展示当前用户通知列表，支持全部已读 / 单条已读；
 * 通知 groupId 属于当前项目时点击可进入对应群聊，否则仅标记已读。
 */
abstract class BaseMessageListFragment : Fragment() {

    private var _binding: FragmentMessageListBinding? = null
    protected val binding get() = _binding!!

    private val userRepository: UserRepository
        get() = (requireActivity().application as QgentApp).container.userRepository
    private val mainViewModel: MainViewModel by activityViewModels {
        (requireActivity().application as QgentApp).container.mainViewModelFactory
    }

    private val items = mutableListOf<NotificationDto>()
    private lateinit var adapter: NotificationAdapter

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentMessageListBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.ivBack.setOnClickListener { findNavController().navigateUp() }
        binding.btnReadAll.setOnClickListener { markAllRead() }

        adapter = NotificationAdapter()
        binding.rvMessageList.layoutManager = LinearLayoutManager(requireContext())
        binding.rvMessageList.adapter = adapter

        loadNotifications()
    }

    private fun loadNotifications() {
        viewLifecycleOwner.lifecycleScope.launch {
            userRepository.getNotifications().onSuccess { list ->
                items.clear()
                items.addAll(list)
                adapter.notifyDataSetChanged()
                updateEmptyState()
            }.onFailure { e ->
                Toast.makeText(requireContext(), e.message ?: getString(R.string.load_failed), Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun markOneRead(notification: NotificationDto, position: Int) {
        // 先本地置已读刷新样式，再调接口；接口失败不阻断 UI
        items[position] = notification.copy(isRead = true)
        adapter.notifyItemChanged(position)
        viewLifecycleOwner.lifecycleScope.launch {
            userRepository.markNotificationRead(notification.id, UUID.randomUUID().toString())
        }
    }

    private fun markAllRead() {
        if (items.none { !it.isRead }) return
        items.replaceAll { it.copy(isRead = true) }
        adapter.notifyDataSetChanged()
        viewLifecycleOwner.lifecycleScope.launch {
            userRepository.markAllNotificationsRead(UUID.randomUUID().toString())
        }
    }

    /** 通知的群聊属于当前项目时进入对应群聊；否则仅标记已读 */
    private fun onNotificationClick(notification: NotificationDto, position: Int) {
        markOneRead(notification, position)
        val groupId = notification.groupId.orEmpty()
        val projectId = notification.projectId.orEmpty()
        if (groupId.isNotEmpty() && projectId == mainViewModel.currentProjectId()) {
            findNavController().navigate(
                R.id.chatDetailFragment,
                bundleOf("groupName" to notification.title, "groupId" to groupId)
            )
        }
    }

    private fun updateEmptyState() {
        binding.tvEmpty.isVisible = items.isEmpty()
    }

    private inner class NotificationAdapter :
        RecyclerView.Adapter<NotificationAdapter.Holder>() {

        inner class Holder(val binding: ItemNotificationBinding) : RecyclerView.ViewHolder(binding.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
            Holder(ItemNotificationBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun getItemCount(): Int = items.size

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val item = items[position]
            val b = holder.binding
            b.tvTitle.text = item.title
            b.tvTitle.setTextColor(if (item.isRead) holder.itemView.context.getColor(R.color.gray) else holder.itemView.context.getColor(R.color.charcoal))
            b.tvDescription.text = item.description
            b.tvDescription.isVisible = !item.description.isNullOrBlank()
            b.tvTime.text = formatGroupTime(parseRfc3339(item.createdAt))
            b.unreadDot.isVisible = !item.isRead
            b.root.setOnClickListener { onNotificationClick(item, position) }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
