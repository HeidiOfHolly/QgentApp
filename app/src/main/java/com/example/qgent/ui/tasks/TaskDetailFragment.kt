package com.example.qgent.ui.tasks

import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.core.os.bundleOf
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.example.qgent.QgentApp
import com.example.qgent.R
import com.example.qgent.data.model.ApiException
import com.example.qgent.data.model.RepositoryDeliveryDto
import com.example.qgent.data.model.TaskDetailDto
import com.example.qgent.data.model.TaskRunDetailListItemDto
import com.example.qgent.data.model.TaskStepListItemDto
import com.example.qgent.data.model.formatFullTime
import com.example.qgent.data.model.parseRfc3339
import com.example.qgent.data.model.toDiffFile
import com.example.qgent.data.repository.DiffRepository
import com.example.qgent.data.repository.TaskRepository
import com.example.qgent.databinding.FragmentTaskDetailBinding
import com.example.qgent.databinding.ItemRepoDeliveryBinding
import com.example.qgent.databinding.ItemTaskRunBinding
import com.example.qgent.databinding.ItemTaskStepBinding
import com.example.qgent.ui.common.OpenMrGuidance
import com.example.qgent.ui.diffreview.DiffReviewRules
import com.example.qgent.ui.personal.fillLinearLayout
import com.example.qgent.viewmodel.MainViewModel
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.UUID

/** 任务详情页：展示标题、需求、状态、仓库（§16.2） */
class TaskDetailFragment : Fragment() {

    private var _binding: FragmentTaskDetailBinding? = null
    private val binding get() = _binding!!

    private val taskRepository: TaskRepository
        get() = (requireActivity().application as QgentApp).container.taskRepository
    private val diffRepository: DiffRepository
        get() = (requireActivity().application as QgentApp).container.diffRepository
    private val mainViewModel: MainViewModel by activityViewModels {
        (requireActivity().application as QgentApp).container.mainViewModelFactory
    }

    private val taskId: String by lazy { arguments?.getString(ARG_TASK_ID).orEmpty() }
    private val projectId: String by lazy { arguments?.getString(ARG_PROJECT_ID).orEmpty() }

    private var eventStreamJob: Job? = null
    private var pollingJob: Job? = null

    /** 加载中指示器引用计数：detail/steps/runs 三个请求全部结束后隐藏 */
    private var loadingCount = 0

    /** 最近一次加载的 Task 详情（Diff 审核弹窗复用，避免重复传参） */
    private var lastDetail: TaskDetailDto? = null

    /** 最近一次加载的任务运行列表（失败原因展示复用；detail 与 runs 并发加载，需两者齐备再判断） */
    private var lastRuns = emptyList<TaskRunDetailListItemDto>()

