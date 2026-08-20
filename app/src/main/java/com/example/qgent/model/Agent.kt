package com.example.qgent.model

enum class AgentStatus { ACTIVE, RUNNING, ERROR, ARCHIVED }

enum class AgentRole {
    PLANNER,
    DEVELOPER,
    TESTER,
    REVIEWER;

    fun displayName(): String = when (this) {
        PLANNER -> "规划者"
        DEVELOPER -> "开发者"
        TESTER -> "测试者"
        REVIEWER -> "审查者"
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
