package com.example.qgent.data.repository

import com.example.qgent.data.api.QgApiService
import com.example.qgent.data.model.AgentDto
import com.example.qgent.data.model.AgentSkillBindingsRequest
import com.example.qgent.data.model.CreateAgentRequest
import com.example.qgent.data.model.UpdateAgentRequest
import com.example.qgent.data.model.toDataOrThrow

class AgentRepositoryImpl(private val service: QgApiService) : AgentRepository {

    override suspend fun getAgents(teamId: String): Result<List<AgentDto>> = runCatching {
        service.getAgents(teamId).toDataOrThrow()
    }

    override suspend fun getAgent(teamId: String, agentId: String): Result<AgentDto> = runCatching {
        service.getAgent(teamId, agentId).toDataOrThrow()
    }

    override suspend fun createAgent(
        teamId: String,
        name: String,
        role: String,
        avatar: String?,
        capabilities: List<String>?,
        prompt: String?
    ): Result<AgentDto> = runCatching {
        service.createAgent(
            teamId,
            CreateAgentRequest(name, avatar, role, capabilities, prompt)
        ).toDataOrThrow()
    }

    override suspend fun updateAgent(
        teamId: String,
        agentId: String,
        name: String?,
        avatar: String?,
        capabilities: List<String>?,
        prompt: String?
    ): Result<AgentDto> = runCatching {
        service.updateAgent(
            teamId, agentId,
            UpdateAgentRequest(name, avatar, capabilities, prompt)
        ).toDataOrThrow()
    }

    override suspend fun publishAgent(teamId: String, agentId: String): Result<AgentDto> = runCatching {
        service.publishAgent(teamId, agentId).toDataOrThrow()
    }

    override suspend fun unpublishAgent(teamId: String, agentId: String): Result<AgentDto> = runCatching {
        service.unpublishAgent(teamId, agentId).toDataOrThrow()
    }

    override suspend fun archiveAgent(teamId: String, agentId: String): Result<AgentDto> = runCatching {
        service.archiveAgent(teamId, agentId).toDataOrThrow()
    }

    override suspend fun bindAgentSkills(
        projectId: String,
        agentId: String,
        skillIds: List<String>
    ): Result<AgentDto> = runCatching {
        service.bindAgentSkills(projectId, agentId, AgentSkillBindingsRequest(skillIds)).toDataOrThrow()
    }
}
