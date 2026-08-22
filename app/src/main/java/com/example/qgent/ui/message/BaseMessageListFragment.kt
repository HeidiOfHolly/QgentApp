package com.example.qgent.ui.message

import android.app.AlertDialog
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
import com.bumptech.glide.Glide
import com.example.qgent.QgentApp
import com.example.qgent.R
import com.example.qgent.data.model.NotificationDto
import com.example.qgent.data.model.ReceivedInvitationDto
import com.example.qgent.data.model.formatGroupTime
import com.example.qgent.data.model.parseRfc3339
import com.example.qgent.data.repository.UserRepository
import com.example.qgent.databinding.FragmentMessageListBinding
import com.example.qgent.databinding.ItemNotificationBinding
import com.example.qgent.ui.personal.joinTeamErrorMessage
import com.example.qgent.viewmodel.MainViewModel
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * 消息列表页公共基类（通知中心 §7.1）：抽屉铃铛与任务界面铃铛两个入口共用。
 * 展示当前用户通知列表，支持全部已读 / 单条已读；
 * 通知 groupId 属于当前项目时点击可进入对应群聊，否则仅标记已读。
 *
 * 子类通过 [notificationsFilter] 决定展示的通知子集：
 * - 抽屉铃铛（MessageListFragment）：仅“被邀请加入团队”的 INVITED 与“有人@我”的 MESSAGE_MENTION
 * - 任务铃铛（TaskMessageListFragment）：当前项目任务类通知（排除 INVITED / MESSAGE_MENTION / MR_PENDING）
 * - 交付中心管理员铃铛（DeliveryMessageListFragment）：仅当前项目的 MR 申请审批 MR_PENDING
 */
abstract class BaseMessageListFragment : Fragment() {

    /** 通知过滤规则，默认展示全部；子类可在加载前修改以限定子集 */
    protected var notificationsFilter: (NotificationDto) -> Boolean = { true }

    /** TASK_FAILED 通知点击跳任务详情的导航动作（子类覆盖为各自的 action） */
    protected open val taskDetailActionRes: Int
        get() = R.id.action_messageList_to_taskDetail

    private var _binding: FragmentMessageListBinding? = null
    protected val binding get() = _binding!!

    private val userRepository: UserRepository
        get() = (requireActivity().application as QgentApp).container.userRepository
    private val mainViewModel: MainViewModel by activityViewModels {
        (requireActivity().application as QgentApp).container.mainViewModelFactory
    }

    private val items = mutableListOf<NotificationDto>()
    private lateinit var adapter: NotificationAdapter
    private var notificationLoadGeneration = 0
    private var hasLoadedNotifications = false

    /** 已拒绝的团队邀请通知 id：后端无“拒绝”接口，本地标记后不再展示、不再弹窗 */
    private val rejectedInvitationIds = mutableSetOf<String>()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentMessageListBinding.inflate(inflater, container, false)
        val loadingSize = (56 * resources.displayMetrics.density).toInt()
        Glide.with(this)
            .asGif()
            .load(R.drawable.blue_robot_loading_animation)
            .override(loadingSize, loadingSize)
            .into(_binding!!.ivMessageListLoading)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.ivBack.setOnClickListener { findNavController().navigateUp() }
        binding.btnReadAll.setOnClickListener { markAllRead() }

        adapter = NotificationAdapter()
        binding.rvMessageList.layoutManager = LinearLayoutManager(requireContext())
        binding.rvMessageList.adapter = adapter

        // 下拉刷新：重新拉取通知列表（子类按各自 filter 加载）
        binding.swipeRefresh.setOnRefreshListener { loadNotifications() }

