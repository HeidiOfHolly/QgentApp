package com.example.qgent.data.repository

import com.example.qgent.data.model.AgentDto

/** 真实请求失败时回退到 mock，保证演示环境可用 */
class FallbackAgentRepository(
    private val real: AgentRepository,
    private val mock: AgentRepository
) : AgentRepository {

    override suspend fun getAgents(teamId: String): Result<List<AgentDto>> =
        real.getAgents(teamId).orFallback { mock.getAgents(teamId) }

    override suspend fun getAgent(teamId: String, agentId: String): Result<AgentDto> =
        real.getAgent(teamId, agentId).orFallback { mock.getAgent(teamId, agentId) }

    override suspend fun createAgent(teamId: String, name: String, role: String, avatar: String?, capabilities: List<String>?, prompt: String?): Result<AgentDto> =
        real.createAgent(teamId, name, role, avatar, capabilities, prompt).orFallback { mock.createAgent(teamId, name, role, avatar, capabilities, prompt) }

    override suspend fun updateAgent(teamId: String, agentId: String, name: String?, avatar: String?, capabilities: List<String>?, prompt: String?): Result<AgentDto> =
        real.updateAgent(teamId, agentId, name, avatar, capabilities, prompt).orFallback { mock.updateAgent(teamId, agentId, name, avatar, capabilities, prompt) }

    override suspend fun publishAgent(teamId: String, agentId: String): Result<AgentDto> =
        real.publishAgent(teamId, agentId).orFallback { mock.publishAgent(teamId, agentId) }

    override suspend fun unpublishAgent(teamId: String, agentId: String): Result<AgentDto> =
        real.unpublishAgent(teamId, agentId).orFallback { mock.unpublishAgent(teamId, agentId) }

    override suspend fun archiveAgent(teamId: String, agentId: String): Result<AgentDto> =
        real.archiveAgent(teamId, agentId).orFallback { mock.archiveAgent(teamId, agentId) }

    override suspend fun bindAgentSkills(projectId: String, agentId: String, skillIds: List<String>): Result<AgentDto> =
        real.bindAgentSkills(projectId, agentId, skillIds).orFallback { mock.bindAgentSkills(projectId, agentId, skillIds) }
}
