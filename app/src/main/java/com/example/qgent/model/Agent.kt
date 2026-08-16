package com.example.qgent.model

enum class AgentStatus { ACTIVE, RUNNING, ERROR, ARCHIVED }

enum class AgentRole {
    ORCHESTRATOR,
    PLANNER,
    DEVELOPER,
    TESTER,
    REVIEWER,
    GENERAL;

    fun displayName(): String = when (this) {
        ORCHESTRATOR -> "调度者"
        PLANNER -> "规划者"
        DEVELOPER -> "开发者"
        TESTER -> "测试者"
        REVIEWER -> "审查者"
        GENERAL -> "通用"
    }
}

enum class AgentVisibility { PRIVATE, TEAM_SHARED }

data class Agent(
    val id: String,
    val name: String,
    val description: String,
    val role: AgentRole,
    val capabilities: List<String> = emptyList(),
    val status: AgentStatus = AgentStatus.ACTIVE,
    val visibility: AgentVisibility = AgentVisibility.TEAM_SHARED,
    val avatar: String? = null,
    val createdBy: String? = null
)