    /** 正在请求日志的运行 ID，避免同一日志入口被连续点击后发出重复请求。 */
    private var loadingRunLogId: String? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentTaskDetailBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.ivBack.setOnClickListener { findNavController().navigateUp() }
        binding.btnCancelTask.setOnClickListener { confirmCancelTask() }
        // 仅首次进入界面时显示加载指示器
        loadDetail(showIndicator = true)
    }

    override fun onResume() {
        super.onResume()
        startEventStream()
        startPolling()
    }

    override fun onPause() {
        super.onPause()
        stopEventStream()
        stopPolling()
    }

    /** 轮询兜底：SSE 偶发断连时任务进度仍能刷新（3s 一次） */
    private fun startPolling() {
        if (pollingJob?.isActive == true) return
        pollingJob = viewLifecycleOwner.lifecycleScope.launch {
            while (true) {
                delay(POLL_INTERVAL_MS)
                loadDetail()
            }
        }
    }

    private fun stopPolling() {
        pollingJob?.cancel()
        pollingJob = null
    }

    /** 项目级 SSE：任务状态/步骤/运行/Diff 事件到达 → 刷新详情（进度实时可见） */
    private fun startEventStream() {
        if (projectId.isEmpty()) return
        val stream = (requireActivity().application as QgentApp).container.projectEventStream
        stream.startProject(projectId)
        if (eventStreamJob == null) {
            eventStreamJob = viewLifecycleOwner.lifecycleScope.launch {
                stream.events.collect { event ->
                    when (event.type) {
                        com.example.qgent.data.sse.SseEventType.TASK_UPDATED,
                        com.example.qgent.data.sse.SseEventType.TASK_STEP_UPDATED,
                        com.example.qgent.data.sse.SseEventType.TASK_RUN_UPDATED,
                        com.example.qgent.data.sse.SseEventType.TASK_RUN_STEP_PROGRESS,
                        com.example.qgent.data.sse.SseEventType.DIFF_CREATED,
                        // 交付相关：开始/逐仓库更新/完成/失败 → 刷新 Task 详情 + DiffReview（含逐仓库进度）
                        com.example.qgent.data.sse.SseEventType.DELIVERY_STARTED,
                        com.example.qgent.data.sse.SseEventType.DELIVERY_REPOSITORY_UPDATED,
                        com.example.qgent.data.sse.SseEventType.DELIVERY_FAILED,
                        com.example.qgent.data.sse.SseEventType.DELIVERY_COMPLETED,
                        com.example.qgent.data.sse.SseEventType.DIFF_REVIEW_SKIPPED,
                        // MR 状态/分支锁定解锁变化 → 刷新任务详情（交付状态、分支、MR 相关展示保持一致）
                        com.example.qgent.data.sse.SseEventType.MERGE_REQUEST_UPDATED,
                        com.example.qgent.data.sse.SseEventType.WORK_BRANCH_UPDATED -> {
                            // 无代码变更（FINAL_DIFF_EMPTY）：记录当前任务，详情页展示空态（MainViewModel 跨页共享，重进不丢失）
                            val taskIdFromEvent = runCatching {
                                org.json.JSONObject(event.data).optString("taskId")
                            }.getOrNull()
                            if (taskIdFromEvent == taskId) mainViewModel.recordNoCodeChangeTask(taskId)
                            loadDetail()
                        }
                        // 旧 Diff 被后续修改取代：只刷新对应 Task，不能标记为无代码变更。
                        com.example.qgent.data.sse.SseEventType.DIFF_REVIEW_SUPERSEDED -> {
                            val taskIdFromEvent = runCatching {
                                org.json.JSONObject(event.data).optString("taskId")
                            }.getOrNull()
                            if (taskIdFromEvent == taskId) loadDetail()
                        }
                        // 实时 Diff Preview 更新（Coding 写入后）：只刷新 Preview，不刷新正式 Diff
                        com.example.qgent.data.sse.SseEventType.WORKSPACE_DIFF_PREVIEW_UPDATED -> {
                            val taskIdFromEvent = runCatching {
                                org.json.JSONObject(event.data).optString("taskId")
                            }.getOrNull()
                            if (taskIdFromEvent == taskId || taskIdFromEvent.isNullOrEmpty()) {
                                loadWorkspaceDiffPreview()
                            }
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

    /** 显示加载中指示器（引用计数，重复调用只增加计数） */
    private fun showLoading() {
        loadingCount++
        if (loadingCount == 1) binding.loading.isVisible = true
    }

    /** 隐藏加载中指示器：全部请求结束后才真正隐藏 */
    private fun hideLoading() {
        loadingCount--
        if (loadingCount <= 0) {
            loadingCount = 0
            binding.loading.isVisible = false
        }
    }

    /** 取消任务确认弹窗 */
    private fun confirmCancelTask() {
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.cancel_task)
            .setMessage(R.string.cancel_task_confirm)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.cancel_task) { _, _ ->
                cancelTask()
            }
            .show()
    }

    /** 调用取消任务接口（§11.3，202 异步受理）；终态 409 专门提示 */
    private fun cancelTask() {
        if (projectId.isEmpty() || taskId.isEmpty()) return
        binding.btnCancelTask.isEnabled = false
        viewLifecycleOwner.lifecycleScope.launch {
            taskRepository.cancelTask(projectId, taskId, UUID.randomUUID().toString())
                .onSuccess {
                    Toast.makeText(requireContext(), R.string.cancel_task_success, Toast.LENGTH_SHORT).show()
                    loadDetail()
                }
                .onFailure { e ->
                    binding.btnCancelTask.isEnabled = true
                    val message = if (e is ApiException && e.code == "TASK_NOT_CANCELLABLE") {
                        "该任务已处于终态，无法取消"
                    } else {
                        e.message ?: getString(R.string.cancel_task_failed)
                    }
                    Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
                    // 终态冲突：刷新详情让取消按钮按最新状态隐藏
                    if (e is ApiException && DiffReviewRules.isConflict(e.code)) {
                        loadDetail()
                    }
                }
        }
    }

    /**
     * 加载任务详情。
     * @param showIndicator true 时显示加载指示器（仅首次进入界面）；轮询/SSE 刷新传 false 不打扰。
     */
    private fun loadDetail(showIndicator: Boolean = false) {
        if (projectId.isEmpty() || taskId.isEmpty()) return
        if (showIndicator) showLoading()
        viewLifecycleOwner.lifecycleScope.launch {
            taskRepository.getTaskDetail(projectId, taskId)
                .onSuccess { detail ->
                    bind(detail)
                    if (showIndicator) hideLoading()
                }
                .onFailure { e ->
                    if (showIndicator) hideLoading()
                    Toast.makeText(requireContext(), e.message ?: "加载任务详情失败", Toast.LENGTH_SHORT).show()
                }
        }
        loadSteps(showIndicator)
        loadRuns(showIndicator)
        loadWorkspaceDiffPreview()
    }

    /**
     * 加载 Workspace 实时 Diff Preview（执行中累计工作树变化；无数据/失败时隐藏入口，不标记任务失败）。
     * 事件 workspace.diff-preview.updated 与任务详情刷新都会走到这里。
     */
    private fun loadWorkspaceDiffPreview() {
        if (projectId.isEmpty() || taskId.isEmpty()) return
        viewLifecycleOwner.lifecycleScope.launch {
            taskRepository.getWorkspaceDiffPreview(projectId, taskId)
                .onSuccess {
                    // 控件表面只展示功能名称；具体改动（文件/增删行）点击后弹窗展示
                    binding.tvPreviewEntry.isVisible = true
                    binding.tvPreviewEntry.text = getString(R.string.task_realtime_preview)
                    binding.tvPreviewEntry.setOnClickListener { showPreviewFilesDialog() }
                }
                .onFailure {
                    // 无 Preview（404）或接口暂不可用：隐藏入口，不打扰
                    binding.tvPreviewEntry.isVisible = false
                }
        }
    }

    /** 实时 Preview 文件列表弹窗：按仓库分组展示（repositoryPath/路径/变更类型/增删行） */
    private fun showPreviewFilesDialog() {
        if (projectId.isEmpty() || taskId.isEmpty()) return
        viewLifecycleOwner.lifecycleScope.launch {
            val files = taskRepository.getWorkspaceDiffPreviewFiles(projectId, taskId).getOrNull().orEmpty()
            val sb = StringBuilder("实时预览文件（累计工作树变化，非正式 Diff）\n\n")
            if (files.isEmpty()) {
                sb.append("暂无文件内容")
            } else {
                files.groupBy { it.repositoryPath ?: it.repositoryId ?: "仓库" }.forEach { (repo, list) ->
                    sb.append("📦 ").append(repo).append("\n")
                    list.forEach { f ->
                        val type = when (f.changeType) {
                            "ADDED", "A" -> "A"
                            "DELETED", "D" -> "D"
                            "RENAMED", "R" -> "R"
                            else -> "M"
                        }
                        sb.append("  ").append(f.path).append("   ").append(type)
                            .append("  +").append(f.additions).append(" -").append(f.deletions).append("\n")
                    }
                    sb.append("\n")
                }
            }
            val dialogBinding = com.example.qgent.databinding.DialogTextContentBinding.inflate(layoutInflater)
            dialogBinding.tvContent.text = sb.toString()
            androidx.appcompat.app.AlertDialog.Builder(requireContext())
                .setTitle("实时 Diff 预览")
                .setView(dialogBinding.root)
                .setPositiveButton("关闭", null)
                .show()
        }
    }

    /** 加载任务步骤列表（§16.3）并填充 */
    private fun loadSteps(showIndicator: Boolean = false) {
        if (showIndicator) showLoading()
        viewLifecycleOwner.lifecycleScope.launch {
            taskRepository.getTaskSteps(projectId, taskId)
                .onSuccess { steps ->
                    if (showIndicator) hideLoading()
                    binding.tvStepsEmpty.isVisible = steps.isEmpty()
                    fillLinearLayout(binding.rvSteps, steps, R.layout.item_task_step) { view, step ->
                        val item = ItemTaskStepBinding.bind(view)
                        item.tvStepTitle.text = step.title.ifEmpty { step.role }
                        item.tvStepStatus.text = stepStatusLabel(step.status)
                        item.tvStepStatus.setTextColor(view.context.getColor(taskStatusColorRes(step.status)))
                        // 仅 PENDING 步骤可替换 Agent（§11.3）
                        item.btnReplaceAgent.isVisible = step.status == "PENDING"
                        item.btnReplaceAgent.setOnClickListener { showReplaceAgentDialog(step) }
                    }
                }
                .onFailure { e ->
                    if (showIndicator) hideLoading()
                    Toast.makeText(requireContext(), "加载任务步骤失败：${e.message}", Toast.LENGTH_SHORT).show()
                }
        }
    }

    /** 加载任务运行列表（§16.4）并填充 */
    private fun loadRuns(showIndicator: Boolean = false) {
        if (showIndicator) showLoading()
        viewLifecycleOwner.lifecycleScope.launch {
            taskRepository.getTaskRunsOfTask(projectId, taskId)
                .onSuccess { runs ->
                    if (showIndicator) hideLoading()
                    binding.tvRunsEmpty.isVisible = runs.isEmpty()
                    fillLinearLayout(binding.rvRuns, runs, R.layout.item_task_run) { view, run ->
                        val item = ItemTaskRunBinding.bind(view)
                        item.tvRunTitle.text = run.taskStepTitle ?: run.role
                        item.tvRunStatus.text = run.statusSummary ?: runStatusLabel(run.status)
                        item.tvRunStatus.setTextColor(view.context.getColor(taskStatusColorRes(run.status)))
                        item.tvRunAgent.text = run.agent?.name ?: run.agentId
                        // 查看执行日志：失败/完成的运行可看具体执行过程（§12.2）
                        val logLoading = loadingRunLogId == run.id
                        item.tvViewLogs.isEnabled = !logLoading
                        item.tvViewLogs.text = if (logLoading) "加载中..." else getString(R.string.view_task_run_logs)
                        item.tvViewLogs.setOnClickListener { showRunLogs(run, item.tvViewLogs) }
                    }
                    lastRuns = runs
                    updateFailureReason()
                }
                .onFailure { e ->
                    if (showIndicator) hideLoading()
                    Toast.makeText(requireContext(), "加载任务运行失败：${e.message}", Toast.LENGTH_SHORT).show()
                }
        }
    }

    /** 查看任务运行执行日志：拉取后弹窗展示（后端日志接口 §12.2/§33，可定位失败原因） */
    private fun showRunLogs(run: TaskRunDetailListItemDto, actionView: TextView) {
        if (loadingRunLogId == run.id) return
        loadingRunLogId = run.id
        actionView.isEnabled = false
        actionView.text = "加载中..."
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                taskRepository.getTaskRunLogs(projectId, run.id)
                    .onSuccess { logs ->
                        if (logs.isEmpty()) {
                            // §33.2：日志为空不代表执行失败（Planner 启动阶段失败时无日志记录）
                            Toast.makeText(requireContext(), R.string.task_run_logs_empty, Toast.LENGTH_SHORT).show()
                            return@onSuccess
                        }
                        val sb = StringBuilder()
                        logs.forEach { entry ->
                            // §33.2 展示约定：EXECUTION=执行日志 / SYSTEM=生命周期日志 / TERMINAL=结果摘要
                            val nodeTag = entry.node?.takeIf { it.isNotBlank() }?.let { "[$it] " }.orEmpty()
                            val typeTag = when (entry.entryType) {
                                "EXECUTION" -> "执行"
                                "SYSTEM" -> "系统"
                                "TERMINAL" -> "结果"
                                else -> null
                            }?.let { "[$it] " }.orEmpty()
                            // 时间戳为 UTC RFC3339（§2），转成本地时间展示，避免直接显示 UTC 原始串
                            val time = formatFullTime(parseRfc3339(entry.timestamp))
                            sb.append(time).append("  ").append(typeTag).append(nodeTag)
                                .append(entry.content).append("\n")
                        }
                        val dialogBinding = com.example.qgent.databinding.DialogTextContentBinding.inflate(layoutInflater)
                        dialogBinding.tvContent.text = sb.toString()
                        AlertDialog.Builder(requireContext())
                            .setTitle(R.string.task_run_logs_title)
                            .setView(dialogBinding.root)
                            .setPositiveButton(R.string.close, null)
                            .show()
                    }
                    .onFailure { e ->
                        Toast.makeText(requireContext(), "加载日志失败：${e.message}", Toast.LENGTH_SHORT).show()
                    }
            } finally {
                loadingRunLogId = null
                actionView.isEnabled = true
                actionView.setText(R.string.view_task_run_logs)
            }
        }
    }

    private fun stepStatusLabel(status: String): String = when (status) {
        "PENDING" -> "待执行"
        "RUNNING" -> "执行中"
        "SUCCEEDED" -> "成功"
        "FAILED" -> "失败"
        "SKIPPED" -> "已跳过"
        else -> status
    }

    private fun runStatusLabel(status: String): String = when (status) {
        "QUEUED" -> "排队中"
        "RUNNING" -> "执行中"
        "SUCCEEDED" -> "成功"
        "FAILED" -> "失败"
        "WAITING_INPUT" -> "等待输入"
        "WAITING_APPROVAL" -> "等待审批"
        "BLOCKED" -> "已阻塞"
        "CANCELLED" -> "已取消"
        else -> status
    }

    private fun bind(detail: TaskDetailDto) {
        lastDetail = detail
        binding.tvTitle.text = detail.title
        binding.tvStatus.text = statusLabel(detail.status)
        // 状态标签背景色随任务状态变化（与任务卡片状态色一致：失败红/完成teal/取消灰/其余黄）；
        // 文字保持白色，背景 tint 保留 bg_status_tag 圆角
        binding.tvStatus.backgroundTintList = android.content.res.ColorStateList.valueOf(
            requireContext().getColor(taskStatusColorRes(detail.status))
        )
        binding.tvRequirement.text = detail.requirement ?: detail.requirementSummary ?: "暂无需求描述"
        // 仓库：任务详情在 workspace.repositories（§16.2），顶层 repositories 仅列表项有；
        // 兼容两种情况，任一非空即展示
        val repos = (detail.workspace?.repositories ?: detail.repositories).orEmpty()
        binding.tvRepo.text = getString(
            R.string.task_detail_repo,
            repos.joinToString { it.fullName.ifEmpty { it.name } }
        )
        // 取消按钮：能力位 canCancel 优先；缺省按终态状态隐藏（终态不可取消，避免"取消失败"）
        val cancellable = detail.capabilities?.canCancel ?: (detail.status !in TERMINAL_STATUSES)
        binding.btnCancelTask.isVisible = cancellable
        binding.btnCancelTask.isEnabled = true
        bindDelivery(detail)
        updateFailureReason()
    }

    /**
     * 任务失败原因（需求与任务步骤之间）：任务 FAILED / DELIVERY_FAILED 统一在此展示
     * （对齐后端 45 节：任务详情接口与诊断接口统一组装 code+summary，summary 优先持久化脱敏 failureReason）。
     * 原因优先级：任务 statusReason.summary（后端统一组装）→ title → 失败运行 statusReason → 兜底；
     * DELIVERY_FAILED 且 statusReason 缺失（旧后端）时回退 diffReviewSummary 的交付失败字段。
     * GIT_BRANCH_NOT_FOUND（基线分支不存在，§45.2 特例）：展示真实原因 + 「重新发起任务」跳需求群。
     */
    private fun updateFailureReason() {
        val detail = lastDetail
        val failed = detail?.status == "FAILED" || detail?.status == "DELIVERY_FAILED"
        binding.layoutFailureReason.isVisible = failed
        if (!failed || detail == null) return
        val taskReason = detail.statusReason
        val failedRun = lastRuns.firstOrNull { it.status == "FAILED" }
        val reason = taskReason?.summary
            ?: taskReason?.title
            ?: failedRun?.statusReason?.summary
            ?: failedRun?.statusReason?.title
            ?: failedRun?.statusSummary
            ?: extractDeliveryFailedReason(detail.diffReviewSummary)
            ?: getString(R.string.task_failure_generic)
        binding.tvFailureTitle.text = getString(
            if (detail.status == "DELIVERY_FAILED") R.string.task_delivery_failed_title else R.string.task_failure_title
        )
        binding.tvFailureReason.text = reason
        // GIT_BRANCH_NOT_FOUND：基线分支不存在 → 「重新发起任务」跳需求群（§45.2 特例，跳群后重新发起）
        val branchMissing = taskReason?.failureCode == "GIT_BRANCH_NOT_FOUND"
        val requirementGroup = detail.requirementGroup
        binding.btnRestartTask.isVisible = branchMissing && requirementGroup != null
        if (branchMissing && requirementGroup != null) {
            binding.btnRestartTask.setOnClickListener {
                findNavController().navigate(
                    R.id.chatDetailFragment,
                    bundleOf(
                        "groupName" to requirementGroup.name,
                        "groupId" to requirementGroup.id
                    )
                )
            }
        }
    }

    /**
     * 交付相关展示（MR_FIRST B 方案）：
     * - MR_FIRST → 「自动交付」标签 + deliveryReason 判定理由
     * - 批次级交付状态稳定文案（进行中/完成/部分失败）
     * - 失败原因 + 重试（PARTIALLY_DELIVERED / FAILED 或任务 DELIVERY_FAILED，能力位优先）
     * - 逐仓库交付进度（repositoryDeliveries[]，来自 diff-review 批次）
     */
    private fun bindDelivery(detail: TaskDetailDto) {
        val diffSummary = detail.diffReviewSummary
        val deliveryStatus = extractStringField(diffSummary, "deliveryStatus")
        val reviewStatus = extractStringField(diffSummary, "reviewStatus")
        val superseded = DiffReviewRules.isSuperseded(reviewStatus)

        // 无代码变更任务（FINAL_DIFF_EMPTY）：无 Diff Review 可确认（§15.6.4/§20.3）。
        // 判定：收到过 diff-review.skipped SSE（内存）OR 任务成功但无可用 Diff 批次
        // （diffReviewSummary.available != true，冷启动/重进详情页也能正确识别，不依赖 SSE）。
        val hasReviewBatch = superseded || (diffSummary?.isJsonObject == true &&
            diffSummary.asJsonObject.get("available")?.takeIf { it.isJsonPrimitive }?.asBoolean == true)
        val noCode = mainViewModel.isNoCodeChangeTask(taskId) ||
            (detail.status == "SUCCEEDED" && !hasReviewBatch)

        // 交付模式：MR_FIRST=⚡MR 前自动预检 / DIFF_FIRST=📦代码交付，deliveryReason 作为副文案（§15）。
        // MR_FIRST 仅任务真正进入交付阶段（待 Diff 确认及之后）才展示标签：deliveryMode 在规划阶段已持久化，
        // 任务尚未进入交付（规划中/待执行/执行中/失败/已取消）时展示会让人误以为已自动交付成功。
        // 无代码任务不显示「代码交付」标签（未实际交付代码，只展示"无代码变更"空态）
        val mrFirst = DiffReviewRules.isMrFirst(detail.deliveryMode)
        val mrFirstInDeliveryPhase = DiffReviewRules.showDeliveryModeLabel(detail.deliveryMode, detail.status)
        val showDeliveryMode = mrFirstInDeliveryPhase || (detail.deliveryMode == "DIFF_FIRST" && !noCode)
        binding.tvDeliveryMode.isVisible = showDeliveryMode
        binding.tvDeliveryMode.text = when {
            mrFirstInDeliveryPhase -> getString(R.string.task_delivery_auto)
            detail.deliveryMode == "DIFF_FIRST" && !noCode -> getString(R.string.task_delivery_code)
            else -> ""
        }
        if (mrFirstInDeliveryPhase && !detail.deliveryReason.isNullOrBlank()) {
            binding.tvDeliveryMode.text = "${binding.tvDeliveryMode.text}（${detail.deliveryReason}）"
        }
        binding.tvNoCodeChange.isVisible = noCode && detail.status == "SUCCEEDED"

        // 批次级交付状态总览（稳定文案；失败状态交给 tvDeliveryError 行展示）
        val overview = when (deliveryStatus) {
            "NOT_STARTED" -> "未开始"
            "DELIVERING" -> "交付中"
            "DELIVERED" -> "交付完成"
            "PARTIALLY_DELIVERED" -> "部分仓库成功，部分仓库失败"
            else -> null
        }
        binding.tvDeliveryStatus.isVisible = overview != null
        binding.tvDeliveryStatus.text = overview

        // 失败原因统一走 layoutFailureReason（updateFailureReason，后端 45 节统一 code+summary），
        // 不再从 diffReviewSummary 猜字段单独渲染交付失败行（避免同一页面两条链路返回矛盾失败信息；
        // tvDeliveryError 控件已随布局移除）

        // 重试交付：部分失败/失败（能力位 canRetryDelivery 优先）；无代码任务不提供重试（无批次可重试）
        val canRetry = !noCode && !superseded && DiffReviewRules.canRetryDelivery(
            deliveryStatus, detail.status, detail.capabilities?.canRetryDelivery
        )
        binding.btnRetryDelivery.isVisible = canRetry
        binding.btnRetryDelivery.isEnabled = true
        binding.btnRetryDelivery.setOnClickListener { retryDiffDelivery() }

        // D3：diffReviewSummary.available=true → 渲染总 Diff 审核入口（available=false 不展示）；
        // 无代码任务即使后端残留 available=true 也不展示确认入口（§20.3）
        val available = !noCode && hasReviewBatch
        binding.tvDiffReviewEntry.isVisible = available
        if (available) {
            val summaryDiffId = extractStringField(diffSummary, "diffId")
            binding.tvDiffReviewEntry.text = if (superseded) {
                getString(R.string.diff_review_superseded)
            } else {
                getString(R.string.task_diff_review)
            }
            binding.tvDiffReviewEntry.setOnClickListener { showDiffReviewDialog(summaryDiffId) }
        }

        // 逐仓库交付进度：MR_FIRST 任务或有交付状态时拉 diff-review 批次渲染
        if (mrFirst || deliveryStatus != null) loadDiffReview()
    }

    /**
     * D3：Diff 审核入口点击 → 弹 Diff Review 对话框
     * （批次摘要 + 交付状态 + 首个 Diff 文件内容 + 按规则显示确认/拒绝/重试按钮）。
     */
    private fun showDiffReviewDialog(preferredDiffId: String?) {
        val detail = lastDetail ?: return
        if (projectId.isEmpty() || taskId.isEmpty()) return
        viewLifecycleOwner.lifecycleScope.launch {
            val batch = diffRepository.getTaskDiffReview(projectId, taskId).getOrNull()
            val reviewStatus = batch?.reviewStatus
                ?: extractStringField(detail.diffReviewSummary, "reviewStatus")
            val confirmationSource = batch?.confirmationSource
                ?: extractStringField(detail.diffReviewSummary, "confirmationSource")
            val deliveryStatus = batch?.deliveryStatus
                ?: extractStringField(detail.diffReviewSummary, "deliveryStatus")
            val superseded = DiffReviewRules.isSuperseded(reviewStatus)

            val sb = StringBuilder("任务状态：").append(statusLabel(detail.status)).append("\n\n")
            DiffReviewRules.reviewStatusCaption(reviewStatus)?.let {
                sb.append("审核状态：").append(it).append("\n")
            }
            DiffReviewRules.deliveryStatusCaption(deliveryStatus)?.let {
                sb.append("交付状态：").append(it).append("\n")
            }
            if (batch != null) {
                sb.append("📦 Diff Review 批次：仓库 ").append(batch.repositoryCount)
                    .append(" · 文件 ").append(batch.filesChanged)
                    .append(" · +").append(batch.additions).append(" -").append(batch.deletions).append("\n")
                batch.repositoryDeliveries?.forEach { rd ->
                    sb.append("• ").append(rd.repositoryName ?: rd.repositoryId)
                        .append("：").append(DiffReviewRules.repositoryDeliveryCaption(rd.deliveryStatus)).append("\n")
                }
                sb.append("\n")
            }
            // 代表性 Diff 文件内容（D3：diffReviewSummary.diffId 或批次内首个 Diff）
            val diffId = preferredDiffId ?: batch?.diffs?.firstOrNull()?.id
            if (!diffId.isNullOrBlank()) {
                val files = diffRepository.getDiffFiles(projectId, diffId).getOrNull().orEmpty().map { it.toDiffFile() }
                files.forEach { file ->
                    sb.append("📄 ").append(file.fileName).append("  (+${file.additions} -${file.deletions})").append("\n")
                    file.lines.forEach { line ->
                        val marker = when (line.type) {
                            com.example.qgent.model.DiffLineType.ADD -> "+"
                            com.example.qgent.model.DiffLineType.DELETE -> "-"
                            else -> " "
                        }
                        sb.append(marker).append(" ").append(line.text).append("\n")
                    }
                    sb.append("\n")
                }
                if (files.isEmpty()) sb.append("（该 Diff 无文件内容）\n")
            }
            val dialogBinding = com.example.qgent.databinding.DialogTextContentBinding.inflate(layoutInflater)
            dialogBinding.tvContent.text = if (sb.isBlank()) "暂无 Diff 内容" else sb.toString()

            val builder = MaterialAlertDialogBuilder(requireContext())
                .setTitle(if (superseded) {
                    "${getString(R.string.diff_review_superseded)} · ${detail.title}"
                } else if (reviewStatus == "ACCEPTED") {
                    "${DiffReviewRules.acceptedCaption(confirmationSource)} · ${detail.title}"
                } else {
                    "Diff 审核 · ${detail.title}"
                })
                .setView(dialogBinding.root)
            // Diff 审核：仅任务发起人或 Project Admin 可确认/拒绝（后端能力位派生，§16.2）。
            // 能力位缺省按 true 兜底（DIFF_FIRST 旧后端回归），确认/拒绝分别判断。
            val canDecide = DiffReviewRules.canConfirmOrReject(reviewStatus, confirmationSource)
            val canConfirm = canDecide && (detail.capabilities?.canConfirmDiffReview ?: true)
            val canReject = canDecide && (detail.capabilities?.canRejectDiffReview ?: true)
            if (canConfirm || canReject) {
                if (canReject) {
                    builder.setNegativeButton(R.string.reject_diff) { _, _ -> rejectTaskDiffReview() }
                }
                if (canConfirm) {
                    builder.setPositiveButton(R.string.confirm_diff) { _, _ -> confirmTaskDiffReview() }
                }
            } else {
                builder.setPositiveButton(R.string.close, null)
            }
            val canRetry = !superseded && DiffReviewRules.canRetryDelivery(
                deliveryStatus, detail.status, detail.capabilities?.canRetryDelivery
            )
            if (canRetry) {
                builder.setNeutralButton(R.string.retry_delivery) { _, _ -> retryDiffDelivery() }
            }
            builder.show()
        }
    }

    /** 确认整个最终 Diff 批次（POST .../diff-review/confirm，§12.3；Idempotency-Key 必填） */
    private fun confirmTaskDiffReview() {
        if (projectId.isEmpty() || taskId.isEmpty()) return
        viewLifecycleOwner.lifecycleScope.launch {
            diffRepository.confirmDiffReview(projectId, taskId, UUID.randomUUID().toString())
                .onSuccess {
                    Toast.makeText(requireContext(), "已确认 Diff，Agent 提交合并请求等待审核", Toast.LENGTH_LONG).show()
                    loadDetail()
                }
                .onFailure { e ->
                    // §27.4：分支存在未合并 MR 时引导查看 MR（不得转为普通失败/DELIVERY_FAILED）
                    if (e is ApiException && DiffReviewRules.isOpenMrBlocked(e.code)) {
                        OpenMrGuidance.show(requireContext(), projectId, e)
                    } else if (e is ApiException && DiffReviewRules.isConflict(e.code)) {
                        loadDetail()
                    } else {
                        Toast.makeText(requireContext(), "确认失败：${e.message}", Toast.LENGTH_LONG).show()
                    }
                }
        }
    }

    /** 拒绝整个最终 Diff 批次（POST .../diff-review/reject，§12.3；可填原因） */
    private fun rejectTaskDiffReview() {
        val input = layoutInflater.inflate(R.layout.dialog_reject_diff, null) as EditText
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.reject_diff)
            .setView(input)
            .setPositiveButton(R.string.confirm) { _, _ ->
                val reason = input.text.toString().trim().ifEmpty { null }
                viewLifecycleOwner.lifecycleScope.launch {
                    diffRepository.rejectDiffReview(projectId, taskId, reason, UUID.randomUUID().toString())
                        .onSuccess {
                            Toast.makeText(requireContext(), "已拒绝 Diff，Agent 重新修改", Toast.LENGTH_LONG).show()
                            loadDetail()
                        }
                        .onFailure { e ->
                            if (e is ApiException && DiffReviewRules.isOpenMrBlocked(e.code)) {
                                OpenMrGuidance.show(requireContext(), projectId, e)
                            } else if (e is ApiException && DiffReviewRules.isConflict(e.code)) {
                                loadDetail()
                            } else {
                                Toast.makeText(requireContext(), "拒绝失败：${e.message}", Toast.LENGTH_LONG).show()
                            }
                        }
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /**
     * 拉取 diff-review 批次渲染逐仓库交付进度。
     * 404 DIFF_REVIEW_NOT_FOUND（或 HTTP_404）：任务无批次，按无进度处理，不当作空批次。
     */
    private fun loadDiffReview() {
        if (projectId.isEmpty() || taskId.isEmpty()) return
        viewLifecycleOwner.lifecycleScope.launch {
            diffRepository.getTaskDiffReview(projectId, taskId)
                .onSuccess { batch ->
                    renderRepoDeliveries(batch?.repositoryDeliveries.orEmpty())
                }
                .onFailure { e ->
                    if (e is ApiException && (e.code == "DIFF_REVIEW_NOT_FOUND" || e.code == "HTTP_404")) {
                        renderRepoDeliveries(emptyList())
                    } else {
                        Log.w("TaskDetail", "加载交付进度失败: ${e.message}")
                        renderRepoDeliveries(emptyList())
                    }
                }
        }
    }

    /** 渲染逐仓库交付进度列表（名称/状态/失败原因/MR 链接/更新时间） */
    private fun renderRepoDeliveries(deliveries: List<RepositoryDeliveryDto>) {
        binding.rvRepoDeliveries.removeAllViews()
        if (deliveries.isEmpty()) return
        deliveries.forEach { rd ->
            val item = ItemRepoDeliveryBinding.inflate(layoutInflater, binding.rvRepoDeliveries, false)
            item.tvRepoName.text = rd.repositoryName ?: rd.repositoryId
            item.tvRepoStatus.text = DiffReviewRules.repositoryDeliveryCaption(rd.deliveryStatus)
            item.tvRepoStatus.setTextColor(requireContext().getColor(repoDeliveryColorRes(rd.deliveryStatus)))
            // 失败原因：仅后端返回的脱敏文本
            val err = rd.failureReason?.takeIf { it.isNotBlank() }
            item.tvRepoError.isVisible = !err.isNullOrBlank()
            item.tvRepoError.text = err
            // MR 链接：仅 MR_CREATED 且 webUrl 非空时渲染，点击系统浏览器打开
            val mr = rd.mergeRequest
            val url = mr?.webUrl?.takeIf { it.isNotBlank() }
            val linkText = DiffReviewRules.mrLinkText(mr)
            item.tvRepoMrLink.isVisible = linkText != null
            item.tvRepoMrLink.text = linkText
            item.tvRepoMrLink.setOnClickListener { openExternalUrl(url) }
            // 更新时间
            val ts = rd.updatedAt
            item.tvRepoUpdatedAt.isVisible = !ts.isNullOrBlank()
            item.tvRepoUpdatedAt.text = ts
            binding.rvRepoDeliveries.addView(item.root)
        }
    }

    /** 逐仓库交付状态颜色：FAILED 红、MR_CREATED teal、COMMITTED 黄、其余灰 */
    private fun repoDeliveryColorRes(status: String?): Int = when (status) {
        "FAILED" -> R.color.exit_red
        "MR_CREATED" -> R.color.teal
        "COMMITTED" -> R.color.status_yellow
        else -> R.color.text_secondary
    }

    /** 用系统浏览器/外部打开 MR 链接（webUrl 为空时调用方不渲染） */
    private fun openExternalUrl(url: String?) {
        if (url.isNullOrBlank()) return
        runCatching {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        }.onFailure {
            Toast.makeText(requireContext(), "无法打开链接", Toast.LENGTH_SHORT).show()
        }
    }

    /** 重试逐仓库交付（POST .../tasks/{taskId}/diff-review/retry-delivery，§12.3；Idempotency-Key 必填） */
    private fun retryDiffDelivery() {
        if (projectId.isEmpty() || taskId.isEmpty()) return
        binding.btnRetryDelivery.isEnabled = false
        viewLifecycleOwner.lifecycleScope.launch {
            diffRepository.retryDiffDelivery(projectId, taskId, UUID.randomUUID().toString())
                .onSuccess {
                    Toast.makeText(requireContext(), "已重试交付，等待 Agent 再次交付", Toast.LENGTH_LONG).show()
                    binding.btnRetryDelivery.isEnabled = false
                    loadDetail()
                }
                .onFailure { e ->
                    // §27.4：分支存在未合并 MR 时引导查看 MR
                    if (e is ApiException && DiffReviewRules.isOpenMrBlocked(e.code)) {
                        binding.btnRetryDelivery.isEnabled = true
                        OpenMrGuidance.show(requireContext(), projectId, e)
                    } else if (e is ApiException && DiffReviewRules.isConflict(e.code)) {
                        // 409 冲突：刷新 Task 与 DiffReview 后再决定按钮状态（B 方案）
                        binding.btnRetryDelivery.isEnabled = true
                        loadDetail()
                    } else {
                        binding.btnRetryDelivery.isEnabled = true
                        Toast.makeText(requireContext(), "重试交付失败：${e.message}", Toast.LENGTH_LONG).show()
                    }
                }
        }
    }

    /** 从 diffReviewSummary JsonElement 读取指定字符串字段（无则 null） */
    private fun extractStringField(json: com.google.gson.JsonElement?, key: String): String? {
        if (json == null || !json.isJsonObject) return null
        val v = json.asJsonObject.get(key)
        return if (v != null && !v.isJsonNull) v.asString else null
    }

    /** 从 diffReviewSummary JsonElement 解析交付失败原因（兼容多个字段名） */
    private fun extractDeliveryFailedReason(diffSummary: com.google.gson.JsonElement?): String? {
        if (diffSummary == null || !diffSummary.isJsonObject) return null
        val obj = diffSummary.asJsonObject
        val candidates = listOf("deliveryFailedReason", "failedReason", "deliveryError", "errorMessage", "message")
        for (key in candidates) {
            val v = obj.get(key)
            if (v != null && !v.isJsonNull && !v.isJsonObject && !v.isJsonArray) {
                val s = v.asString
                if (s.isNotBlank()) return s
            }
        }
        return null
    }

    /** 替换步骤执行 Agent：弹出可选 Agent 列表（当前团队 Agent），选中后调用替换接口 */
    private fun showReplaceAgentDialog(step: TaskStepListItemDto) {
        val agents = mainViewModel.agents.value.orEmpty()
        if (agents.isEmpty()) {
            Toast.makeText(requireContext(), "暂无可选 Agent", Toast.LENGTH_SHORT).show()
            return
        }
        val names = agents.map { it.name }.toTypedArray()
        AlertDialog.Builder(requireContext())
            .setTitle("替换 ${step.title.ifEmpty { step.role }} 的执行 Agent")
            .setItems(names) { _, which ->
                replaceStepAgent(step, agents[which].id)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /** 调用替换 Agent 接口（§11.3），成功后刷新步骤列表 */
    private fun replaceStepAgent(step: TaskStepListItemDto, agentId: String) {
        if (projectId.isEmpty() || taskId.isEmpty()) return
        viewLifecycleOwner.lifecycleScope.launch {
            taskRepository.replaceAgent(projectId, taskId, step.id, agentId, UUID.randomUUID().toString())
                .onSuccess {
                    Toast.makeText(requireContext(), "已替换执行 Agent", Toast.LENGTH_SHORT).show()
                    loadSteps()
                }
                .onFailure { e ->
                    Toast.makeText(requireContext(), e.message ?: "替换失败，请稍后重试", Toast.LENGTH_SHORT).show()
                }
        }
    }

    private fun statusLabel(status: String): String =
        TaskListViewModel.STATUS_OPTIONS.firstOrNull { it.first == status }?.second ?: status

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        const val ARG_TASK_ID = "taskId"
        const val ARG_PROJECT_ID = "projectId"
        private const val POLL_INTERVAL_MS = 3_000L

        /** 终态/不可取消状态：不显示取消按钮（避免对已取消任务再次取消导致"取消失败"） */
        private val TERMINAL_STATUSES = setOf("SUCCEEDED", "FAILED", "DELIVERY_FAILED", "CANCELLED", "CANCELLING")
    }
}
