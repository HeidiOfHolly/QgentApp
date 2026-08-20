package com.example.qgent.data.repository

/** 真实请求失败时回退到 mock，保证演示环境可用 */
class FallbackTaskRepository(
    real: TaskRepository,
    mock: TaskRepository
) : TaskRepository {
    private val fb = FallbackDelegate(real, mock)

    override suspend fun createTask(projectId: String, request: com.example.qgent.data.model.TaskCreateRequest, idempotencyKey: String) =
        fb.call { createTask(projectId, request, idempotencyKey) }

    override suspend fun triggerTask(
        projectId: String,
        groupId: String,
        messageId: String,
        request: com.example.qgent.data.model.TaskTriggerRequest,
        idempotencyKey: String
    ) = fb.call { triggerTask(projectId, groupId, messageId, request, idempotencyKey) }

    override suspend fun getTasks(
        projectId: String,
        groupId: String?,
        status: String?,
        createdBy: String?,
        repositoryId: String?,
        cursor: String?,
        limit: Int
    ) = fb.call { getTasks(projectId, groupId, status, createdBy, repositoryId, cursor, limit) }

    override suspend fun getActivities(teamId: String, cursor: String?, limit: Int) =
        fb.call { getActivities(teamId, cursor, limit) }

    override suspend fun getMergeRequests(projectId: String, cursor: String?, limit: Int) =
        fb.call { getMergeRequests(projectId, cursor, limit) }

    override suspend fun getTaskRuns(projectId: String, agentId: String, cursor: String?, limit: Int) =
        fb.call { getTaskRuns(projectId, agentId, cursor, limit) }

    override suspend fun getTaskDetail(projectId: String, taskId: String) =
        fb.call { getTaskDetail(projectId, taskId) }

    override suspend fun getWorkspaceDiffPreview(projectId: String, taskId: String, revision: Int?) =
        fb.call { getWorkspaceDiffPreview(projectId, taskId, revision) }

    override suspend fun getWorkspaceDiffPreviewFiles(projectId: String, taskId: String, revision: Int?) =
        fb.call { getWorkspaceDiffPreviewFiles(projectId, taskId, revision) }

    override suspend fun getTaskSteps(projectId: String, taskId: String) =
        fb.call { getTaskSteps(projectId, taskId) }

    override suspend fun getTaskRunsOfTask(projectId: String, taskId: String) =
        fb.call { getTaskRunsOfTask(projectId, taskId) }

    override suspend fun getTaskRunLogs(projectId: String, taskRunId: String, cursor: String?, limit: Int) =
        fb.call { getTaskRunLogs(projectId, taskRunId, cursor, limit) }

    override suspend fun getMergeRequestDetail(projectId: String, mergeRequestId: String) =
        fb.call { getMergeRequestDetail(projectId, mergeRequestId) }

    override suspend fun getDeliveryItems(
        projectId: String,
        type: String?,
        groupId: String?,
        createdBy: String?,
        repositoryId: String?,
        cursor: String?,
        limit: Int
    ) = fb.call { getDeliveryItems(projectId, type, groupId, createdBy, repositoryId, cursor, limit) }

    override suspend fun getMergeRequestChecks(projectId: String, mergeRequestId: String) =
        fb.call { getMergeRequestChecks(projectId, mergeRequestId) }

    override suspend fun getMergeRequestReviews(projectId: String, mergeRequestId: String) =
        fb.call { getMergeRequestReviews(projectId, mergeRequestId) }

    override suspend fun cqApprove(projectId: String, mergeRequestId: String, reason: String?, idempotencyKey: String) =
        fb.call { cqApprove(projectId, mergeRequestId, reason, idempotencyKey) }

    override suspend fun cqReject(projectId: String, mergeRequestId: String, reason: String, idempotencyKey: String) =
        fb.call { cqReject(projectId, mergeRequestId, reason, idempotencyKey) }

    override suspend fun mergeRequest(projectId: String, mergeRequestId: String, idempotencyKey: String) =
        fb.call { mergeRequest(projectId, mergeRequestId, idempotencyKey) }

    override suspend fun syncMergeRequest(projectId: String, mergeRequestId: String, idempotencyKey: String) =
        fb.call { syncMergeRequest(projectId, mergeRequestId, idempotencyKey) }

    override suspend fun createMergeRequest(
        projectId: String,
        taskId: String,
        repositoryId: String,
        targetBranch: String,
        title: String,
        idempotencyKey: String
    ) = fb.call { createMergeRequest(projectId, taskId, repositoryId, targetBranch, title, idempotencyKey) }

    override suspend fun getPreflight(projectId: String, taskId: String, repositoryId: String, targetBranch: String?) =
        fb.call { getPreflight(projectId, taskId, repositoryId, targetBranch) }

    override suspend fun requestMergeRequestPreflight(
        projectId: String,
        taskId: String,
        repositoryId: String,
        idempotencyKey: String
    ) = fb.call { requestMergeRequestPreflight(projectId, taskId, repositoryId, idempotencyKey) }

    override suspend fun getTaskMergeRequestPreflight(projectId: String, taskId: String) =
        fb.call { getTaskMergeRequestPreflight(projectId, taskId) }

    override suspend fun dryRunCqApprove(projectId: String, dryRunId: String, reason: String?, idempotencyKey: String) =
        fb.call { dryRunCqApprove(projectId, dryRunId, reason, idempotencyKey) }

    override suspend fun getDiffFiles(projectId: String, diffId: String) =
        fb.call { getDiffFiles(projectId, diffId) }

    override suspend fun cancelTask(projectId: String, taskId: String, idempotencyKey: String) =
        fb.call { cancelTask(projectId, taskId, idempotencyKey) }

    override suspend fun replaceAgent(
        projectId: String,
        taskId: String,
        stepId: String,
        agentId: String,
        idempotencyKey: String
    ) = fb.call { replaceAgent(projectId, taskId, stepId, agentId, idempotencyKey) }
}
