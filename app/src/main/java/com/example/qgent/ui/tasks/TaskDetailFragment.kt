package com.example.qgent.ui.tasks

import android.app.AlertDialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.example.qgent.QgentApp
import com.example.qgent.R
import com.example.qgent.data.model.ApiException
import com.example.qgent.data.model.TaskDetailDto
import com.example.qgent.data.model.TaskRunDetailListItemDto
import com.example.qgent.data.model.TaskStepListItemDto
import com.example.qgent.data.repository.TaskRepository
import com.example.qgent.databinding.FragmentTaskDetailBinding
import com.example.qgent.databinding.ItemTaskRunBinding
import com.example.qgent.databinding.ItemTaskStepBinding
import com.example.qgent.ui.personal.fillLinearLayout
import com.example.qgent.viewmodel.MainViewModel
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
    private val mainViewModel: MainViewModel by activityViewModels {
        (requireActivity().application as QgentApp).container.mainViewModelFactory
    }

    private val taskId: String by lazy { arguments?.getString(ARG_TASK_ID).orEmpty() }
    private val projectId: String by lazy { arguments?.getString(ARG_PROJECT_ID).orEmpty() }

    private var eventStreamJob: Job? = null
    private var pollingJob: Job? = null

    /** 收到 diff-review.skipped（reason=FINAL_DIFF_EMPTY）的任务：展示"已完成，无代码变更"空态（文档 §15.6.4/§20.3） */
    private val noCodeChangeTaskIds = mutableSetOf<String>()

    /** 加载中指示器引用计数：detail/steps/runs 三个请求全部结束后隐藏 */
    private var loadingCount = 0

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
                        com.example.qgent.data.sse.SseEventType.DELIVERY_REPOSITORY_UPDATED,
                        com.example.qgent.data.sse.SseEventType.DELIVERY_FAILED,
                        com.example.qgent.data.sse.SseEventType.DELIVERY_COMPLETED,
                        com.example.qgent.data.sse.SseEventType.DIFF_REVIEW_SKIPPED -> {
                            // 无代码变更（FINAL_DIFF_EMPTY）：记录当前任务，详情页展示空态
                            if (event.type == com.example.qgent.data.sse.SseEventType.DIFF_REVIEW_SKIPPED) {
                                val taskIdFromEvent = runCatching {
                                    org.json.JSONObject(event.data).optString("taskId")
                                }.getOrNull()
                                if (taskIdFromEvent == taskId) noCodeChangeTaskIds.add(taskId)
                            }
                            loadDetail()
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
        viewLifecycleOwner.lifecycleScope.launch {
            taskRepository.cancelTask(projectId, taskId, UUID.randomUUID().toString())
                .onSuccess {
                    Toast.makeText(requireContext(), R.string.cancel_task_success, Toast.LENGTH_SHORT).show()
                }
                .onFailure { e ->
                    val message = if (e is ApiException && e.code == "TASK_NOT_CANCELLABLE") {
                        "该任务已处于终态，无法取消"
                    } else {
                        e.message ?: getString(R.string.cancel_task_failed)
                    }
                    Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
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
                        item.tvViewLogs.setOnClickListener { showRunLogs(run) }
                    }
                }
                .onFailure { e ->
                    if (showIndicator) hideLoading()
                    Toast.makeText(requireContext(), "加载任务运行失败：${e.message}", Toast.LENGTH_SHORT).show()
                }
        }
    }

    /** 查看任务运行执行日志：拉取后弹窗展示（后端日志接口 §12.2，可定位失败原因） */
    private fun showRunLogs(run: TaskRunDetailListItemDto) {
        viewLifecycleOwner.lifecycleScope.launch {
            taskRepository.getTaskRunLogs(projectId, run.id)
                .onSuccess { logs ->
                    if (logs.isEmpty()) {
                        Toast.makeText(requireContext(), R.string.task_run_logs_empty, Toast.LENGTH_SHORT).show()
                        return@onSuccess
                    }
                    val sb = StringBuilder()
                    logs.forEach { entry ->
                        sb.append(entry.timestamp).append("  ").append(entry.content).append("\n")
                    }
                    val scroll = ScrollView(requireContext())
                    val tv = TextView(requireContext()).apply {
                        text = sb.toString()
                        textSize = 12f
                        setTextIsSelectable(true)
                        setPadding(48, 40, 48, 40)
                    }
                    scroll.addView(
                        tv,
                        ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    )
                    AlertDialog.Builder(requireContext())
                        .setTitle(R.string.task_run_logs_title)
                        .setView(scroll)
                        .setPositiveButton(R.string.close, null)
                        .show()
                }
                .onFailure { e ->
                    Toast.makeText(requireContext(), "加载日志失败：${e.message}", Toast.LENGTH_SHORT).show()
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
        binding.tvTitle.text = detail.title
        binding.tvStatus.text = statusLabel(detail.status)
        binding.tvRequirement.text = detail.requirement ?: detail.requirementSummary ?: "暂无需求描述"
        binding.tvRepo.text = getString(
            R.string.task_detail_repo,
            detail.repositories?.joinToString { it.fullName.ifEmpty { it.name } }.orEmpty()
        )
        // 交付模式：MR_FIRST=自动交付 / DIFF_FIRST=代码交付（文档 §15），deliveryReason 作为副文案
        val mrFirst = detail.deliveryMode == "MR_FIRST"
        binding.tvDeliveryMode.isVisible = mrFirst || detail.deliveryMode == "DIFF_FIRST"
        binding.tvDeliveryMode.text = when {
            mrFirst -> getString(R.string.task_delivery_auto)
            detail.deliveryMode == "DIFF_FIRST" -> getString(R.string.task_delivery_code)
            else -> ""
        }
        if (mrFirst && !detail.deliveryReason.isNullOrBlank()) {
            binding.tvDeliveryMode.text = "${binding.tvDeliveryMode.text}（${detail.deliveryReason}）"
        }
        // 无代码变更空态：仅当收到 diff-review.skipped（FINAL_DIFF_EMPTY）且任务已完成时展示（文档 §20.3）
        binding.tvNoCodeChange.isVisible = detail.status == "SUCCEEDED" && taskId in noCodeChangeTaskIds
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
    }
}
