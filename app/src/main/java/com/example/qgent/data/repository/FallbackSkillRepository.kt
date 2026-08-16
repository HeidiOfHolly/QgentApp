package com.example.qgent.data.repository

import com.example.qgent.data.model.CreateSkillRequest
import com.example.qgent.data.model.SkillDto

/** 真实请求失败时回退到 mock，保证演示环境可用（测试完成后可移除 Fallback 层） */
class FallbackSkillRepository(
    real: SkillRepository,
    mock: SkillRepository
) : SkillRepository {
    private val fb = FallbackDelegate(real, mock)

    override suspend fun getSkills(projectId: String, status: String?, tag: String?) =
        fb.call { getSkills(projectId, status, tag) }

    override suspend fun getSkill(projectId: String, skillId: String) =
        fb.call { getSkill(projectId, skillId) }

    override suspend fun createSkill(projectId: String, request: CreateSkillRequest, idempotencyKey: String) =
        fb.call { createSkill(projectId, request, idempotencyKey) }

    override suspend fun submitReview(projectId: String, skillId: String, idempotencyKey: String) =
        fb.call { submitReview(projectId, skillId, idempotencyKey) }

    override suspend fun approve(projectId: String, skillId: String, idempotencyKey: String) =
        fb.call { approve(projectId, skillId, idempotencyKey) }

    override suspend fun reject(projectId: String, skillId: String, idempotencyKey: String) =
        fb.call { reject(projectId, skillId, idempotencyKey) }

    override suspend fun archive(projectId: String, skillId: String, idempotencyKey: String) =
        fb.call { archive(projectId, skillId, idempotencyKey) }
}
