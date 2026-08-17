package com.example.qgent.data.repository

import com.example.qgent.data.api.QgApiService
import com.example.qgent.data.model.DiffDecisionRequest
import com.example.qgent.data.model.DiffFileDto
import com.example.qgent.data.model.DiffReviewBatchDto
import com.example.qgent.data.model.DiffReviewConfirmRequest
import com.example.qgent.data.model.DiffReviewRejectRequest
import com.example.qgent.data.model.toDataOrNull
import com.example.qgent.data.model.toDataOrThrow

class DiffRepositoryImpl(private val service: QgApiService) : DiffRepository {

    override suspend fun getDiffFiles(projectId: String, diffId: String, cursor: String?, limit: Int): Result<List<DiffFileDto>> = apiCall {
        service.getDiffFiles(projectId, diffId, cursor, limit).toDataOrThrow()
    }

    override suspend fun acceptDiff(projectId: String, diffId: String, reason: String?, idempotencyKey: String): Result<Unit> =
        apiCall {
            service.acceptDiff(projectId, diffId, idempotencyKey, DiffDecisionRequest(reason)).toDataOrThrow()
        }

    override suspend fun rejectDiff(projectId: String, diffId: String, reason: String?, idempotencyKey: String): Result<Unit> =
        apiCall {
            service.rejectDiff(projectId, diffId, idempotencyKey, DiffDecisionRequest(reason)).toDataOrThrow()
        }

    override suspend fun getTaskDiffReview(projectId: String, taskId: String): Result<DiffReviewBatchDto?> = apiCall {
        service.getTaskDiffReview(projectId, taskId).toDataOrNull()
    }

    override suspend fun getDiffReviewPatch(projectId: String, taskId: String, diffId: String): Result<com.google.gson.JsonElement> =
        apiCall {
            service.getDiffReviewPatch(projectId, taskId, diffId).toDataOrThrow()
        }

    override suspend fun confirmDiffReview(projectId: String, taskId: String, idempotencyKey: String): Result<Unit> =
        apiCall {
            service.confirmDiffReview(projectId, taskId, idempotencyKey, DiffReviewConfirmRequest()).toDataOrNull()
        }

    override suspend fun rejectDiffReview(projectId: String, taskId: String, reason: String?, idempotencyKey: String): Result<Unit> =
        apiCall {
            service.rejectDiffReview(projectId, taskId, idempotencyKey, DiffReviewRejectRequest(reason)).toDataOrNull()
        }

    override suspend fun retryDiffDelivery(projectId: String, taskId: String, idempotencyKey: String): Result<Unit> =
        apiCall {
            service.retryDiffDelivery(projectId, taskId, idempotencyKey).toDataOrNull()
        }
}
