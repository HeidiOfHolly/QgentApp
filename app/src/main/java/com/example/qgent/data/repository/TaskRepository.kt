package com.example.qgent.data.repository

import com.example.qgent.data.model.ActivityDto
import com.example.qgent.data.model.CqActionRequest
import com.example.qgent.data.model.CreateDryRunRequest
import com.example.qgent.data.model.CreateDryRunResponse
import com.example.qgent.data.model.CreateMergeRequestRequest
import com.example.qgent.data.model.CreateMergeRequestResponse
import com.example.qgent.data.model.DeliveryItemDto
import com.example.qgent.data.model.DiffFileResponseDto
import com.example.qgent.data.model.DryRunListItemDto
import com.example.qgent.data.model.DryRunReportDto
import com.example.qgent.data.model.DryRunRetryResponse
import com.example.qgent.data.model.EmptyBody
import com.example.qgent.data.model.MergeRequestCheckDto
import com.example.qgent.data.model.MergeRequestDetailDto
import com.example.qgent.data.model.MergeRequestDto
import com.example.qgent.data.model.MergeRequestPreflightDto
import com.example.qgent.data.model.RequestMergeRequestPreflightRequest
import com.example.qgent.data.model.RequestMergeRequestPreflightResponse
import com.example.qgent.data.model.TestsetResponseDto
import com.example.qgent.data.model.MergeRequestReviewDto
import com.example.qgent.data.model.TaskCreateRequest
import com.example.qgent.data.model.TaskDetailDto
import com.example.qgent.data.model.TaskListItemDto
import com.example.qgent.data.model.TaskRunDetailListItemDto
import com.example.qgent.data.model.TaskRunListItemDto
import com.example.qgent.data.model.TaskRunLogEntryDto
import com.example.qgent.data.model.TaskStepListItemDto
import com.example.qgent.data.model.TaskTriggerRequest
import com.example.qgent.data.model.WorkspaceDiffPreviewDto
import com.example.qgent.data.model.WorkspaceDiffPreviewFileDto

/** 任务/动态/MR 仓库：任务页三列表查询（§16 任务 / §19.4 动态 / §13 MR） */
interface TaskRepository {
    /** 创建任务（§11.3：从需求群创建，可创建新 Workspace 或复用前序） */
    suspend fun createTask(
        projectId: String,
        request: TaskCreateRequest,
        idempotencyKey: String
    ): Result<TaskListItemDto>

    /** 契约 §7：从群消息显式触发 Task（POST .../messages/{messageId}/trigger-task） */
    suspend fun triggerTask(
        projectId: String,
        groupId: String,
        messageId: String,
        request: TaskTriggerRequest,
        idempotencyKey: String
    ): Result<Unit>

    /** 查询项目可见任务（§16.1），支持需求群/状态/发起人/仓库过滤，分页返回单页数据 */
    suspend fun getTasks(
        projectId: String,
        groupId: String? = null,
        status: String? = null,
        createdBy: String? = null,
        repositoryId: String? = null,
        cursor: String? = null,
        limit: Int = 20
    ): Result<List<TaskListItemDto>>

    /** 团队最近动态（§19.4），按 createdAt 倒序 */
    suspend fun getActivities(
        teamId: String,
        cursor: String? = null,
        limit: Int = 20
    ): Result<List<ActivityDto>>

    /** 查询项目关联 MR（§13） */
    suspend fun getMergeRequests(
        projectId: String,
        cursor: String? = null,
        limit: Int = 20
    ): Result<List<MergeRequestDto>>

    /** 项目级按 Agent 查询 TaskRun（§20.6） */
    suspend fun getTaskRuns(
        projectId: String,
        agentId: String,
        cursor: String? = null,
        limit: Int = 20
    ): Result<List<TaskRunListItemDto>>

    /** 任务详情（§16.2） */
    suspend fun getTaskDetail(projectId: String, taskId: String): Result<TaskDetailDto>

    /** Workspace 实时 Diff Preview 详情（执行中累计工作树变化；无则 404/空） */
    suspend fun getWorkspaceDiffPreview(projectId: String, taskId: String, revision: Int? = null): Result<WorkspaceDiffPreviewDto>

    /** Workspace 实时 Diff Preview 文件列表（按仓库分组） */
    suspend fun getWorkspaceDiffPreviewFiles(projectId: String, taskId: String, revision: Int? = null): Result<List<WorkspaceDiffPreviewFileDto>>

    /** 任务步骤列表（§16.3） */
    suspend fun getTaskSteps(projectId: String, taskId: String): Result<List<TaskStepListItemDto>>

    /** 任务运行列表（§16.4，每次执行的 TaskRun） */
    suspend fun getTaskRunsOfTask(projectId: String, taskId: String): Result<List<TaskRunDetailListItemDto>>

    /** 任务运行执行日志（§12.2） */
    suspend fun getTaskRunLogs(projectId: String, taskRunId: String, cursor: String? = null, limit: Int = 100): Result<List<TaskRunLogEntryDto>>

    /** MR 详情（§13，含 diffId） */
    suspend fun getMergeRequestDetail(projectId: String, mergeRequestId: String): Result<MergeRequestDetailDto>

