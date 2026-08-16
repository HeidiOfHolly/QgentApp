package com.example.qgent.data.repository

import com.example.qgent.data.api.QgApiService
import com.example.qgent.data.model.CreateSkillRequest
import com.example.qgent.data.model.SkillDto
import com.example.qgent.data.model.toDataOrThrow

class SkillRepositoryImpl(private val service: QgApiService) : SkillRepository {

    override suspend fun getSkills(projectId: String, status: String?, tag: String?): Result<List<SkillDto>> = apiCall {
        service.getSkills(projectId, status, tag).toDataOrThrow()
    }

    override suspend fun getSkill(projectId: String, skillId: String): Result<SkillDto> = apiCall {
        service.getSkill(projectId, skillId).toDataOrThrow()
    }

    override suspend fun createSkill(projectId: String, request: CreateSkillRequest, idempotencyKey: String): Result<SkillDto> = apiCall {
        service.createSkill(projectId, idempotencyKey, request).toDataOrThrow()
    }

    override suspend fun submitReview(projectId: String, skillId: String, idempotencyKey: String): Result<SkillDto> = apiCall {
        service.submitSkillReview(projectId, skillId, idempotencyKey).toDataOrThrow()
    }

    override suspend fun approve(projectId: String, skillId: String, idempotencyKey: String): Result<SkillDto> = apiCall {
        service.approveSkill(projectId, skillId, idempotencyKey).toDataOrThrow()
    }

    override suspend fun reject(projectId: String, skillId: String, idempotencyKey: String): Result<SkillDto> = apiCall {
        service.rejectSkill(projectId, skillId, idempotencyKey).toDataOrThrow()
    }

    override suspend fun archive(projectId: String, skillId: String, idempotencyKey: String): Result<SkillDto> = apiCall {
        service.archiveSkill(projectId, skillId, idempotencyKey).toDataOrThrow()
    }
}
