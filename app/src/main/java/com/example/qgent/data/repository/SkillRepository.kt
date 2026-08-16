package com.example.qgent.data.repository

import com.example.qgent.data.model.CreateSkillRequest
import com.example.qgent.data.model.SkillDto

/**
 * 共享 Skill 仓库（文档 §8）。
 * 写操作（create/submitReview/approve/reject/archive）需 Idempotency-Key。
 */
interface SkillRepository {
    suspend fun getSkills(projectId: String, status: String? = null, tag: String? = null): Result<List<SkillDto>>
    suspend fun getSkill(projectId: String, skillId: String): Result<SkillDto>
    suspend fun createSkill(projectId: String, request: CreateSkillRequest, idempotencyKey: String): Result<SkillDto>
    suspend fun submitReview(projectId: String, skillId: String, idempotencyKey: String): Result<SkillDto>
    suspend fun approve(projectId: String, skillId: String, idempotencyKey: String): Result<SkillDto>
    suspend fun reject(projectId: String, skillId: String, idempotencyKey: String): Result<SkillDto>
    suspend fun archive(projectId: String, skillId: String, idempotencyKey: String): Result<SkillDto>
}
