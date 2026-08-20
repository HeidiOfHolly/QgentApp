package com.example.qgent.data.repository

import com.example.qgent.data.model.ActivityDto
import com.example.qgent.data.model.CreateDryRunResponse
import com.example.qgent.data.model.CreateMergeRequestResponse
import com.example.qgent.data.model.DiffFileResponseDto
import com.example.qgent.data.model.DeliveryItemDto
import com.example.qgent.data.model.DryRunListItemDto
import com.example.qgent.data.model.DryRunReportDto
import com.example.qgent.data.model.DryRunRetryResponse
import com.example.qgent.data.model.MergeRequestCheckDto
import com.example.qgent.data.model.MergeRequestReviewDto
import com.example.qgent.data.model.MergeRequestDetailDto
import com.example.qgent.data.model.MergeRequestDto
import com.example.qgent.data.model.MergeRequestPreflightDto
import com.example.qgent.data.model.RequestMergeRequestPreflightResponse
import com.example.qgent.data.model.TestsetResponseDto
import com.example.qgent.data.model.TaskCreateRequest
import com.example.qgent.data.model.TaskDetailDto
import com.example.qgent.data.model.WorkspaceDiffPreviewDto
import com.example.qgent.data.model.WorkspaceDiffPreviewFileDto
import com.example.qgent.data.model.TaskListItemDto
import com.example.qgent.data.model.TaskRunDetailListItemDto
import com.example.qgent.data.model.TaskRunListItemDto
import com.example.qgent.data.model.TaskRunLogEntryDto
import com.example.qgent.data.model.TaskStepListItemDto
import com.example.qgent.data.model.TaskTriggerRequest

/** mock 任务/动态/MR：返回空列表，任务页展示空态 */
class MockTaskRepository : TaskRepository {

    override suspend fun createTask(
        projectId: String,
        request: TaskCreateRequest,
        idempotencyKey: String
    ): Result<TaskListItemDto> = Result.success(
        TaskListItemDto(
            id = "mock-task-${System.currentTimeMillis()}",
            displayCode = "TASK-mock",
            projectId = projectId,
            title = request.title,
            requirementSummary = request.requirement.take(50),
            status = "PLANNING",
            deliveryMode = "DIFF_FIRST",
            requirementGroup = null,
            createdByUser = null,
            repositories = null,
            executionSummary = null,
            attention = null,
            createdAt = "2026-08-16T08:00:00Z",
            updatedAt = "2026-08-16T08:00:00Z"
        )
    )

    override suspend fun triggerTask(
        projectId: String,
        groupId: String,
        messageId: String,
        request: TaskTriggerRequest,
        idempotencyKey: String
    ): Result<Unit> = Result.success(Unit)

    override suspend fun getTasks(
        projectId: String,
        groupId: String?,
        status: String?,
        createdBy: String?,
        repositoryId: String?,
        cursor: String?,
        limit: Int
    ): Result<List<TaskListItemDto>> =
        Result.success(emptyList())

    override suspend fun getActivities(teamId: String, cursor: String?, limit: Int): Result<List<ActivityDto>> =
        Result.success(emptyList())

    override suspend fun getMergeRequests(projectId: String, cursor: String?, limit: Int): Result<List<MergeRequestDto>> =
        Result.success(emptyList())

    override suspend fun getTaskRuns(projectId: String, agentId: String, cursor: String?, limit: Int): Result<List<TaskRunListItemDto>> =
        Result.success(emptyList())

    override suspend fun getTaskDetail(projectId: String, taskId: String): Result<TaskDetailDto> =
        Result.failure(UnsupportedOperationException("mock 不支持任务详情"))

    override suspend fun getWorkspaceDiffPreview(projectId: String, taskId: String, revision: Int?): Result<WorkspaceDiffPreviewDto> =
        Result.failure(UnsupportedOperationException("mock 不支持实时 Preview"))

    override suspend fun getWorkspaceDiffPreviewFiles(projectId: String, taskId: String, revision: Int?): Result<List<WorkspaceDiffPreviewFileDto>> =
        Result.failure(UnsupportedOperationException("mock 不支持实时 Preview 文件"))

    override suspend fun getTaskSteps(projectId: String, taskId: String): Result<List<TaskStepListItemDto>> =
        Result.success(emptyList())

    override suspend fun getTaskRunsOfTask(projectId: String, taskId: String): Result<List<TaskRunDetailListItemDto>> =
        Result.success(emptyList())

    override suspend fun getTaskRunLogs(projectId: String, taskRunId: String, cursor: String?, limit: Int): Result<List<TaskRunLogEntryDto>> =
        Result.failure(UnsupportedOperationException("mock 不支持运行日志"))

