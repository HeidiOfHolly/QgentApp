package com.example.qgent.data.repository

/** 真实请求失败时回退到 mock，保证演示环境可用 */
class FallbackTaskRepository(
    real: TaskRepository,
    mock: TaskRepository
) : TaskRepository {
    private val fb = FallbackDelegate(real, mock)

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

    override suspend fun getTaskSteps(projectId: String, taskId: String) =
        fb.call { getTaskSteps(projectId, taskId) }

    override suspend fun getTaskRunsOfTask(projectId: String, taskId: String) =
        fb.call { getTaskRunsOfTask(projectId, taskId) }

    override suspend fun getMergeRequestDetail(projectId: String, mergeRequestId: String) =
        fb.call { getMergeRequestDetail(projectId, mergeRequestId) }

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
