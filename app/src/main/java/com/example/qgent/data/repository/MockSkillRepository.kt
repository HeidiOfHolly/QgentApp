package com.example.qgent.data.repository

import com.example.qgent.data.model.CreateSkillRequest
import com.example.qgent.data.model.SkillDto

/**
 * mock Skill 仓库（保底用，真实接口测试完成后删除）。
 * 与 MemoryPool/SkillPool 页面的演示数据保持一致。
 */
class MockSkillRepository : SkillRepository {

    private val mockSkills = listOf(
        SkillDto(
            id = "s1", projectId = "p1", name = "Docker 部署脚本",
            content = "自动构建并推送 Docker 镜像到团队私有仓库，支持多阶段构建、缓存优化与环境变量注入，确保构建产物的一致性。",
            tags = listOf("docker", "ci"), visibility = "PROJECT_SHARED",
            status = "PENDING_REVIEW", creator = null, reviewer = null,
            rejectionReason = null, reviewedAt = null, createdAt = "2026-08-15T08:00:00Z", updatedAt = null
        ),
        SkillDto(
            id = "s2", projectId = "p1", name = "TypeScript 检查",
            content = "对修改的 .ts/.tsx 文件运行 tsc --noEmit 并报告类型错误",
            tags = listOf("ts", "lint"), visibility = "PROJECT_SHARED",
            status = "PUBLISHED", creator = null, reviewer = null,
            rejectionReason = null, reviewedAt = null, createdAt = "2026-08-14T08:00:00Z", updatedAt = null
        ),
        SkillDto(
            id = "s3", projectId = "p1", name = "ESLint 格式化",
            content = "基于团队 .eslintrc 规则自动修复格式问题",
            tags = listOf("eslint", "lint"), visibility = "PROJECT_SHARED",
            status = "PUBLISHED", creator = null, reviewer = null,
            rejectionReason = null, reviewedAt = null, createdAt = "2026-08-13T08:00:00Z", updatedAt = null
        )
    )

    override suspend fun getSkills(projectId: String, status: String?, tag: String?): Result<List<SkillDto>> =
        Result.success(mockSkills.filter { status == null || it.status == status })

    override suspend fun getSkill(projectId: String, skillId: String): Result<SkillDto> =
        Result.success(mockSkills.firstOrNull { it.id == skillId } ?: mockSkills.first())

    override suspend fun createSkill(projectId: String, request: CreateSkillRequest, idempotencyKey: String): Result<SkillDto> =
        Result.success(
            SkillDto(
                id = "mock-skill-${System.currentTimeMillis()}", projectId = projectId,
                name = request.name, content = request.content, tags = request.tags,
                visibility = request.visibility, status = "PENDING_REVIEW",
                creator = null, reviewer = null, rejectionReason = null,
                reviewedAt = null, createdAt = "2026-08-16T08:00:00Z", updatedAt = null
            )
        )

    override suspend fun submitReview(projectId: String, skillId: String, idempotencyKey: String): Result<SkillDto> =
        Result.success(mockSkills.first().copy(status = "PENDING_REVIEW"))

    override suspend fun approve(projectId: String, skillId: String, idempotencyKey: String): Result<SkillDto> =
        Result.success(mockSkills.first().copy(status = "PUBLISHED"))

    override suspend fun reject(projectId: String, skillId: String, idempotencyKey: String): Result<SkillDto> =
        Result.success(mockSkills.first().copy(status = "REJECTED"))

    override suspend fun archive(projectId: String, skillId: String, idempotencyKey: String): Result<SkillDto> =
        Result.success(mockSkills.first().copy(status = "ARCHIVED"))
}
