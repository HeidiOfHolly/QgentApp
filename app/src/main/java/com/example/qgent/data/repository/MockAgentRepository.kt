package com.example.qgent.data.repository

import com.example.qgent.data.datasource.MockDataSource
import com.example.qgent.data.model.AgentDto

/** mock Agent：提供系统内置“新手大礼包” Agent，仅支持查询 */
class MockAgentRepository : AgentRepository {

    override suspend fun getAgents(teamId: String): Result<List<AgentDto>> = Result.success(MockDataSource.mockAgents)

    override suspend fun getAgent(teamId: String, agentId: String): Result<AgentDto> {
        val agent = MockDataSource.mockAgents.firstOrNull { it.id == agentId }
            ?: return Result.failure(NoSuchElementException("mock Agent 不存在"))
        return Result.success(agent)
    }

    override suspend fun createAgent(teamId: String, name: String, role: String, avatar: String?, capabilities: List<String>?, prompt: String?, idempotencyKey: String): Result<AgentDto> =
        Result.failure(UnsupportedOperationException("mock 不支持创建 Agent"))

    override suspend fun updateAgent(teamId: String, agentId: String, name: String?, avatar: String?, capabilities: List<String>?, prompt: String?, idempotencyKey: String): Result<AgentDto> =
        Result.failure(UnsupportedOperationException("mock 不支持更新 Agent"))

    override suspend fun publishAgent(teamId: String, agentId: String, idempotencyKey: String): Result<AgentDto> =
        Result.failure(UnsupportedOperationException("mock 不支持发布 Agent"))

    override suspend fun unpublishAgent(teamId: String, agentId: String, idempotencyKey: String): Result<AgentDto> =
        Result.failure(UnsupportedOperationException("mock 不支持收回 Agent"))

    override suspend fun archiveAgent(teamId: String, agentId: String, idempotencyKey: String): Result<AgentDto> =
        Result.failure(UnsupportedOperationException("mock 不支持下线 Agent"))
}
