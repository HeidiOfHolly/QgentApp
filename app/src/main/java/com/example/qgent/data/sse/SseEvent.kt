package com.example.qgent.data.sse

/**
 * SSE 事件（文档 §12.1 + 项目/团队/通知级补充）。
 *
 * `id` 即流内单调递增 sequenceNo，作为 Last-Event-ID 断线续传游标；
 * `type` 为事件名（如 task.updated / message.created），`data` 为业务 payload 原始 JSON。
 *
 * 事件仅用于刷新界面：客户端恢复连接或收到乱序事件后，
 * 必须以对应的查询接口为准，不把 SSE payload 当作完整 DTO。
 */
data class SseEvent(
    val id: String?,
    val type: SseEventType,
    val data: String
)

/**
 * 全部事件类型（按流分组）。
 *
 * 项目级流（GET /projects/{projectId}/events）：任务/Diff/交付 + 消息/群/Memory；
 * 团队级流（GET /teams/{teamId}/events）：成员/项目动态；
 * 通知级流（GET /notifications/events）：通知。
 */
enum class SseEventType(val wire: String) {

    // ── 项目级：任务 / Diff / 交付（§12.1 + §15.4） ──

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
    MERGE_REQUEST_UPDATED("merge-request.updated"),

    // ── 项目级：消息 / 群 / Memory（前端 SSE 需求清单 ①） ──

    /** 有人/Agent 发群消息；payload { projectId, groupId, messageId } */
    MESSAGE_CREATED("message.created"),
    /** 群创建/改名/归档；payload { projectId, groupId } */
    GROUP_CREATED("group.created"),
    GROUP_UPDATED("group.updated"),
    GROUP_ARCHIVED("group.archived"),
    /** 成员进出、Agent 首次进群；payload { projectId, groupId } */
    GROUP_MEMBER_UPDATED("group.member.updated"),
    /** Memory 审批流转；payload { projectId, resourceType, resourceId, eventVersion, updatedAt } */
    MEMORY_SUBMIT_REVIEW("memory.submit-review"),
    MEMORY_APPROVED("memory.approved"),
    MEMORY_REJECTED("memory.rejected"),
    MEMORY_ARCHIVED("memory.archived"),

    // ── 团队级（GET /teams/{teamId}/events，清单 ②） ──

    /** 成员被拉进项目；payload { teamId, projectId } */
    PROJECT_MEMBER_ADDED("project.member.added"),
    /** 成员加入（接受邀请）/移出团队；payload { teamId, userId } */
    TEAM_MEMBER_UPDATED("team.member.updated"),
    /** 团队动态产生（暂未单独发布，由项目事件聚合）；payload { teamId } */
    ACTIVITY_CREATED("activity.created"),

    // ── 通知级（GET /notifications/events，清单 ③） ──

    /** 新通知产生（含 INVITED 邀请）；payload { notificationId, kind } */
    NOTIFICATION_CREATED("notification.created");

    companion object {
        /** 未知事件名 → null（不做匹配，避免枚举增长破坏解析） */
        fun fromWire(name: String): SseEventType? =
            entries.firstOrNull { it.wire == name }
    }
}
