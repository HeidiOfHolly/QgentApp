package com.example.qgent.ui.diffreview

import com.example.qgent.data.model.RepositoryDeliveryMergeRequestDto

/**
 * Diff Review / 交付 的展示与按钮规则（MR_FIRST B 方案，接口文档 v1.10.0）。
 *
 * 全部为纯函数，便于单测；UI 层（任务详情页 / 聊天对话框）共用同一套判定，避免两处逻辑分叉。
 *
 * 核心规则：
 * - 只有 reviewStatus=PENDING_CONFIRMATION 且 confirmationSource 非 SYSTEM 时显示「确认交付/拒绝交付」；
 * - ACCEPTED+USER 展示「已由用户确认」；ACCEPTED+SYSTEM 展示「自动交付」，均无确认/拒绝按钮；
 * - PARTIALLY_DELIVERED / FAILED（交付状态）或任务 DELIVERY_FAILED 时按权限显示「重试交付」。
 */
object DiffReviewRules {

    /** 是否显示「确认交付 / 拒绝交付」按钮（confirmationSource 缺省按 USER 兜底，保证 DIFF_FIRST 回归） */
    fun canConfirmOrReject(reviewStatus: String?, confirmationSource: String?): Boolean =
        reviewStatus == "PENDING_CONFIRMATION" && confirmationSource != "SYSTEM"

    /** ACCEPTED 状态下的展示文案：SYSTEM → 已自动确认（MR_FIRST 自动授权，不代表交付已完成）；USER → 已由用户确认；缺省兜底已确认 */
    fun acceptedCaption(confirmationSource: String?): String = when (confirmationSource) {
        "SYSTEM" -> "已自动确认"
        "USER" -> "已由用户确认"
        else -> "已确认"
    }

    /**
     * 是否显示「重试交付」。
     * @param deliveryStatus 批次交付状态（NOT_STARTED/DELIVERING/DELIVERED/PARTIALLY_DELIVERED/FAILED）
     * @param taskStatus 任务状态（DELIVERY_FAILED 兼容旧枚举）
     * @param capabilityRetry 后端能力位 canRetryDelivery（优先）
     */
    fun canRetryDelivery(
        deliveryStatus: String?,
        taskStatus: String?,
        capabilityRetry: Boolean?
    ): Boolean {
        if (capabilityRetry == true) return true
        // FAILED 同时兼容旧枚举 DELIVERY_FAILED（diffReviewSummary 早期版本）
        return deliveryStatus == "PARTIALLY_DELIVERED" ||
            deliveryStatus == "FAILED" ||
            deliveryStatus == "DELIVERY_FAILED" ||
            taskStatus == "DELIVERY_FAILED"
    }

    /** 批次级交付状态文案（稳定文案，不把 COMMITTED 当 MR 已创建） */
    fun deliveryStatusCaption(deliveryStatus: String?): String? = when (deliveryStatus) {
        "NOT_STARTED" -> "未开始"
        "DELIVERING" -> "交付中"
        "DELIVERED" -> "交付完成"
        "PARTIALLY_DELIVERED" -> "部分仓库成功，部分仓库失败"
        "FAILED", "DELIVERY_FAILED" -> "交付失败"
        else -> null
    }

    /** 单仓库交付状态文案（NOT_STARTED / COMMITTED / MR_CREATED / FAILED） */
    fun repositoryDeliveryCaption(status: String?): String = when (status) {
        "NOT_STARTED" -> "未开始"
        "COMMITTED" -> "已提交代码"
        "MR_CREATED" -> "已创建合并请求"
        "FAILED" -> "交付失败"
        else -> status ?: "未知"
    }

    /** 交付模式文案：MR_FIRST → MR 前自动预检（仅交付路径标签，不代表已交付/已合并）；DIFF_FIRST → 人工确认 */
    fun deliveryModeCaption(mode: String?): String? = when (mode) {
        "MR_FIRST" -> "MR 前自动预检"
        "DIFF_FIRST" -> "人工确认"
        else -> null
    }

