package com.example.qgent.ui.delivery

import android.app.AlertDialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.os.bundleOf
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.qgent.QgentApp
import com.example.qgent.R
import com.example.qgent.data.model.DeliveryItemDto
import com.example.qgent.data.repository.ChatRepository
import com.example.qgent.data.repository.DiffRepository
import com.example.qgent.data.repository.GitHubRepository
import com.example.qgent.data.repository.TaskRepository
import com.example.qgent.data.repository.UserRepository
import com.example.qgent.databinding.FragmentDeliveryItemListBinding
import com.example.qgent.ui.personal.setSkeletonLoading
import com.example.qgent.ui.tasks.FilterChip
import com.example.qgent.ui.tasks.FilterChipAdapter
import com.example.qgent.ui.tasks.TaskFilterType
import com.example.qgent.viewmodel.MainViewModel
import kotlinx.coroutines.launch

/** 交付物列表页：展示当前项目全部交付物（布局同任务列表页），顶部可按需求群/发起人/仓库筛选 */
class DeliveryItemListFragment : Fragment() {

    private var _binding: FragmentDeliveryItemListBinding? = null
    private val binding get() = _binding!!

    private val mainViewModel: MainViewModel by activityViewModels {
        (requireActivity().application as QgentApp).container.mainViewModelFactory
    }
    private val taskRepo: TaskRepository
        get() = (requireActivity().application as QgentApp).container.taskRepository
    private val diffRepo: DiffRepository
        get() = (requireActivity().application as QgentApp).container.diffRepository
    private val chatRepository: ChatRepository
        get() = (requireActivity().application as QgentApp).container.chatRepository
    private val githubRepository: GitHubRepository
        get() = (requireActivity().application as QgentApp).container.githubRepository
    private val userRepository: UserRepository
        get() = (requireActivity().application as QgentApp).container.userRepository

    /** 交付物操作（查看 Diff / 确认 / 拒绝 / 重试），成功后刷新本页列表 */
    private val deliveryActions by lazy {
        DeliveryItemActions(this, mainViewModel, diffRepo) { loadDeliveries() }
    }
    private val filterAdapter = FilterChipAdapter { type -> showFilterOptions(type) }

    /** 当前筛选条件（null 表示不筛） */
    private var groupId: String? = null
    private var createdBy: String? = null
    private var repositoryId: String? = null

    // 候选值缓存
    private var groupOptions: Map<String, String> = emptyMap()       // id -> 群名
    private var repoOptions: Map<String, String> = emptyMap()        // id -> 仓库名
    private var creatorOptions: List<Pair<String, String>> = emptyList() // id -> 显示名

    /** 首次加载是否完成（加载结束无论成败置位）：控制骨架屏显示，筛选/刷新不再闪骨架 */
    private var deliveriesLoaded = false

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentDeliveryItemListBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.ivBack.setOnClickListener { findNavController().navigateUp() }
        binding.swipeRefresh.setOnRefreshListener { loadDeliveries() }

