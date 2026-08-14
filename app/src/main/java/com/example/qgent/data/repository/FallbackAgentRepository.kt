package com.example.qgent.data.repository

/** 真实请求失败时回退到 mock，保证演示环境可用 */
class FallbackAgentRepository(
    real: AgentRepository,
    mock: AgentRepository
) : AgentRepository {
    private val fb = FallbackDelegate(real, mock)

    override suspend fun getAgents(teamId: String) = fb.call { getAgents(teamId) }

    override suspend fun getAgent(teamId: String, agentId: String) = fb.call { getAgent(teamId, agentId) }

    override suspend fun createAgent(
        teamId: String,
        name: String,
        role: String,
        avatar: String?,
        capabilities: List<String>?,
        prompt: String?
    ) = fb.call { createAgent(teamId, name, role, avatar, capabilities, prompt) }

    override suspend fun updateAgent(
        teamId: String,
        agentId: String,
        name: String?,
        avatar: String?,
        capabilities: List<String>?,
        prompt: String?
    ) = fb.call { updateAgent(teamId, agentId, name, avatar, capabilities, prompt) }

    override suspend fun publishAgent(teamId: String, agentId: String) = fb.call { publishAgent(teamId, agentId) }

    override suspend fun unpublishAgent(teamId: String, agentId: String) = fb.call { unpublishAgent(teamId, agentId) }

    override suspend fun archiveAgent(teamId: String, agentId: String) = fb.call { archiveAgent(teamId, agentId) }

    override suspend fun bindAgentSkills(projectId: String, agentId: String, skillIds: List<String>) =
        fb.call { bindAgentSkills(projectId, agentId, skillIds) }
}
