package com.example.qgent.ui.delivery

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
import com.example.qgent.QgentApp
import com.example.qgent.R
import com.example.qgent.data.model.DeliveryItemDto
import com.example.qgent.data.model.DeliveryRepositoryDeliveryDto
import com.example.qgent.data.model.MergeRequestPreflightDto
import com.example.qgent.data.repository.TaskRepository
import com.example.qgent.databinding.FragmentDeliveryItemDetailBinding
import com.example.qgent.viewmodel.MainViewModel
import com.google.gson.Gson
import kotlinx.coroutines.launch
import java.util.UUID

/** 交付物详情页：交付物信息 + 逐仓库交付进度 + MR 摘要入口（MR webUrl 外部打开；关联任务跳任务详情） */
class DeliveryItemDetailFragment : Fragment() {

    private var _binding: FragmentDeliveryItemDetailBinding? = null
    private val binding get() = _binding!!

    private val mainViewModel: MainViewModel by activityViewModels {
        (requireActivity().application as QgentApp).container.mainViewModelFactory
    }
    private val taskRepository: TaskRepository
        get() = (requireActivity().application as QgentApp).container.taskRepository

    private val item: DeliveryItemDto? by lazy {
        arguments?.getString(ARG_ITEM_JSON)?.let { Gson().fromJson(it, DeliveryItemDto::class.java) }
    }
    private var eventStreamJob: kotlinx.coroutines.Job? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentDeliveryItemDetailBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.ivBack.setOnClickListener { findNavController().navigateUp() }
        val it = item
        if (it == null) {
            Toast.makeText(requireContext(), "交付物数据缺失", Toast.LENGTH_SHORT).show()
            findNavController().navigateUp()
            return
        }
        bind(it)
        loadPreflight(it)
    }

    override fun onResume() {
        super.onResume()
        startEventStream()
    }

    override fun onPause() {
        super.onPause()
        stopEventStream()
    }

    /** 项目级 SSE：预检/DryRun/MR 事件 → 刷新预检状态（计划 §4.4；事件只触发刷新，以查询接口为准） */
    private fun startEventStream() {
        val projectId = mainViewModel.currentProjectId() ?: return
        val stream = (requireActivity().application as QgentApp).container.projectEventStream
        stream.startProject(projectId)
        if (eventStreamJob == null) {
            eventStreamJob = viewLifecycleOwner.lifecycleScope.launch {
                stream.events.collect { event ->
                    when (event.type) {
                        com.example.qgent.data.sse.SseEventType.PREFLIGHT_UPDATED,
                        com.example.qgent.data.sse.SseEventType.DRY_RUN_UPDATED,
                        com.example.qgent.data.sse.SseEventType.MERGE_REQUEST_UPDATED -> {
                            item?.let { loadPreflight(it) }
                        }
                        else -> Unit
                    }
                }
            }
        }
    }

    private fun stopEventStream() {
        eventStreamJob?.cancel()
        eventStreamJob = null
        (requireActivity().application as QgentApp).container.projectEventStream.stop()
    }

    /**
     * 统一创建 MR 自动预检流程（计划 §4.2/§4.3）：
     * 按 Task 查全部仓库预检状态，按状态展示操作按钮：
     * - 无预检记录 → DIFF_FIRST 显示手动「创建 MR」；MR_FIRST 只读提示（后端自动发起预检）
     * - REQUESTED/DRY_RUN_QUEUED/DRY_RUN_RUNNING → 「预检中」，禁用
     * - WAITING_CQ → 显示「CQ+1」
     * - CQ_REJECTED → 显示「重新预检」
     * - FAILED/STALE → 显示「重试预检」
     * - CREATING_MR → 「正在创建 MR」，禁用
     * - MR_CREATED → 显示真实 MR 链接，无按钮
     */
    private fun loadPreflight(itemDto: DeliveryItemDto) {
        val projectId = mainViewModel.currentProjectId() ?: return
        val taskId = itemDto.source?.taskId ?: return
        viewLifecycleOwner.lifecycleScope.launch {
            // 复位：清空多仓库容器 + 无记录占位
            binding.containerPreflights.removeAllViews()
            binding.tvPreflightStatus.isVisible = false
            binding.btnCreateMr.isVisible = false
            val statuses = taskRepository.getTaskMergeRequestPreflight(projectId, taskId)
                .getOrNull().orEmpty()   // 多仓库：每个预检项独立渲染
            if (statuses.isEmpty()) {
                // DIFF_FIRST：用户确认 Diff 后需手动创建 MR（§46），无预检记录时提供「创建 MR」入口启动 Dry Run；
                // MR_FIRST：交付后由后端自动发起 Dry Run（§27.10/§46），前端无需手动创建，只读等待自动推进。
                // 未交付（diff 未确认，reviewStatus != ACCEPTED）时无法申请创建 MR
                val deliveryMode = taskRepository.getTaskDetail(projectId, taskId).getOrNull()?.deliveryMode
                binding.tvPreflightStatus.isVisible = true
                val diffConfirmed = itemDto.reviewStatus == "ACCEPTED"
                if (deliveryMode == "DIFF_FIRST" && diffConfirmed) {
                    binding.tvPreflightStatus.text = "尚未创建 MR，可手动申请预检"
                    binding.btnCreateMr.isVisible = true
                    binding.btnCreateMr.text = "创建 MR"
                    binding.btnCreateMr.setOnClickListener {
                        requestPreflight(projectId, taskId, itemDto)
                    }
                } else if (deliveryMode == "DIFF_FIRST") {
                    binding.tvPreflightStatus.text = "请先确认 Diff 后再申请创建 MR"
                } else {
                    binding.tvPreflightStatus.text = "等待后端自动发起预检"
                }
                return@launch
            }
            // 逐仓库渲染预检状态块（每个仓库独立状态与操作，不能把一个仓库的 WAITING_CQ 当整个任务状态）
            statuses.forEach { st ->
                binding.containerPreflights.addView(buildPreflightItem(projectId, taskId, itemDto, st))
            }
        }
    }

    /** 构建单个仓库的预检状态块（item_preflight_status）：按状态机展示状态文本与 CQ+1/拒绝/重试按钮 */
    private fun buildPreflightItem(
        projectId: String,
        taskId: String,
        itemDto: DeliveryItemDto,
        status: MergeRequestPreflightDto
    ): View {
        val binding = com.example.qgent.databinding.ItemPreflightStatusBinding.inflate(layoutInflater)
        binding.tvRepoName.text = status.repositoryName?.takeIf { it.isNotBlank() } ?: "仓库"
        // CQ+1 已操作判定：后端顶层 status 可能未同步推进，综合 cqPlusOne/cqPlusOneStatus 容错
        val cqStatus = status.cqPlusOne?.status?.takeIf { it.isNotBlank() } ?: status.cqPlusOneStatus
        val effective = when {
            cqStatus == "APPROVED" && status.status == "WAITING_CQ" -> "CREATING_MR"
            cqStatus == "REJECTED" && status.status == "WAITING_CQ" -> "CQ_REJECTED"
            else -> status.status
        }
        when (effective) {
            "MR_CREATED" -> {
                binding.tvStatus.text = "MR 已创建：MR #${status.mergeRequest?.number ?: "?"} ${status.mergeRequest?.title.orEmpty()}"
            }
            // 等待 CQ+1：仅 canCqApprove==true 才显示通过/拒绝按钮（§清单 §2/§5）
            "WAITING_CQ" -> {
                binding.tvStatus.text = if (status.canCqApprove == true) {
                    "DryRun 通过，等待独立成员 CQ+1"
                } else {
                    "DryRun 通过，等待其他成员 CQ+1"
                }
                val canOperate = status.canCqApprove == true
                binding.btnRow.isVisible = canOperate
                if (canOperate) {
                    binding.btnCqApprove.setOnClickListener { doCqApprove(projectId, status.dryRunId) }
                    binding.btnCqReject.setOnClickListener { doCqReject(projectId, status.dryRunId) }
                }
            }
            "CREATING_MR" -> {
                binding.tvStatus.text = "CQ+1 已通过，正在创建 MR…"
            }
            "CQ_REJECTED" -> {
                binding.tvStatus.text = "CQ 被拒绝${status.failureCode?.let { "（$it）" } ?: ""}"
                val rejectReason = status.cqPlusOne?.reason?.takeIf { it.isNotBlank() }
                    ?: status.cqReviewReason?.takeIf { it.isNotBlank() }
                    ?: status.reviewReason?.takeIf { it.isNotBlank() }
                    ?: status.failureReason?.takeIf { it.isNotBlank() }
                binding.tvRejectReason.isVisible = rejectReason != null
                if (rejectReason != null) binding.tvRejectReason.text = "CQ+1 被拒原因：$rejectReason"
                // 重试：仅 canRetry==true 显示（§清单 §6）
                binding.btnRow.isVisible = status.canRetry == true
                binding.btnRetry.isVisible = status.canRetry == true
                binding.btnRetry.setOnClickListener { retryPreflight(projectId, status.id) }
            }
            "FAILED", "STALE" -> {
                binding.tvStatus.text = buildFailureText(status)
                // 重试：仅 canRetry==true 显示（§清单 §6）
                binding.btnRow.isVisible = status.canRetry == true
                binding.btnRetry.isVisible = status.canRetry == true
                binding.btnRetry.setOnClickListener { retryPreflight(projectId, status.id) }
            }
            "REQUESTED", "DRY_RUN_QUEUED", "DRY_RUN_RUNNING" -> {
                binding.tvStatus.text = "预检中（${status.status}）…"
            }
            else -> binding.tvStatus.text = "预检状态：${status.status}"
        }
        return binding.root
    }

    /** 预检失败文案：INVALID_REQUEST 时优先 failureDetails，另拼 failureStage/workerCode/workerHttpStatus 便于排查 */
    private fun buildFailureText(status: MergeRequestPreflightDto): String {
        val sb = StringBuilder("预检失败")
        val details = status.failureDetails?.takeIf { it.isNotEmpty() }
        val reason = if (!details.isNullOrEmpty()) {
            details.joinToString("；") { d ->
                listOfNotNull(d.field, d.reason).joinToString(" ").ifBlank { d.value ?: "" }
            }
        } else {
            status.failureReason?.takeIf { it.isNotBlank() } ?: status.status
        }
        sb.append("：").append(reason)
        status.failureCode?.let { sb.append("（$it）") }
        val stage = status.failureStage?.takeIf { it.isNotBlank() }
        val worker = listOfNotNull(
            status.workerCode?.takeIf { it.isNotBlank() },
            status.workerHttpStatus?.takeIf { it.isNotBlank() }
        ).joinToString("/")
        if (!stage.isNullOrBlank() || worker.isNotBlank()) {
            sb.append(" ").append(listOfNotNull(stage, worker).joinToString(" "))
        }
        return sb.toString()
    }

    /** 重试预检：仅 canRetry=true 且 CQ_REJECTED/FAILED 时调用；创建新 Dry Run，成功后刷新 */
    private fun retryPreflight(projectId: String, preflightId: String?) {
        if (preflightId.isNullOrBlank()) {
            Toast.makeText(requireContext(), "缺少预检记录", Toast.LENGTH_SHORT).show()
            return
        }
        viewLifecycleOwner.lifecycleScope.launch {
            taskRepository.retryMergeRequestPreflight(projectId, preflightId, UUID.randomUUID().toString())
                .onSuccess {
                    Toast.makeText(requireContext(), "已重新发起预检，正在运行 DryRun", Toast.LENGTH_SHORT).show()
                    item?.let { loadPreflight(it) }
                }
                .onFailure { e ->
                    Toast.makeText(requireContext(), "重试预检失败：${e.message}", Toast.LENGTH_LONG).show()
                }
        }
    }

    /** 申请 MR 预检：统一入口，启动 Dry Run（计划 §4.2：前端只调预检申请接口，不自行拼装 targetBranch/Testset） */
    private fun requestPreflight(projectId: String, taskId: String, itemDto: DeliveryItemDto) {
        val repositoryId = itemDto.repositoryDeliveries?.firstOrNull()?.repositoryId
        if (repositoryId == null) {
            Toast.makeText(requireContext(), "缺少仓库信息", Toast.LENGTH_SHORT).show()
            return
        }
        viewLifecycleOwner.lifecycleScope.launch {
            taskRepository.requestMergeRequestPreflight(
                projectId, taskId, repositoryId, UUID.randomUUID().toString()
            ).onSuccess {
                Toast.makeText(requireContext(), "已申请预检，正在运行 DryRun", Toast.LENGTH_SHORT).show()
                item?.let { loadPreflight(it) }
            }.onFailure { e ->
                Toast.makeText(requireContext(), "申请预检失败：${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    /** CQ+1：对 Dry Run 提交审批，通过后服务端自动创建 MR（计划 §4.3）。
     *  无权限（当前用户是 MR 申请者或任务发起者）时点击只弹 Toast，禁止发起请求。
     *  权限判断基于 MR 申请者/任务创建者与当前用户比对（不依赖不可靠的 canCqApprove 字段）。 */
    private fun doCqApprove(projectId: String, dryRunId: String?) {
        if (dryRunId.isNullOrBlank()) {
            Toast.makeText(requireContext(), "暂无可审批的 DryRun", Toast.LENGTH_SHORT).show()
            return
        }
        val taskId = item?.source?.taskId
        viewLifecycleOwner.lifecycleScope.launch {
            // 权限检查：MR 申请者或任务发起人不能给自己审批 CQ+1（§46），无权限直接 Toast，不发请求
            val me = com.example.qgent.data.SessionStore.user()?.id
            val requester = taskId?.let {
                taskRepository.getTaskMergeRequestPreflight(projectId, it)
                    .getOrNull()?.firstOrNull()?.requestedByUserId
            }
            val author = taskId?.let {
                taskRepository.getTaskDetail(projectId, it).getOrNull()?.createdByUser?.id
            }
            val noPermission = me != null && (me == requester || me == author)
            android.util.Log.d("Preflight", "doCqApprove me=$me requester=$requester author=$author noPermission=$noPermission")
            if (noPermission) {
                Toast.makeText(requireContext(), "无权操作 CQ+1", Toast.LENGTH_LONG).show()
                return@launch
            }
            android.util.Log.d("Preflight", "doCqApprove SEND projectId=$projectId dryRunId=$dryRunId")
            taskRepository.dryRunCqApprove(projectId, dryRunId, null, UUID.randomUUID().toString())
                .onSuccess {
                    android.util.Log.d("Preflight", "doCqApprove SUCCESS")
                    Toast.makeText(requireContext(), "已提交 CQ+1", Toast.LENGTH_SHORT).show()
                    // 操作成功：重查最新预检状态（多仓库容器由 loadPreflight 重建）
                    item?.let { loadPreflight(it) }
                }
                .onFailure { e ->
                    android.util.Log.e("Preflight", "doCqApprove FAILED: ${e.message}")
                    // 后端 403 PREFLIGHT_CQ_AUTHOR_FORBIDDEN（发起人审批自己）：统一无权限提示
                    if (e is com.example.qgent.data.model.ApiException &&
                        e.code == "PREFLIGHT_CQ_AUTHOR_FORBIDDEN"
                    ) {
                        Toast.makeText(requireContext(), "无权操作 CQ+1", Toast.LENGTH_LONG).show()
                        return@onFailure
                    }
                    Toast.makeText(requireContext(), "CQ+1 失败：${e.message}", Toast.LENGTH_LONG).show()
                }
        }
    }

    /** 拒绝 CQ：对 Dry Run 提交拒绝并给出修改意见（reason 必填；不会创建 MR）。
     *  无权限（当前用户是任务发起人/作者）时点击只弹 Toast，禁止发起请求。 */
    private fun doCqReject(projectId: String, dryRunId: String?) {
        if (dryRunId.isNullOrBlank()) {
            Toast.makeText(requireContext(), "暂无可拒绝的 DryRun", Toast.LENGTH_SHORT).show()
            return
        }
        val taskId = item?.source?.taskId
        val input = android.widget.EditText(requireContext()).apply {
            hint = "拒绝原因 / 修改意见（必填）"
            textSize = 15f
        }
        com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
            .setTitle("拒绝 CQ")
            .setView(input)
            .setNegativeButton("取消", null)
            .setPositiveButton("确认拒绝") { _, _ ->
                val reason = input.text.toString().trim()
                if (reason.isEmpty()) {
                    Toast.makeText(requireContext(), "请填写拒绝原因", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                viewLifecycleOwner.lifecycleScope.launch {
                    // 权限检查：MR 申请者或任务发起人不能给自己拒绝 CQ（§46），无权限直接 Toast，不发请求
                    val me = com.example.qgent.data.SessionStore.user()?.id
                    val requester = taskId?.let {
                        taskRepository.getTaskMergeRequestPreflight(projectId, it)
                            .getOrNull()?.firstOrNull()?.requestedByUserId
                    }
                    val author = taskId?.let {
                        taskRepository.getTaskDetail(projectId, it).getOrNull()?.createdByUser?.id
                    }
                    val noPermission = me != null && (me == requester || me == author)
                    android.util.Log.d("Preflight", "doCqReject me=$me requester=$requester author=$author noPermission=$noPermission")
                    if (noPermission) {
                        Toast.makeText(requireContext(), "无权操作 CQ+1", Toast.LENGTH_LONG).show()
                        return@launch
                    }
                    android.util.Log.d("Preflight", "doCqReject SEND projectId=$projectId dryRunId=$dryRunId reason=$reason")
                    taskRepository.dryRunCqReject(projectId, dryRunId, reason, UUID.randomUUID().toString())
                        .onSuccess {
                            android.util.Log.d("Preflight", "doCqReject SUCCESS")
                            Toast.makeText(requireContext(), "已拒绝 CQ", Toast.LENGTH_SHORT).show()
                            // 操作成功：重查最新预检状态（多仓库容器由 loadPreflight 重建）
                            item?.let { loadPreflight(it) }
                        }
                        .onFailure { e ->
                            android.util.Log.e("Preflight", "doCqReject FAILED: ${e.message}")
                            Toast.makeText(requireContext(), "拒绝 CQ 失败：${e.message}", Toast.LENGTH_LONG).show()
                        }
                }
            }
            .show()
    }

    private fun bind(it: DeliveryItemDto) {
        // 标题：任务展示码 + 标题
        val title = it.source?.taskDisplayCode?.let { "($it) " }.orEmpty() + it.title
        binding.tvTitle.text = title

        // 状态
        val displayStatus = it.displayStatus ?: "-"
        val statusColor = when (displayStatus) {
            "FAILED", "REJECTED" -> R.color.exit_red
            "DELIVERED", "ACCEPTED" -> R.color.teal
            else -> R.color.status_yellow
        }
        binding.tvStatus.backgroundTintList = android.content.res.ColorStateList.valueOf(requireContext().getColor(statusColor))
        binding.tvStatus.text = displayStatus

        // Diff 统计
        binding.tvDiffStats.text = "Diff ${it.filesChanged} 个文件 · +${it.additions} / -${it.deletions}"

        // Review / Delivery 状态；交付被拒绝（REJECTED）展示拒绝意见 + 回群继续修改入口；
        // diff 未确认（reviewStatus 非 ACCEPTED）时不显示"是否交付"
        val review = it.reviewStatus ?: "-"
        val delivery = it.deliveryStatus ?: "-"
        binding.tvReviewDelivery.text = when {
            it.reviewStatus == "REJECTED" -> "已拒绝"
            it.reviewStatus == "ACCEPTED" -> "Review $review · Delivery $delivery"
            else -> "Review $review"   // diff 未确认，不显示是否交付
        }
        val rejectedReason = it.reviewReason?.takeIf { r -> r.isNotBlank() }
        binding.tvRejectedReason.isVisible = it.reviewStatus == "REJECTED" && rejectedReason != null
        if (rejectedReason != null) binding.tvRejectedReason.text = "已拒绝：$rejectedReason"
        val requirementGroup = it.requirementGroup
        val groupId = requirementGroup?.id?.takeIf { g -> g.isNotBlank() }
        binding.btnContinueModify.isVisible = it.reviewStatus == "REJECTED" && groupId != null
        if (groupId != null) {
            binding.btnContinueModify.setOnClickListener {
                findNavController().navigate(
                    R.id.chatDetailFragment,
                    bundleOf(
                        "groupName" to requirementGroup?.name,
                        "groupId" to groupId
                    )
                )
            }
        }

        // 逐仓库交付进度
        fillRepos(it.repositoryDeliveries.orEmpty())

        // 关联任务入口
        val taskId = it.source?.taskId
        binding.tvTaskEntry.isVisible = !taskId.isNullOrBlank()
        if (!taskId.isNullOrBlank()) {
            binding.tvTaskEntry.setOnClickListener {
                findNavController().navigate(
                    R.id.action_deliveryItemDetail_to_taskDetail,
                    bundleOf(
                        com.example.qgent.ui.tasks.TaskDetailFragment.ARG_TASK_ID to taskId,
                        com.example.qgent.ui.tasks.TaskDetailFragment.ARG_PROJECT_ID to (mainViewModel.currentProjectId().orEmpty())
                    )
                )
            }
        }
    }

    private fun fillRepos(repos: List<DeliveryRepositoryDeliveryDto>) {
        binding.containerRepos.removeAllViews()
        repos.forEach { rd ->
            val status = rd.deliveryStatus ?: "-"
            val reason = rd.failureReason?.takeIf { it.isNotBlank() }?.let { "（$it）" }.orEmpty()
            val row = com.example.qgent.databinding.ItemRepoProgressRowBinding.inflate(
                layoutInflater, binding.containerRepos, false
            )
            row.tvRepoStatus.text = "• ${rd.repositoryName ?: rd.repositoryId ?: "仓库"}：$status$reason"
            binding.containerRepos.addView(row.root)
        }
    }


    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        const val ARG_ITEM_JSON = "itemJson"

        /** 序列化 DeliveryItemDto 供跳转传参 */
        fun toJson(item: DeliveryItemDto): String = Gson().toJson(item)
    }
}
