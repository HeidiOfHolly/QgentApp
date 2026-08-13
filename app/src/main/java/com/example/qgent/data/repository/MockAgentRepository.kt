package com.example.qgent.data.repository

import com.example.qgent.data.model.AgentDto

/** mock Agent：提供系统内置“新手大礼包” Agent，仅支持查询 */
class MockAgentRepository : AgentRepository {

    private val mockAgents = listOf(
        AgentDto("1", "AgentOrchestrator", null, "ORCHESTRATOR", listOf("任务调度", "工作流编排", "质量门禁"), null, "PRIVATE", "ACTIVE", "system"),
        AgentDto("2", "Planner", null, "PLANNER", listOf("需求分析", "任务拆分", "计划编排"), null, "PRIVATE", "ACTIVE", "system"),
        AgentDto("3", "Developer", null, "DEVELOPER", listOf("java", "spring-boot", "api", "react"), null, "PRIVATE", "ACTIVE", "system"),
        AgentDto("4", "Tester", null, "TESTER", listOf("单元测试", "集成测试", "回归测试"), null, "PRIVATE", "ACTIVE", "system"),
        AgentDto("5", "Reviewer", null, "REVIEWER", listOf("代码审查", "规范检查", "安全扫描"), null, "PRIVATE", "ACTIVE", "system")
    )

    override suspend fun getAgents(teamId: String): Result<List<AgentDto>> = Result.success(mockAgents)

    override suspend fun getAgent(teamId: String, agentId: String): Result<AgentDto> {
        val agent = mockAgents.firstOrNull { it.id == agentId }
            ?: return Result.failure(NoSuchElementException("mock Agent 不存在"))
        return Result.success(agent)
    }

    override suspend fun createAgent(teamId: String, name: String, role: String, avatar: String?, capabilities: List<String>?, prompt: String?): Result<AgentDto> =
        Result.failure(UnsupportedOperationException("mock 不支持创建 Agent"))

    override suspend fun updateAgent(teamId: String, agentId: String, name: String?, avatar: String?, capabilities: List<String>?, prompt: String?): Result<AgentDto> =
        Result.failure(UnsupportedOperationException("mock 不支持更新 Agent"))

    override suspend fun publishAgent(teamId: String, agentId: String): Result<AgentDto> =
        Result.failure(UnsupportedOperationException("mock 不支持发布 Agent"))

    override suspend fun unpublishAgent(teamId: String, agentId: String): Result<AgentDto> =
        Result.failure(UnsupportedOperationException("mock 不支持收回 Agent"))

    override suspend fun archiveAgent(teamId: String, agentId: String): Result<AgentDto> =
        Result.failure(UnsupportedOperationException("mock 不支持下线 Agent"))

    override suspend fun bindAgentSkills(projectId: String, agentId: String, skillIds: List<String>): Result<AgentDto> =
        Result.failure(UnsupportedOperationException("mock 不支持绑定 Skill"))
}