    override suspend fun getMergeRequestDetail(projectId: String, mergeRequestId: String): Result<MergeRequestDetailDto> =
        Result.failure(UnsupportedOperationException("mock 不支持合并请求详情"))

    override suspend fun getDeliveryItems(
        projectId: String,
        type: String?,
        groupId: String?,
        createdBy: String?,
        repositoryId: String?,
        cursor: String?,
        limit: Int
    ): Result<List<DeliveryItemDto>> =
        Result.success(emptyList())

    override suspend fun getTestsets(projectId: String, repositoryId: String?, status: String?): Result<List<TestsetResponseDto>> =
        Result.success(emptyList())

    override suspend fun getMergeRequestChecks(projectId: String, mergeRequestId: String): Result<List<MergeRequestCheckDto>> =
        Result.success(emptyList())

    override suspend fun getMergeRequestReviews(projectId: String, mergeRequestId: String): Result<List<MergeRequestReviewDto>> =
        Result.success(emptyList())

    override suspend fun cqApprove(projectId: String, mergeRequestId: String, reason: String?, idempotencyKey: String): Result<Unit> =
        Result.success(Unit)

    override suspend fun cqReject(projectId: String, mergeRequestId: String, reason: String, idempotencyKey: String): Result<Unit> =
        Result.success(Unit)

    override suspend fun mergeRequest(projectId: String, mergeRequestId: String, idempotencyKey: String): Result<Unit> =
        Result.success(Unit)

    override suspend fun syncMergeRequest(projectId: String, mergeRequestId: String, idempotencyKey: String): Result<Unit> =
        Result.success(Unit)

    override suspend fun createMergeRequest(
        projectId: String,
        taskId: String,
        repositoryId: String,
        targetBranch: String,
        title: String,
        idempotencyKey: String
    ): Result<CreateMergeRequestResponse> =
        Result.failure(UnsupportedOperationException("mock 不支持创建 MR"))

    override suspend fun requestMergeRequestPreflight(
        projectId: String,
        taskId: String,
        repositoryId: String,
        idempotencyKey: String
    ): Result<RequestMergeRequestPreflightResponse> =
        Result.failure(UnsupportedOperationException("mock 不支持申请 MR 预检"))

    override suspend fun getTaskMergeRequestPreflight(projectId: String, taskId: String): Result<List<MergeRequestPreflightDto>> =
        Result.failure(UnsupportedOperationException("mock 不支持查询 MR 预检"))

    override suspend fun getMergeRequestPreflight(projectId: String, preflightId: String): Result<MergeRequestPreflightDto> =
        Result.failure(UnsupportedOperationException("mock 不支持查询 MR 预检"))

    override suspend fun dryRunCqApprove(projectId: String, dryRunId: String, reason: String?, idempotencyKey: String): Result<Unit> =
        Result.failure(UnsupportedOperationException("mock 不支持 DryRun CQ+1"))

    override suspend fun createDryRun(
        projectId: String,
        repositoryId: String,
        sourceRef: String,
        targetBranch: String,
        taskId: String?,
        idempotencyKey: String
    ): Result<CreateDryRunResponse> =
        Result.failure(UnsupportedOperationException("mock 不支持创建 Dry Run"))

    override suspend fun getDryRunReport(projectId: String, dryRunId: String): Result<DryRunReportDto> =
        Result.failure(UnsupportedOperationException("mock 不支持 Dry Run 报告"))

    override suspend fun retryDryRun(projectId: String, dryRunId: String, idempotencyKey: String): Result<DryRunRetryResponse> =
        Result.failure(UnsupportedOperationException("mock 不支持重试 Dry Run"))

    override suspend fun getDryRuns(
        projectId: String,
        repositoryId: String?,
        taskId: String?,
        status: String?,
        targetBranch: String?,
        createdByUserId: String?,
        cursor: String?,
        limit: Int
    ): Result<List<DryRunListItemDto>> =
        Result.failure(UnsupportedOperationException("mock 不支持 Dry Run 列表"))

    override suspend fun getDiffFiles(projectId: String, diffId: String): Result<List<DiffFileResponseDto>> =
        Result.failure(UnsupportedOperationException("mock 不支持 diff"))

    override suspend fun cancelTask(projectId: String, taskId: String, idempotencyKey: String): Result<Unit> =
        Result.failure(UnsupportedOperationException("mock 不支持取消任务"))

    override suspend fun replaceAgent(
        projectId: String,
        taskId: String,
        stepId: String,
        agentId: String,
        idempotencyKey: String
    ): Result<TaskStepListItemDto> =
        Result.failure(UnsupportedOperationException("mock 不支持替换 Agent"))
}
