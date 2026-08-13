package com.example.qgent.data.repository

import com.example.qgent.data.api.RetrofitClient
import com.example.qgent.data.model.AgentDto
import com.example.qgent.data.model.AgentSkillBindingsRequest
import com.example.qgent.data.model.CreateAgentRequest
import com.example.qgent.data.model.UpdateAgentRequest

class AgentRepository {

    private val service = RetrofitClient.service

    // ── 查询 ──

    /** 查询团队下所有可用 Agent：系统 Agent + 本人私有 + 团队已发布 */
    suspend fun getAgents(teamId: String): Result<List<AgentDto>> = runCatching {
        service.getAgents(teamId).data!!
    }

    /** 获取单个 Agent 身份卡 */
    suspend fun getAgent(teamId: String, agentId: String): Result<AgentDto> = runCatching {
        service.getAgent(teamId, agentId).data!!
    }

    // ── 创建与编辑 ──

    /** 创建自己的 PRIVATE Agent */
    suspend fun createAgent(
        teamId: String,
        name: String,
        role: String,
        avatar: String? = null,
        capabilities: List<String>? = null,
        prompt: String? = null
    ): Result<AgentDto> = runCatching {
        service.createAgent(
            teamId,
            CreateAgentRequest(
                name = name,
                avatar = avatar,
                role = role,
                capabilities = capabilities,
                prompt = prompt
            )
        ).data!!
    }

    /** 编辑自己的 Agent */
    suspend fun updateAgent(
        teamId: String,
        agentId: String,
        name: String? = null,
        avatar: String? = null,
        capabilities: List<String>? = null,
        prompt: String? = null
    ): Result<AgentDto> = runCatching {
        service.updateAgent(
            teamId, agentId,
            UpdateAgentRequest(
                name = name,
                avatar = avatar,
                capabilities = capabilities,
                prompt = prompt
            )
        ).data!!
    }

    // ── 发布/收回/下线 ──

    /** 发布为 TEAM_SHARED */
    suspend fun publishAgent(teamId: String, agentId: String): Result<AgentDto> = runCatching {
        service.publishAgent(teamId, agentId).data!!
    }

    /** 收回为 PRIVATE */
    suspend fun unpublishAgent(teamId: String, agentId: String): Result<AgentDto> = runCatching {
        service.unpublishAgent(teamId, agentId).data!!
    }

    /** 下线 Agent */
    suspend fun archiveAgent(teamId: String, agentId: String): Result<AgentDto> = runCatching {
        service.archiveAgent(teamId, agentId).data!!
    }

    // ── Skill 绑定 ──

    /** 为 Agent 装配当前项目可用 Skill */
    suspend fun bindAgentSkills(
        projectId: String,
        agentId: String,
        skillIds: List<String>
    ): Result<AgentDto> = runCatching {
        service.bindAgentSkills(
            projectId, agentId,
            AgentSkillBindingsRequest(skillIds = skillIds)
        ).data!!
    }
}
