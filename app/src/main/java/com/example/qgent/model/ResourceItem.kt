package com.example.qgent.model

enum class ResourceStatus { APPROVED, PENDING }

data class MemoryItem(
    val id: String,
    val name: String,
    val description: String,
    val status: ResourceStatus
)

data class SkillItem(
    val id: String,
    val name: String,
    val description: String,
    val status: ResourceStatus
)

/** Skill 状态 → UI 状态（PUBLISHED/APPROVED 视为已共享，其余 PENDING_REVIEW 进审核队列） */
fun com.example.qgent.data.model.SkillDto.toSkillItem(): SkillItem = SkillItem(
    id = id,
    name = name,
    description = content.orEmpty(),
    status = if (status == "PUBLISHED") ResourceStatus.APPROVED else ResourceStatus.PENDING
)

/** Memory 状态 → UI 状态（APPROVED 视为已共享，其余 PENDING_REVIEW 进审核队列） */
fun com.example.qgent.data.model.MemoryDto.toMemoryItem(): MemoryItem = MemoryItem(
    id = id,
    name = title,
    description = content.orEmpty(),
    status = if (status == "APPROVED") ResourceStatus.APPROVED else ResourceStatus.PENDING
)