        loadNotifications()
    }

    private fun loadNotifications() {
        val generation = ++notificationLoadGeneration
        hasLoadedNotifications = false
        binding.tvEmpty.isVisible = false
        binding.messageListLoadingState.isVisible = items.isEmpty()
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                userRepository.getNotifications().onSuccess { list ->
                    // 任务通知页初始化会立即按项目条件刷新一次；旧请求晚到时不能覆盖新筛选结果。
                    if (generation != notificationLoadGeneration) return@onSuccess
                    items.clear()
                    items.addAll(list.filter(notificationsFilter))
                    adapter.notifyDataSetChanged()
                    hasLoadedNotifications = true
                }.onFailure { e ->
                    if (generation == notificationLoadGeneration) {
                        Toast.makeText(requireContext(), e.message ?: getString(R.string.load_failed), Toast.LENGTH_SHORT).show()
                    }
                }
            } finally {
                if (generation == notificationLoadGeneration) {
                    binding.swipeRefresh.isRefreshing = false
                    binding.messageListLoadingState.isVisible = false
                    updateEmptyState()
                }
            }
        }
    }

    private fun markOneRead(notification: NotificationDto, position: Int) {
        // 先本地置已读刷新样式，再调接口；接口失败不阻断 UI
        items[position] = notification.copy(isRead = true)
        adapter.notifyItemChanged(position)
        // 已读接口用 fragment 级 lifecycleScope：TASK_FAILED/MR_PENDING 点击会立即跳详情，
        // view 随导航销毁会取消 viewLifecycleOwner.lifecycleScope 中的请求，服务端未标记已读，
        // 返回列表重新拉取后红点仍在（需点两次）。fragment 在返回栈中存活，请求能跑完。
        lifecycleScope.launch {
            userRepository.markNotificationRead(notification.id, UUID.randomUUID().toString())
        }
        // 同步刷新未读红点（可能标记的正是最后一条未读通知）
        mainViewModel.refreshUnreadInvitations()
        mainViewModel.refreshUnreadTaskNotifications()
        mainViewModel.refreshUnreadDeliveryNotifications()
        mainViewModel.refreshDrawerUnread()
    }

    private fun markAllRead() {
        if (items.none { !it.isRead }) return
        items.replaceAll { it.copy(isRead = true) }
        adapter.notifyDataSetChanged()
        // 与 markOneRead 同理：用 fragment 级 lifecycleScope，避免导航销毁 view 时取消已读请求
        lifecycleScope.launch {
            userRepository.markAllNotificationsRead(UUID.randomUUID().toString())
        }
        mainViewModel.refreshUnreadInvitations()
        mainViewModel.refreshUnreadTaskNotifications()
        mainViewModel.refreshUnreadDeliveryNotifications()
        mainViewModel.refreshDrawerUnread()
    }

    /** 通知点击：团队邀请 → 待处理则弹窗选择是否接受；TASK_FAILED → 跳任务详情；
     *  MR_PENDING → 跳 MR 详情；其余 → 群聊属于当前项目时进入群聊 */
    private fun onNotificationClick(notification: NotificationDto, position: Int) {
        markOneRead(notification, position)
        if (notification.kind == "INVITED") {
            handleInvitation(notification)
            return
        }
        // TASK_FAILED：resourceId = taskId，跳任务详情（前端待办：任务失败提醒跳既有任务详情）
        if (notification.kind == "TASK_FAILED") {
            val taskId = notification.resourceId.orEmpty()
            val projectId = notification.projectId.orEmpty()
            if (taskId.isNotEmpty() && projectId.isNotEmpty()) {
                findNavController().navigate(
                    taskDetailActionRes,
                    Bundle().apply {
                        putString(com.example.qgent.ui.tasks.TaskDetailFragment.ARG_TASK_ID, taskId)
                        putString(com.example.qgent.ui.tasks.TaskDetailFragment.ARG_PROJECT_ID, projectId)
                    }
                )
                return
            }
        }
        // MR_PENDING：resourceId = mrId，跳 MR 详情（交付中心管理员消息列表入口）
        if (notification.kind == "MR_PENDING") {
            val mrId = notification.resourceId.orEmpty()
            val mrProjectId = notification.projectId.orEmpty()
            if (mrId.isNotEmpty() && mrProjectId.isNotEmpty()) {
                findNavController().navigate(
                    R.id.mergeRequestDetailFragment,
                    Bundle().apply {
                        putString(com.example.qgent.ui.tasks.MergeRequestDetailFragment.ARG_MR_ID, mrId)
                        putString(com.example.qgent.ui.tasks.MergeRequestDetailFragment.ARG_PROJECT_ID, mrProjectId)
                    }
                )
                return
            }
        }
        // §7.1 MESSAGE_MENTION：点击直达被 @ 的消息。
        // resourceId = 被 @ 的消息 id，跳群后由群聊页滚动高亮到该消息；
        // resourceId 缺失时传 fromMention=true，群聊页兜底滚到最上面一条被 @ 的消息。
        if (notification.kind == "MESSAGE_MENTION") {
            val groupId = notification.groupId.orEmpty()
            val projectId = notification.projectId.orEmpty()
            if (groupId.isNotEmpty() && projectId == mainViewModel.currentProjectId()) {
                findNavController().navigate(
                    R.id.chatDetailFragment,
                    bundleOf(
                        "groupName" to (mainViewModel.groups.value?.firstOrNull { it.id == groupId }?.name ?: notification.title),
                        "groupId" to groupId,
                        "targetMessageId" to notification.resourceId.orEmpty(),
                        "fromMention" to true
                    )
                )
                return
            }
        }
        val groupId = notification.groupId.orEmpty()
        val projectId = notification.projectId.orEmpty()
        if (groupId.isNotEmpty() && projectId == mainViewModel.currentProjectId()) {
            findNavController().navigate(
                R.id.chatDetailFragment,
                bundleOf("groupName" to notification.title, "groupId" to groupId)
            )
        }
    }

    /**
     * 点击团队邀请通知：查收件人的待处理邀请列表，该团队邀请仍为 PENDING 时弹窗；
     * 已撤销、过期或已接受则提示且不再弹窗，避免对失效邀请继续操作。
     */
    private fun handleInvitation(notification: NotificationDto) {
        if (notification.id in rejectedInvitationIds) {
            Toast.makeText(requireContext(), R.string.invitation_rejected, Toast.LENGTH_SHORT).show()
            return
        }
        viewLifecycleOwner.lifecycleScope.launch {
            userRepository.getReceivedInvitations()
                .onSuccess { invitations ->
                    val pending = invitations.firstOrNull {
                        it.teamId == notification.resourceId && it.status == "PENDING"
                    }
                    if (pending == null) {
                        Toast.makeText(requireContext(), R.string.join_team_unavailable, Toast.LENGTH_SHORT).show()
                        return@onSuccess
                    }
                    showAcceptInvitationDialog(notification, pending)
                }
                .onFailure { e ->
                    Toast.makeText(requireContext(), e.message ?: getString(R.string.load_failed), Toast.LENGTH_SHORT).show()
                }
        }
    }

    /** 邀请处理弹窗：展示团队与邀请人，可选择接受 / 拒绝 / 暂不 */
    private fun showAcceptInvitationDialog(notification: NotificationDto, invitation: ReceivedInvitationDto) {
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.invitation_accept_title)
            .setMessage(
                getString(
                    R.string.invitation_accept_message,
                    invitation.inviterDisplayName,
                    invitation.teamName
                )
            )
            .setNeutralButton(R.string.cancel, null)
            .setNegativeButton(R.string.invitation_reject) { _, _ ->
                rejectInvitation(notification)
            }
            .setPositiveButton(R.string.invitation_accept) { _, _ ->
                acceptInvitation(invitation)
            }
            .show()
    }

    /** 拒绝邀请：后端无对应接口，本地标记后从列表移除并刷新未读红点 */
    private fun rejectInvitation(notification: NotificationDto) {
        rejectedInvitationIds.add(notification.id)
        items.removeAll { it.id == notification.id }
        adapter.notifyDataSetChanged()
        updateEmptyState()
        mainViewModel.refreshUnreadInvitations()
        mainViewModel.refreshUnreadTaskNotifications()
        Toast.makeText(requireContext(), R.string.invitation_rejected, Toast.LENGTH_SHORT).show()
    }

    /** 接受邀请：调 POST /team-invitations/{id}/accept，成功后刷新团队与未读邀请红点 */
    private fun acceptInvitation(invitation: ReceivedInvitationDto) {
        viewLifecycleOwner.lifecycleScope.launch {
            userRepository.acceptTeamInvitation(invitation.id, UUID.randomUUID().toString())
                .onSuccess {
                    Toast.makeText(requireContext(), R.string.join_team_success, Toast.LENGTH_SHORT).show()
                    mainViewModel.refreshTeams()
                    mainViewModel.refreshUnreadInvitations()
                }
                .onFailure { e ->
                    Toast.makeText(
                        requireContext(),
                        joinTeamErrorMessage(requireContext(), e, getString(R.string.invitation_accept_failed)),
                        Toast.LENGTH_SHORT
                    ).show()
                }
        }
    }

    /** 重新加载通知列表（子类切换过滤条件/上下文后调用） */
    protected fun reloadNotifications() {
        loadNotifications()
    }

    private fun updateEmptyState() {
        binding.tvEmpty.isVisible = hasLoadedNotifications && items.isEmpty() &&
            !binding.messageListLoadingState.isVisible
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
