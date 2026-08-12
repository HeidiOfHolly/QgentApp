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
