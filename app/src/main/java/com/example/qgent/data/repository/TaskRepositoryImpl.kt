package com.example.qgent.data.repository

import com.example.qgent.data.api.QgApiService
import com.example.qgent.data.model.ActivityDto
import com.example.qgent.data.model.DiffFileResponseDto
import com.example.qgent.data.model.MergeRequestDetailDto
import com.example.qgent.data.model.MergeRequestDto
import com.example.qgent.data.model.ReplaceAgentRequest
import com.example.qgent.data.model.TaskCreateRequest
import com.example.qgent.data.model.TaskDetailDto
import com.example.qgent.data.model.TaskListItemDto
import com.example.qgent.data.model.TaskRunDetailListItemDto
import com.example.qgent.data.model.TaskRunListItemDto
import com.example.qgent.data.model.TaskRunLogEntryDto
import com.example.qgent.data.model.TaskStepListItemDto
import com.example.qgent.data.model.TaskTriggerRequest
import com.example.qgent.data.model.toDataOrThrow
import com.example.qgent.data.model.toUnitOrThrow

class TaskRepositoryImpl(private val service: QgApiService) : TaskRepository {

    override suspend fun createTask(
        projectId: String,
        request: TaskCreateRequest,
        idempotencyKey: String
    ): Result<TaskListItemDto> = apiCall {
        service.createTask(projectId, idempotencyKey, request).toDataOrThrow()
    }

    override suspend fun triggerTask(
        projectId: String,
        groupId: String,
        messageId: String,
        request: TaskTriggerRequest,
        idempotencyKey: String
    ): Result<Unit> = apiCall {
        // 响应 data 恒为 null，成功以 HTTP 200 为准
        service.triggerTask(projectId, groupId, messageId, idempotencyKey, request).toUnitOrThrow()
    }

    override suspend fun getTasks(
        projectId: String,
        groupId: String?,
        status: String?,
        createdBy: String?,
        repositoryId: String?,
        cursor: String?,
        limit: Int
    ): Result<List<TaskListItemDto>> =
        apiCall {
            service.getTasks(
                projectId,
                groupId = groupId,
                status = status,
                createdBy = createdBy,
                repositoryId = repositoryId,
                cursor = cursor,
                limit = limit
            ).toDataOrThrow()
        }

    override suspend fun getActivities(teamId: String, cursor: String?, limit: Int): Result<List<ActivityDto>> =
        apiCall { service.getActivities(teamId, cursor = cursor, limit = limit).toDataOrThrow() }

    override suspend fun getMergeRequests(projectId: String, cursor: String?, limit: Int): Result<List<MergeRequestDto>> =
        apiCall { service.getMergeRequests(projectId, cursor = cursor, limit = limit).toDataOrThrow() }

    override suspend fun getTaskRuns(projectId: String, agentId: String, cursor: String?, limit: Int): Result<List<TaskRunListItemDto>> =
        apiCall { service.getTaskRuns(projectId, agentId = agentId, cursor = cursor, limit = limit).toDataOrThrow() }

    override suspend fun getTaskDetail(projectId: String, taskId: String): Result<TaskDetailDto> =
        apiCall { service.getTaskDetail(projectId, taskId).toDataOrThrow() }

    override suspend fun getTaskSteps(projectId: String, taskId: String): Result<List<TaskStepListItemDto>> =
        apiCall { service.getTaskSteps(projectId, taskId).toDataOrThrow() }

    override suspend fun getTaskRunsOfTask(projectId: String, taskId: String): Result<List<TaskRunDetailListItemDto>> =
        apiCall { service.getTaskRunsOfTask(projectId, taskId).toDataOrThrow() }

    override suspend fun getTaskRunLogs(projectId: String, taskRunId: String, cursor: String?, limit: Int): Result<List<TaskRunLogEntryDto>> =
        apiCall { service.getTaskRunLogs(projectId, taskRunId, cursor, limit).toDataOrThrow() }

    override suspend fun getMergeRequestDetail(projectId: String, mergeRequestId: String): Result<MergeRequestDetailDto> =
        apiCall { service.getMergeRequestDetail(projectId, mergeRequestId).toDataOrThrow() }

    override suspend fun getDiffFiles(projectId: String, diffId: String): Result<List<DiffFileResponseDto>> =
        apiCall { service.getDiffFiles(projectId, diffId).toDataOrThrow() }

    override suspend fun cancelTask(projectId: String, taskId: String, idempotencyKey: String): Result<Unit> =
        apiCall { service.cancelTask(projectId, taskId, idempotencyKey).toUnitOrThrow() }

    override suspend fun replaceAgent(
        projectId: String,
        taskId: String,
        stepId: String,
        agentId: String,
        idempotencyKey: String
    ): Result<TaskStepListItemDto> = apiCall {
        service.replaceAgent(
            projectId, taskId, stepId, idempotencyKey, ReplaceAgentRequest(agentId)
        ).toDataOrThrow()
    }
}