    fun isMrFirst(mode: String?): Boolean = mode == "MR_FIRST"

    /**
     * MR_FIRST 交付模式标签是否展示：仅当任务真正进入交付阶段（待 Diff 确认及之后）才展示。
     * deliveryMode 在规划阶段已持久化，任务尚未进入交付（规划中/待执行/执行中/失败/已取消）时
     * 展示会让人误以为已自动交付成功；DIFF_FIRST 等非 MR_FIRST 模式不按状态隐藏。
     */
    fun showDeliveryModeLabel(mode: String?, taskStatus: String?): Boolean {
        if (!isMrFirst(mode)) return true
        return taskStatus != null && taskStatus !in PRE_DELIVERY_STATUSES
    }

    /** 尚未真正进入交付阶段的任务状态（deliveryMode 只是规划标签，不展示 MR 前自动预检） */
    private val PRE_DELIVERY_STATUSES = setOf(
        "PLANNING", "PENDING", "RUNNING", "FAILED", "CANCELLING", "CANCELLED"
    )

    /**
     * MR 链接文案：mergeRequest.webUrl 为空返回 null（前端不渲染链接）；
     * 非空返回「查看合并请求 #N ↗」。
     */
    fun mrLinkText(mergeRequest: RepositoryDeliveryMergeRequestDto?): String? {
        val url = mergeRequest?.webUrl?.takeIf { it.isNotBlank() } ?: return null
        val num = mergeRequest.number?.let { "#$it " }.orEmpty()
        return "查看合并请求 $num↗"
    }

    /** 已知 409 冲突业务码（后端错误码透传进 ApiException.code，非 HTTP_409 形态） */
    private val CONFLICT_CODES = setOf(
        "DIFF_BATCH_REVIEW_REQUIRED",   // 批次内单 Diff 确认/拒绝 → 409
        "TASK_NOT_CANCELLABLE",         // 终态操作 → 409
        "DELIVERY_STATE_CONFLICT",      // 交付状态冲突
        "DIFF_REVIEW_STATE_CONFLICT"    // 批次状态冲突（已确认/已拒绝再操作）
    )

    /** 是否冲突类错误（409）：刷新 Task 与 DiffReview 后再决定按钮状态 */
    fun isConflict(errorCode: String?): Boolean =
        errorCode == "HTTP_409" ||
            errorCode?.contains("409") == true ||
            errorCode?.contains("CONFLICT", ignoreCase = true) == true ||
            errorCode?.let { it in CONFLICT_CODES } == true

    // ── delivery.started SSE 事件 ──

    /**
     * 去重键：taskId + operationId（事件可能重复、乱序或晚到，以此去重避免重复刷新页面数据）。
     */
    fun deliveryStartedKey(taskId: String?, operationId: String?): String? {
        if (taskId.isNullOrBlank() || operationId.isNullOrBlank()) return null
        return "$taskId:$operationId"
    }

    /** 从 delivery.started payload 解析去重键（解析不到返回 null，调用方按不去重处理）。
     *  兼容 Gson 2.8.5（retrofit 2.9.0 默认）：JsonParser().parse 实例方法，JsonObject.get + asString。 */
    fun deliveryStartedKeyFromPayload(payload: String?): String? {
        if (payload.isNullOrBlank()) return null
        return runCatching {
            val obj = com.google.gson.JsonParser().parse(payload).asJsonObject
            val taskId = obj.get("taskId")?.takeIf { it.isJsonPrimitive }?.asString?.takeIf { it.isNotBlank() }
            val operationId = obj.get("operationId")?.takeIf { it.isJsonPrimitive }?.asString?.takeIf { it.isNotBlank() }
            deliveryStartedKey(taskId, operationId)
        }.getOrNull()
    }
}
