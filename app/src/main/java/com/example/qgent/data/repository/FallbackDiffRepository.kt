package com.example.qgent.data.repository

import com.example.qgent.data.model.DiffFileDto
import com.example.qgent.data.model.DiffReviewBatchDto

/** 真实请求失败时回退到 mock，保证演示环境可用（测试完成后可移除） */
class FallbackDiffRepository(
    real: DiffRepository,
    mock: DiffRepository
) : DiffRepository {
    private val fb = FallbackDelegate(real, mock)

    override suspend fun getDiffFiles(projectId: String, diffId: String, cursor: String?, limit: Int) =
        fb.call { getDiffFiles(projectId, diffId, cursor, limit) }

    override suspend fun acceptDiff(projectId: String, diffId: String, reason: String?, idempotencyKey: String) =
        fb.call { acceptDiff(projectId, diffId, reason, idempotencyKey) }

    override suspend fun rejectDiff(projectId: String, diffId: String, reason: String?, idempotencyKey: String) =
        fb.call { rejectDiff(projectId, diffId, reason, idempotencyKey) }

    override suspend fun getTaskDiffReview(projectId: String, taskId: String): Result<DiffReviewBatchDto?> =
        fb.call { getTaskDiffReview(projectId, taskId) }

    override suspend fun getDiffReviewPatch(projectId: String, taskId: String, diffId: String): Result<com.google.gson.JsonElement> =
        fb.call { getDiffReviewPatch(projectId, taskId, diffId) }

    override suspend fun confirmDiffReview(projectId: String, taskId: String, idempotencyKey: String) =
        fb.call { confirmDiffReview(projectId, taskId, idempotencyKey) }

    override suspend fun rejectDiffReview(projectId: String, taskId: String, reason: String?, idempotencyKey: String) =
        fb.call { rejectDiffReview(projectId, taskId, reason, idempotencyKey) }

    override suspend fun retryDiffDelivery(projectId: String, taskId: String, idempotencyKey: String) =
        fb.call { retryDiffDelivery(projectId, taskId, idempotencyKey) }
}
