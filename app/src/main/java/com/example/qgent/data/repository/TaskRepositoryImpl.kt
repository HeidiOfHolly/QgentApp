package com.example.qgent.data.repository

import com.example.qgent.data.api.QgApiService
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
import com.example.qgent.data.model.MergeCommitMessageRequest
import com.example.qgent.data.model.MergeRequestCheckDto
import com.example.qgent.data.model.MergeRequestDetailDto
import com.example.qgent.data.model.MergeRequestDto
import com.example.qgent.data.model.MergeRequestPreflightDto
import com.example.qgent.data.model.MergeRequestReviewDto
import com.example.qgent.data.model.RequestMergeRequestPreflightRequest
import com.example.qgent.data.model.RequestMergeRequestPreflightResponse
import com.example.qgent.data.model.TestsetResponseDto
import com.example.qgent.data.model.ReplaceAgentRequest
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
import com.example.qgent.data.model.toDataOrThrow
import com.example.qgent.data.model.toUnitOrThrow
import com.google.gson.JsonElement

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

    override suspend fun getMergeRequests(projectId: String, status: String?, cursor: String?, limit: Int): Result<List<MergeRequestDto>> =
        apiCall { service.getMergeRequests(projectId, status = status, cursor = cursor, limit = limit).toDataOrThrow() }

    override suspend fun getTaskRuns(projectId: String, agentId: String, cursor: String?, limit: Int): Result<List<TaskRunListItemDto>> =
        apiCall { service.getTaskRuns(projectId, agentId = agentId, cursor = cursor, limit = limit).toDataOrThrow() }

    override suspend fun getTaskDetail(projectId: String, taskId: String): Result<TaskDetailDto> =
        apiCall { service.getTaskDetail(projectId, taskId).toDataOrThrow() }

    override suspend fun getWorkspaceDiffPreview(projectId: String, taskId: String, revision: Int?): Result<WorkspaceDiffPreviewDto> =
        apiCall { service.getWorkspaceDiffPreview(projectId, taskId, revision).toDataOrThrow() }

    override suspend fun getWorkspaceDiffPreviewFiles(projectId: String, taskId: String, revision: Int?): Result<List<WorkspaceDiffPreviewFileDto>> =
        apiCall { service.getWorkspaceDiffPreviewFiles(projectId, taskId, revision).toDataOrThrow() }

    override suspend fun getTaskSteps(projectId: String, taskId: String): Result<List<TaskStepListItemDto>> =
        apiCall { service.getTaskSteps(projectId, taskId).toDataOrThrow() }

    override suspend fun getTaskRunsOfTask(projectId: String, taskId: String): Result<List<TaskRunDetailListItemDto>> =
        apiCall { service.getTaskRunsOfTask(projectId, taskId).toDataOrThrow() }

    override suspend fun getTaskRunLogs(projectId: String, taskRunId: String, cursor: String?, limit: Int): Result<List<TaskRunLogEntryDto>> =
        apiCall {
            // 日志接口按 cursor/limit 分页（文档 §12.2/§33，游标=上页最后一条 sequence），
            // 循环拉全量避免日志被截断。body() 只能读一次，必须同时取出 data 与 page。
            val all = mutableListOf<TaskRunLogEntryDto>()
            var nextCursor = cursor
            do {
                val resp = service.getTaskRunLogs(projectId, taskRunId, nextCursor, limit)
                if (!resp.isSuccessful) resp.toDataOrThrow()
                val body = resp.body() ?: throw com.example.qgent.data.model.ApiException(
                    "EMPTY_RESPONSE", "响应为空", null
                )
                body.error?.let {
                    throw com.example.qgent.data.model.ApiException(it.code, it.message, body.requestId, it.details)
                }
                all += body.data.orEmpty()
                val page = body.page
                nextCursor = page?.nextCursor?.takeIf { page.hasMore }
            } while (nextCursor != null)
            all
        }

    override suspend fun getMergeRequestDetail(projectId: String, mergeRequestId: String): Result<MergeRequestDetailDto> =
        apiCall { service.getMergeRequestDetail(projectId, mergeRequestId).toDataOrThrow() }

    override suspend fun getDeliveryItems(
        projectId: String,
        type: String?,
        groupId: String?,
        createdBy: String?,
        repositoryId: String?,
        cursor: String?,
        limit: Int
    ): Result<List<DeliveryItemDto>> =
        apiCall {
            // 接口按 cursor/limit 分页（文档 §20.1），循环拉全量避免交付物数量被截断。
            // 注意：Response.body() 只能读一次（OkHttp body 一次性流），必须同时取出 data 与 page。
            val all = mutableListOf<DeliveryItemDto>()
            var nextCursor = cursor
            do {
                val resp = service.getDeliveryItems(projectId, type, groupId, createdBy, repositoryId, nextCursor, limit)
                if (!resp.isSuccessful) resp.toDataOrThrow()  // 非 2xx：抛错走统一错误处理
                val body = resp.body() ?: throw com.example.qgent.data.model.ApiException(
                    "EMPTY_RESPONSE", "响应为空", null
                )
                body.error?.let {
                    throw com.example.qgent.data.model.ApiException(it.code, it.message, body.requestId, it.details)
                }
                all += body.data.orEmpty()
                val page = body.page
                nextCursor = page?.nextCursor?.takeIf { page.hasMore }
            } while (nextCursor != null)
            all
        }

    override suspend fun getTestsets(projectId: String, repositoryId: String?, status: String?): Result<List<TestsetResponseDto>> =
        apiCall { service.getTestsets(projectId, repositoryId, status).toDataOrThrow() }

    override suspend fun getMergeRequestChecks(projectId: String, mergeRequestId: String): Result<List<MergeRequestCheckDto>> =
        apiCall { service.getMergeRequestChecks(projectId, mergeRequestId).toDataOrThrow() }

    override suspend fun getMergeRequestReviews(projectId: String, mergeRequestId: String): Result<List<MergeRequestReviewDto>> =
        apiCall { service.getMergeRequestReviews(projectId, mergeRequestId).toDataOrThrow() }

    override suspend fun cqApprove(projectId: String, mergeRequestId: String, reason: String?, idempotencyKey: String): Result<Unit> =
        apiCall { service.cqApprove(projectId, mergeRequestId, idempotencyKey, CqActionRequest(reason)).toUnitOrThrow() }

    override suspend fun cqReject(projectId: String, mergeRequestId: String, reason: String, idempotencyKey: String): Result<Unit> =
        apiCall { service.cqReject(projectId, mergeRequestId, idempotencyKey, CqActionRequest(reason)).toUnitOrThrow() }

    override suspend fun mergeRequest(projectId: String, mergeRequestId: String, idempotencyKey: String, commitMessage: String?): Result<Unit> =
        apiCall {
            service.mergeRequest(
                projectId, mergeRequestId, idempotencyKey,
                MergeCommitMessageRequest(commitMessage)
            ).toUnitOrThrow()
        }

    override suspend fun syncMergeRequest(projectId: String, mergeRequestId: String, idempotencyKey: String): Result<Unit> =
        apiCall { service.syncMergeRequest(projectId, mergeRequestId, idempotencyKey, EmptyBody()).toUnitOrThrow() }

    override suspend fun createMergeRequest(
        projectId: String,
        taskId: String,
        repositoryId: String,
        targetBranch: String,
        title: String,
        idempotencyKey: String
    ): Result<CreateMergeRequestResponse> =
        apiCall {
            service.createMergeRequest(
                projectId, idempotencyKey,
                CreateMergeRequestRequest(taskId, repositoryId, targetBranch, title)
            ).toDataOrThrow()
        }

    override suspend fun requestMergeRequestPreflight(
        projectId: String,
        taskId: String,
        repositoryId: String,
        idempotencyKey: String
    ): Result<RequestMergeRequestPreflightResponse> =
        apiCall {
            service.requestMergeRequestPreflight(
                projectId, idempotencyKey,
                RequestMergeRequestPreflightRequest(taskId, repositoryId)
            ).toDataOrThrow()
        }

    override suspend fun getTaskMergeRequestPreflight(projectId: String, taskId: String): Result<List<MergeRequestPreflightDto>> =
        apiCall { parsePreflightList(service.getTaskMergeRequestPreflight(projectId, taskId)) }

    /** 解析预检列表响应：兼容 {data:[...]} / 裸数组 / {items:[...]} 三形态（§46 预检列表）。
     *  非 2xx 或 error 分支按统一错误契约抛 ApiException。 */
    private fun parsePreflightList(resp: retrofit2.Response<com.google.gson.JsonElement>): List<MergeRequestPreflightDto> {
        if (!resp.isSuccessful) resp.throwPreflightHttpError()
        val body = resp.body()
        val gson = com.google.gson.Gson()
        val type = object : com.google.gson.reflect.TypeToken<List<MergeRequestPreflightDto>>() {}.type
        val parse: (JsonElement) -> List<MergeRequestPreflightDto> = { el ->
            if (el.isJsonArray) {
                gson.fromJson(el, type) ?: emptyList()
            } else if (el.isJsonObject) {
                val obj = el.asJsonObject
                obj.get("error")?.takeIf { !it.isJsonNull }?.let { errObj ->
                    val code = errObj.asJsonObject.get("code")?.asString
                    val message = errObj.asJsonObject.get("message")?.asString ?: "请求失败"
                    throw com.example.qgent.data.model.ApiException(code ?: "UNKNOWN", message)
                }
                val data = obj.get("data")
                val items = obj.get("items")
                val list = (data ?: items)?.takeIf { !it.isJsonNull }
                if (list?.isJsonArray == true) gson.fromJson(list, type) ?: emptyList()
                else emptyList()
            } else emptyList()
        }
        return body?.let { parse(it) } ?: throw com.example.qgent.data.model.ApiException("EMPTY_RESPONSE", "响应为空")
    }

    /** 非 2xx 预检响应：解析 errorBody 统一转 ApiException（与 toDataOrThrow 的 throwHttpError 一致） */
    private fun retrofit2.Response<*>.throwPreflightHttpError(): Nothing {
        val gson = com.google.gson.Gson()
        val parsed = runCatching {
            errorBody()?.string()?.let { gson.fromJson(it, com.google.gson.JsonObject::class.java) }
        }.getOrNull()
        val err = parsed?.get("error")?.takeIf { it.isJsonObject }?.asJsonObject
        throw com.example.qgent.data.model.ApiException(
            code = err?.get("code")?.asString ?: "HTTP_${code()}",
            message = err?.get("message")?.asString ?: "请求失败 (${code()})",
            requestId = parsed?.get("requestId")?.takeIf { it.isJsonPrimitive }?.asString,
            details = err?.get("details")?.takeIf { it.isJsonArray }?.asJsonArray
                ?.mapNotNull { if (it.isJsonPrimitive && it.asJsonPrimitive.isString) com.google.gson.JsonPrimitive(it.asString) else it }
        )
    }

    override suspend fun getMergeRequestPreflight(projectId: String, preflightId: String): Result<MergeRequestPreflightDto> =
        apiCall { service.getMergeRequestPreflight(projectId, preflightId).toDataOrThrow() }

    override suspend fun dryRunCqApprove(projectId: String, dryRunId: String, reason: String?, idempotencyKey: String): Result<Unit> =
        apiCall { service.dryRunCqApprove(projectId, dryRunId, idempotencyKey, CqActionRequest(reason)).toUnitOrThrow() }

    override suspend fun createDryRun(
        projectId: String,
        repositoryId: String,
        sourceRef: String,
        targetBranch: String,
        taskId: String?,
        idempotencyKey: String
    ): Result<CreateDryRunResponse> =
        apiCall {
            service.createDryRun(
                projectId, idempotencyKey,
                CreateDryRunRequest(repositoryId, sourceRef, targetBranch, taskId)
            ).toDataOrThrow()
        }

    override suspend fun getDryRunReport(projectId: String, dryRunId: String): Result<DryRunReportDto> =
        apiCall { service.getDryRunReport(projectId, dryRunId).toDataOrThrow() }

    override suspend fun retryDryRun(projectId: String, dryRunId: String, idempotencyKey: String): Result<DryRunRetryResponse> =
        apiCall { service.retryDryRun(projectId, dryRunId, idempotencyKey, EmptyBody()).toDataOrThrow() }

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
        apiCall {
            service.getDryRuns(
                projectId, repositoryId, taskId, status, targetBranch, createdByUserId, cursor, limit
            ).toDataOrThrow()
        }

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
