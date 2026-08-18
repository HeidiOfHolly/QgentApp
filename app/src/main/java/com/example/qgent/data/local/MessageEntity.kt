package com.example.qgent.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "chat_message", indices = [Index("groupId")])
data class MessageEntity(
    @PrimaryKey val id: String,
    val groupId: String,
    val senderName: String,
    val content: String,
    val type: String,
    val timestamp: Long,
    val isMine: Boolean,
    val fileName: String?,
    val fileSize: Long?,
    val sequence: Long,
    /** 引用消息 id（非空表示本条为 QUOTE） */
    val replyToId: String?,
    /** 引用消息摘要（引用条展示，缓存恢复时需要） */
    val replyToSummary: String?,
    /** 发送者类型：USER / AGENT / SYSTEM */
    val senderType: String?,
    /** TASK_STATUS 卡片：任务 id / 状态 / 执行节点 */
    val taskId: String?,
    val taskStatus: String?,
    val taskNode: String?,
    /** TASK_STATUS 卡片（v23）：阶段 / 交付模式 / 计划摘要 / 步骤快照（JSON 数组串） */
    val taskPhase: String?,
    val taskDeliveryMode: String?,
    val taskPlanSummary: String?,
    val taskPlanStepsJson: String?,
    /** DIFF 卡片：diffId / 标题 / 总变更统计（缺这些字段缓存恢复后点卡片会报"缺少 diffId"） */
    val diffId: String?,
    val diffTitle: String?,
    val diffAdditions: Int?,
    val diffDeletions: Int?,
    /** DIFF 卡片（v23）：审核批次 / 审核状态 / 交付状态 */
    val reviewBatchId: String?,
    val reviewStatus: String?,
    val deliveryStatus: String?
)
