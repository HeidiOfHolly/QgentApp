package com.example.qgent.data.repository

import com.example.qgent.data.model.AgentDto

interface AgentRepository {
    suspend fun getAgents(teamId: String): Result<List<AgentDto>>
    suspend fun getAgent(teamId: String, agentId: String): Result<AgentDto>
    suspend fun createAgent(teamId: String, name: String, role: String, avatar: String? = null, capabilities: List<String>? = null, prompt: String? = null): Result<AgentDto>
    suspend fun updateAgent(teamId: String, agentId: String, name: String? = null, avatar: String? = null, capabilities: List<String>? = null, prompt: String? = null): Result<AgentDto>
    suspend fun publishAgent(teamId: String, agentId: String): Result<AgentDto>
    suspend fun unpublishAgent(teamId: String, agentId: String): Result<AgentDto>
    suspend fun archiveAgent(teamId: String, agentId: String): Result<AgentDto>
    suspend fun bindAgentSkills(projectId: String, agentId: String, skillIds: List<String>): Result<AgentDto>
}
