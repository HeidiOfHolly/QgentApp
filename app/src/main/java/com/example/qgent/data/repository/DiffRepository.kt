package com.example.qgent.data.repository

import com.example.qgent.data.model.DiffFileDto
import com.example.qgent.data.model.DiffReviewBatchDto

/**
 * Diff 仓库（文档 §12.3 / §15.3）：拉取 DIFF 消息卡片对应的文件 diff 内容。
 *
 * 确认/拒绝语义（§12.3）：
 * - 任务级最终 Diff Review 批次（跨仓库）必须走 Task 级接口 confirm/reject/retry-delivery；
 *   批次内 Diff 禁止用单 Diff 的 accept/reject（后端返回 409 DIFF_BATCH_REVIEW_REQUIRED）。
 * - 单 Diff 的 accept/reject 仅用于非批次场景（当前 App 未使用，保留兼容）。
 */
interface DiffRepository {
    suspend fun getDiffFiles(projectId: String, diffId: String, cursor: String? = null, limit: Int = 20): Result<List<DiffFileDto>>

    /** 确认 Diff（POST /diffs/{diffId}/accept，§15.3；仅非批次场景，body 可带 reason） */
    suspend fun acceptDiff(projectId: String, diffId: String, reason: String? = null, idempotencyKey: String): Result<Unit>

    /** 拒绝 Diff（POST /diffs/{diffId}/reject，§15.3；仅非批次场景，body 可带 reason） */
    suspend fun rejectDiff(projectId: String, diffId: String, reason: String? = null, idempotencyKey: String): Result<Unit>

    /** 查询 Task 级最终 Diff Review 批次（GET .../tasks/{taskId}/diff-review，§12.3；无批次时为 null） */
    suspend fun getTaskDiffReview(projectId: String, taskId: String): Result<DiffReviewBatchDto?>

    /** 读取批次内单个 Diff 的不可变 patch 内容（GET .../diff-review/diffs/{diffId}/patch，§12.3） */
    suspend fun getDiffReviewPatch(projectId: String, taskId: String, diffId: String): Result<com.google.gson.JsonElement>

    /** 确认整个最终 Diff 批次，开始逐仓库交付（POST .../diff-review/confirm，§12.3；Idempotency-Key 必填） */
    suspend fun confirmDiffReview(projectId: String, taskId: String, idempotencyKey: String): Result<Unit>

    /** 拒绝整个最终 Diff 批次（POST .../diff-review/reject，§12.3；body 带 reason；Idempotency-Key 必填） */
    suspend fun rejectDiffReview(projectId: String, taskId: String, reason: String?, idempotencyKey: String): Result<Unit>

    /** 重试逐仓库交付（POST .../diff-review/retry-delivery，§12.3；Idempotency-Key 必填） */
    suspend fun retryDiffDelivery(projectId: String, taskId: String, idempotencyKey: String): Result<Unit>
}