    /** 交付中心：CODE 交付物列表（GET /delivery-items；支持需求群/发起人/仓库筛选，§20.1） */
    suspend fun getDeliveryItems(
        projectId: String,
        type: String? = null,
        groupId: String? = null,
        createdBy: String? = null,
        repositoryId: String? = null,
        cursor: String? = null,
        limit: Int = 100
    ): Result<List<DeliveryItemDto>>

    /** Testset 列表（§10：项目成员可查，支持仓库/状态过滤） */
    suspend fun getTestsets(projectId: String, repositoryId: String? = null, status: String? = null): Result<List<TestsetResponseDto>>

    /** MR 门禁检查（TESTSET/AI_REVIEW/DRY_RUN/CQ_PLUS_ONE） */
    suspend fun getMergeRequestChecks(projectId: String, mergeRequestId: String): Result<List<MergeRequestCheckDto>>

    /** MR 人工/AI 审查摘要（CQ+1 审批人/决定） */
    suspend fun getMergeRequestReviews(projectId: String, mergeRequestId: String): Result<List<MergeRequestReviewDto>>

    /** 提交 CQ+1（Project Member 非作者） */
    suspend fun cqApprove(projectId: String, mergeRequestId: String, reason: String?, idempotencyKey: String): Result<Unit>

    /** 拒绝 CQ（必填 reason） */
    suspend fun cqReject(projectId: String, mergeRequestId: String, reason: String, idempotencyKey: String): Result<Unit>

    /** 通过门禁后合并（Project Admin） */
    suspend fun mergeRequest(projectId: String, mergeRequestId: String, idempotencyKey: String): Result<Unit>

    /** 触发从 GitHub 同步 MR 状态 */
    suspend fun syncMergeRequest(projectId: String, mergeRequestId: String, idempotencyKey: String): Result<Unit>

    /** 创建 MR（§13：基于已接受 Diff；DIFF_FIRST 手动 / MR_FIRST 幂等补偿） */
    suspend fun createMergeRequest(
        projectId: String,
        taskId: String,
        repositoryId: String,
        targetBranch: String,
        title: String,
        idempotencyKey: String
    ): Result<CreateMergeRequestResponse>

    /** 申请 MR 预检（§46.2：启动 Dry Run，202） */
    suspend fun requestMergeRequestPreflight(
        projectId: String,
        taskId: String,
        repositoryId: String,
        idempotencyKey: String
    ): Result<RequestMergeRequestPreflightResponse>

    /** 按 Task 查询全部仓库 MR 预检状态（§46.7） */
    suspend fun getTaskMergeRequestPreflight(projectId: String, taskId: String): Result<List<MergeRequestPreflightDto>>

    /** 单条 MR 预检详情（§46.7：页面刷新/SSE 断线后恢复状态） */
    suspend fun getMergeRequestPreflight(projectId: String, preflightId: String): Result<MergeRequestPreflightDto>

    /** Dry Run 预检 CQ+1（§27.10：通过后触发自动创建 MR） */
    suspend fun dryRunCqApprove(projectId: String, dryRunId: String, reason: String?, idempotencyKey: String): Result<Unit>

    /** Dry Run 预检 CQ 拒绝（reason 必填；不会创建 MR） */
    suspend fun dryRunCqReject(projectId: String, dryRunId: String, reason: String, idempotencyKey: String): Result<Unit>

    /** 创建 Dry Run（§12.4/§32.1：repositoryId/sourceRef/targetBranch，taskId 可选；202 受理） */
    suspend fun createDryRun(
        projectId: String,
        repositoryId: String,
        sourceRef: String,
        targetBranch: String,
        taskId: String?,
        idempotencyKey: String
    ): Result<CreateDryRunResponse>

    /** Dry Run 报告（§12.4/§32.1：冲突/测试汇总；排队/运行时 report 为 null） */
    suspend fun getDryRunReport(projectId: String, dryRunId: String): Result<DryRunReportDto>

    /** 重试 Dry Run（§32.2：返回新的 Dry Run ID，原报告只读） */
    suspend fun retryDryRun(projectId: String, dryRunId: String, idempotencyKey: String): Result<DryRunRetryResponse>

    /** Dry Run 历史列表（§38.3：筛选 + 游标分页） */
    suspend fun getDryRuns(
        projectId: String,
        repositoryId: String? = null,
        taskId: String? = null,
        status: String? = null,
        targetBranch: String? = null,
        createdByUserId: String? = null,
        cursor: String? = null,
        limit: Int = 20
    ): Result<List<DryRunListItemDto>>

    /** 读取 Diff 文件与代码行（§12.3） */
    suspend fun getDiffFiles(projectId: String, diffId: String): Result<List<DiffFileResponseDto>>

    /** 取消任务（§11.3，POST cancel，202 异步受理；终态返回 409 TASK_NOT_CANCELLABLE） */
    suspend fun cancelTask(projectId: String, taskId: String, idempotencyKey: String): Result<Unit>

    /** 替换步骤执行 Agent（§11.3，仅 PENDING 步骤可替换） */
    suspend fun replaceAgent(
        projectId: String,
        taskId: String,
        stepId: String,
        agentId: String,
        idempotencyKey: String
    ): Result<TaskStepListItemDto>
}
