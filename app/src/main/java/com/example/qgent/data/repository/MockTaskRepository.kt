package com.example.qgent.data.repository

import com.example.qgent.data.model.ActivityDto
import com.example.qgent.data.model.DiffFileResponseDto
import com.example.qgent.data.model.MergeRequestDetailDto
import com.example.qgent.data.model.MergeRequestDto
import com.example.qgent.data.model.TaskCreateRequest
import com.example.qgent.data.model.TaskDetailDto
import com.example.qgent.data.model.TaskListItemDto
import com.example.qgent.data.model.TaskRunDetailListItemDto
import com.example.qgent.data.model.TaskRunListItemDto
import com.example.qgent.data.model.TaskRunLogEntryDto
import com.example.qgent.data.model.TaskStepListItemDto

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
            deliveryMode = "STANDARD",
            requirementGroup = null,
            createdByUser = null,
            repositories = null,
            executionSummary = null,
            attention = null,
            createdAt = "2026-08-16T08:00:00Z",
            updatedAt = "2026-08-16T08:00:00Z"
        )
    )

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

    override suspend fun getTaskSteps(projectId: String, taskId: String): Result<List<TaskStepListItemDto>> =
        Result.success(emptyList())

    override suspend fun getTaskRunsOfTask(projectId: String, taskId: String): Result<List<TaskRunDetailListItemDto>> =
        Result.success(emptyList())

    override suspend fun getTaskRunLogs(projectId: String, taskRunId: String, cursor: String?, limit: Int): Result<List<TaskRunLogEntryDto>> =
        Result.failure(UnsupportedOperationException("mock 不支持运行日志"))

    override suspend fun getMergeRequestDetail(projectId: String, mergeRequestId: String): Result<MergeRequestDetailDto> =
        Result.failure(UnsupportedOperationException("mock 不支持 MR 详情"))

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
