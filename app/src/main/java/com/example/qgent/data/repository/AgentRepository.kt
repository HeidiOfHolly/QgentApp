package com.example.qgent.data.repository

import com.example.qgent.data.model.AgentDto
import com.example.qgent.data.model.AgentSkillBindingsResponse

/**
 * Agent 仓库。所有写操作（create/update/publish/unpublish/archive/bindAgentSkills）
 * 都要求传 [idempotencyKey]（后端强制的 Idempotency-Key 头，缺失返回 400），
 * 用于防重复：同一逻辑操作重试复用同一 UUID，后端据此去重，非鉴权。
 */
interface AgentRepository {
    suspend fun getAgents(teamId: String): Result<List<AgentDto>>
    suspend fun getAgent(teamId: String, agentId: String): Result<AgentDto>
    suspend fun createAgent(teamId: String, name: String, role: String, avatar: String? = null, capabilities: List<String>? = null, prompt: String? = null, idempotencyKey: String): Result<AgentDto>
    suspend fun updateAgent(teamId: String, agentId: String, name: String? = null, avatar: String? = null, capabilities: List<String>? = null, prompt: String? = null, idempotencyKey: String): Result<AgentDto>
    suspend fun publishAgent(teamId: String, agentId: String, idempotencyKey: String): Result<AgentDto>
    suspend fun unpublishAgent(teamId: String, agentId: String, idempotencyKey: String): Result<AgentDto>
    suspend fun archiveAgent(teamId: String, agentId: String, idempotencyKey: String): Result<AgentDto>
    suspend fun bindAgentSkills(projectId: String, agentId: String, skillIds: List<String>, idempotencyKey: String): Result<AgentDto>
    /** 读取 Agent 在当前项目的 Skill 绑定集（项目成员） */
    suspend fun getAgentSkillBindings(projectId: String, agentId: String): Result<AgentSkillBindingsResponse>
}
