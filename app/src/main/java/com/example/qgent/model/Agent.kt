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

enum class AgentVisibility { PRIVATE, PENDING, TEAM }

/** 后端返回的角色 wire 值 → 中文标签。
 *  前端枚举只收敛 4 种执行角色，但后端仍可能返回 ORCHESTRATOR / REPORTER / GENERAL 等种子角色，
 *  统一在此映射，避免名片/详情被兜底成「开发者」或显示英文原值；未知值原样返回。 */
fun mapAgentRoleDisplay(role: String): String = when (role) {
    "PLANNER" -> "规划者"
    "DEVELOPER" -> "开发者"
    "TESTER" -> "测试者"
    "REVIEWER" -> "审查者"
    "ORCHESTRATOR" -> "编排助手"
    "REPORTER" -> "报告"
    "GENERAL" -> "通用"
    else -> role
}

data class Agent(
    val id: String,
    val name: String,
    val description: String,
    val role: AgentRole,
    /** 后端返回的角色原始值（roleWire）：枚举只收敛 4 种，ORCHESTRATOR/GENERAL/REPORTER 等在此保留原值，
     *  名片/详情用 [roleLabel] 展示而非兜底成 DEVELOPER */
    val roleWire: String? = null,
    val capabilities: List<String> = emptyList(),
    val status: AgentStatus = AgentStatus.ACTIVE,
    val visibility: AgentVisibility = AgentVisibility.TEAM,
    val avatar: String? = null,
    val createdBy: String? = null
) {
    /** 名片/详情角色标签：优先按后端原始角色值映射，缺失时回退枚举 displayName */
    fun roleLabel(): String =
        roleWire?.takeIf { it.isNotBlank() }?.let { mapAgentRoleDisplay(it) } ?: role.displayName()
}
