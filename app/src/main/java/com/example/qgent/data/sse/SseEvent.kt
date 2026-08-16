package com.example.qgent.data.sse

/**
 * 项目级 SSE 事件（文档 §12.1）。
 *
 * `id` 即项目内单调递增 sequenceNo，作为 Last-Event-ID 断线续传游标；
 * `type` 为事件名（如 task.updated），`data` 为业务 payload 原始 JSON。
 *
 * 事件仅用于刷新界面：客户端恢复连接或收到乱序事件后，
 * 必须以对应的查询接口为准，不把 SSE payload 当作完整 DTO。
 */
data class SseEvent(
    val id: String?,
    val type: SseEventType,
    val data: String
)

/** 文档 §12.1 + §15.4 冻结的 21 种事件类型 */
enum class SseEventType(val wire: String) {
    TASK_UPDATED("task.updated"),
    TASK_STEP_UPDATED("task-step.updated"),
    TASK_RUN_UPDATED("task-run.updated"),
    TASK_RUN_STEP_PROGRESS("task-run.step.progress"),
    INPUT_REQUIRED("input-required"),
    APPROVAL_REQUIRED("approval-required"),
    TEST_RUN_UPDATED("test-run.updated"),
    DRY_RUN_UPDATED("dry-run.updated"),
    DIFF_CREATED("diff.created"),
    TASK_ARTIFACT_CREATED("task.artifact.created"),
    TASK_RUN_ARTIFACT_CREATED("task-run.artifact.created"),
    DIFF_REVIEW_CREATED("diff-review.created"),
    TASK_AWAITING_DIFF_CONFIRMATION("task.awaiting-diff-confirmation"),
    DIFF_REVIEW_CONFIRMED("diff-review.confirmed"),
    DIFF_REVIEW_REJECTED("diff-review.rejected"),
    DELIVERY_REPOSITORY_UPDATED("delivery.repository.updated"),
    DELIVERY_FAILED("delivery.failed"),
    DELIVERY_COMPLETED("delivery.completed"),
    TASK_DIFF_REVIEW_FAILED("task.diff-review.failed"),
    DIFF_REVIEW_SKIPPED("diff-review.skipped"),
    MERGE_REQUEST_UPDATED("merge-request.updated");

    companion object {
        /** 未知事件名 → null（不做匹配，避免枚举增长破坏解析） */
        fun fromWire(name: String): SseEventType? =
            entries.firstOrNull { it.wire == name }
    }
}
