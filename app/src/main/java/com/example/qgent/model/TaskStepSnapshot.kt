package com.example.qgent.model

/** TASK_STATUS 卡 plan.steps 单步快照（v23 契约；stepId 为数据库 TaskStepEntity.id） */
data class TaskStepSnapshot(
    val stepId: String? = null,
    val sequence: Int? = null,
    val title: String? = null,
    val role: String? = null,
    val status: String? = null,
    val message: String? = null
)