        binding.rvFilters.layoutManager = LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false)
        binding.rvFilters.adapter = filterAdapter
        refreshFilterRow()

        val projectId = mainViewModel.currentProjectId() ?: return
        loadCandidates(projectId)
        loadDeliveries()
    }

    override fun onResume() {
        super.onResume()
        val projectId = mainViewModel.currentProjectId() ?: return
        loadCandidates(projectId)
        loadDeliveries()
    }

    /** 加载需求群 / 仓库 / 发起人候选。发起人取项目成员（稳定来源） */
    private fun loadCandidates(projectId: String) {
        viewLifecycleOwner.lifecycleScope.launch {
            chatRepository.getGroups(projectId).onSuccess { groups ->
                groupOptions = groups.associate { it.id to it.title }
                refreshFilterRow()
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            githubRepository.getProjectRepositories(projectId).onSuccess { repos ->
                repoOptions = repos.associate { it.id to it.displayName }
                refreshFilterRow()
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            val teamId = mainViewModel.currentTeamId() ?: return@launch
            val memberNames = userRepository.getTeamMembers(teamId).getOrNull().orEmpty()
                .associate { it.userId to it.displayName }
            creatorOptions = userRepository.getProjectMembers(projectId).getOrNull().orEmpty()
                .map { it.userId to (memberNames[it.userId] ?: it.userId) }
            refreshFilterRow()
        }
    }

    private fun refreshFilterRow() {
        filterAdapter.submitList(
            listOf(
                FilterChip(TaskFilterType.GROUP, "需求群", groupId?.let { groupOptions[it] }),
                FilterChip(TaskFilterType.CREATED_BY, "发起人", createdBy?.let { creatorLabel(it) }),
                FilterChip(TaskFilterType.REPOSITORY, "仓库", repositoryId?.let { repoOptions[it] })
            )
        )
    }

    private fun creatorLabel(id: String): String =
        creatorOptions.firstOrNull { it.first == id }?.second ?: id

    private fun showFilterOptions(type: TaskFilterType) {
        when (type) {
            TaskFilterType.GROUP -> showChoiceDialog(
                "按需求群筛选",
                groupOptions.entries.map { it.value to it.key },
                groupId
            ) { id -> groupId = id; loadDeliveries() }

            TaskFilterType.CREATED_BY -> showChoiceDialog(
                "按发起人筛选",
                creatorOptions.map { it.second to it.first },
                createdBy
            ) { id -> createdBy = id; loadDeliveries() }

            TaskFilterType.REPOSITORY -> showChoiceDialog(
                "按仓库筛选",
                repoOptions.entries.map { it.value to it.key },
                repositoryId
            ) { id -> repositoryId = id; loadDeliveries() }

            TaskFilterType.STATUS -> Unit // 交付物列表不提供状态筛选
        }
    }

    private fun showChoiceDialog(
        title: String,
        options: List<Pair<String, String>>,   // 显示文本 -> 值
        selected: String?,
        onSelect: (String?) -> Unit
    ) {
        // 「全部」作为首项：选中后清除该维度筛选（null 表示不筛）
        val labels = arrayOf(getString(R.string.filter_all)) + options.map { it.first }.toTypedArray()
        val selectedIndex = if (selected == null) 0
        else options.indexOfFirst { it.second == selected }.takeIf { it >= 0 }?.plus(1) ?: 0
        val builder = AlertDialog.Builder(requireContext())
            .setTitle(title)
            .setNegativeButton(R.string.cancel, null)
        var dialogRef: AlertDialog? = null
        builder.setSingleChoiceItems(labels, selectedIndex) { _, which ->
            onSelect(if (which == 0) null else options[which - 1].second)
            refreshFilterRow()
            dialogRef?.dismiss()
        }
        dialogRef = builder.create()
        dialogRef.show()
    }

    private fun loadDeliveries() {
        val projectId = mainViewModel.currentProjectId() ?: return
        binding.swipeRefresh.isRefreshing = false
        viewLifecycleOwner.lifecycleScope.launch {
            // 骨架屏：首次加载完成前显示（不转圈），完成后隐藏（含失败）
            setSkeletonLoading(binding.viewSkeleton.root, !deliveriesLoaded)
            val items = runCatching {
                kotlinx.coroutines.withTimeout(10_000) {
                    taskRepo.getDeliveryItems(
                        projectId,
                        type = "CODE",
                        groupId = groupId,
                        createdBy = createdBy,
                        repositoryId = repositoryId
                    ).getOrThrow()
                }
            }.getOrNull()
            deliveriesLoaded = true
            setSkeletonLoading(binding.viewSkeleton.root, false)
            binding.rvDeliveries.removeAllViews()
            if (items == null) {
                binding.tvEmpty.isVisible = true
                binding.tvEmpty.text = "交付物暂不可用"
            } else {
                binding.tvEmpty.isVisible = items.isEmpty()
                items.forEach { item ->
                    binding.rvDeliveries.addView(
                        DeliveryItemCardBuilder.build(requireContext(), item, deliveryActions, ::openTaskDetail)
                    )
                }
            }
        }
    }

    /** 交付物卡片点击 → 交付物详情页（含逐仓库进度 + MR/任务入口） */
    private fun openTaskDetail(item: DeliveryItemDto) {
        findNavController().navigate(
            R.id.action_deliveryItemList_to_deliveryItemDetail,
            bundleOf(DeliveryItemDetailFragment.ARG_ITEM_JSON to DeliveryItemDetailFragment.toJson(item))
        )
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
