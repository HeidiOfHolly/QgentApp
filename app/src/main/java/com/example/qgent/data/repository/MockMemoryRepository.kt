package com.example.qgent.data.repository

import com.example.qgent.data.model.CreateMemoryRequest
import com.example.qgent.data.model.MemoryDto

/**
 * mock Memory 仓库（保底用，真实接口测试完成后删除）。
 * 与 MemoryPool/SkillPool 页面的演示数据保持一致。
 */
class MockMemoryRepository : MemoryRepository {

    private val mockMemories = listOf(
        MemoryDto(
            id = "m1", projectId = "p1", title = "登录状态持久化方案",
            content = "描述了跨 Activity 的登录状态管理策略，建议使用 SharedPreferences 配合 LiveData 实现全局登录状态同步，避免在多个 Activity 中重复检查。",
            category = "架构", tags = listOf("登录", "状态"), status = "PENDING_REVIEW",
            creator = null, reviewer = null, rejectionReason = null,
            reviewedAt = null, createdAt = "2026-08-15T08:00:00Z"
        ),
        MemoryDto(
            id = "m2", projectId = "p1", title = "RSA 加密流程说明",
            content = "前后端 RSA 公钥加密流程的详细说明与注意事项，包含密钥长度选择、填充方式配置以及前后端传输过程中的编码规范。",
            category = "安全", tags = listOf("rsa", "加密"), status = "PENDING_REVIEW",
            creator = null, reviewer = null, rejectionReason = null,
            reviewedAt = null, createdAt = "2026-08-14T08:00:00Z"
        ),
        MemoryDto(
            id = "m3", projectId = "p1", title = "React 组件规范",
            content = "统一项目 React 组件命名、文件结构与状态管理规范",
            category = "前端", tags = listOf("react"), status = "APPROVED",
            creator = null, reviewer = null, rejectionReason = null,
            reviewedAt = null, createdAt = "2026-08-13T08:00:00Z"
        ),
        MemoryDto(
            id = "m4", projectId = "p1", title = "API 接口约定",
            content = "RESTful API 统一返回格式、分页与错误码约定",
            category = "后端", tags = listOf("api"), status = "APPROVED",
            creator = null, reviewer = null, rejectionReason = null,
            reviewedAt = null, createdAt = "2026-08-12T08:00:00Z"
        ),
        MemoryDto(
            id = "m5", projectId = "p1", title = "Git 提交规范",
            content = "Conventional Commits 格式要求与分支命名规则",
            category = "工程化", tags = listOf("git"), status = "APPROVED",
            creator = null, reviewer = null, rejectionReason = null,
            reviewedAt = null, createdAt = "2026-08-11T08:00:00Z"
        )
    )

    override suspend fun getMemories(projectId: String, status: String?, tag: String?): Result<List<MemoryDto>> =
        Result.success(mockMemories.filter { status == null || it.status == status })

    override suspend fun getMemory(projectId: String, memoryId: String): Result<MemoryDto> =
        Result.success(mockMemories.firstOrNull { it.id == memoryId } ?: mockMemories.first())

    override suspend fun createMemory(projectId: String, request: CreateMemoryRequest, idempotencyKey: String): Result<MemoryDto> =
        Result.success(
            MemoryDto(
                id = "mock-memory-${System.currentTimeMillis()}", projectId = projectId,
                title = request.title, content = request.content, category = request.category,
                tags = request.tags, status = "PENDING_REVIEW",
                creator = null, reviewer = null, rejectionReason = null,
                reviewedAt = null, createdAt = "2026-08-16T08:00:00Z"
            )
        )

    override suspend fun createAiDraft(projectId: String, request: com.example.qgent.data.model.AiMemoryDraftRequest, idempotencyKey: String): Result<MemoryDto> =
        Result.success(
            MemoryDto(
                id = "mock-memory-ai-${System.currentTimeMillis()}", projectId = projectId,
                title = "AI 总结草稿", content = "（AI 按群自动检索生成的草稿）", category = null,
                tags = null, status = "DRAFT",
                creator = null, reviewer = null, rejectionReason = null,
                reviewedAt = null, createdAt = "2026-08-16T08:00:00Z"
            )
        )

    override suspend fun submitReview(projectId: String, memoryId: String, idempotencyKey: String): Result<MemoryDto> =
        Result.success(mockMemories.first().copy(status = "PENDING_REVIEW"))

    override suspend fun approve(projectId: String, memoryId: String, idempotencyKey: String): Result<MemoryDto> =
        Result.success(mockMemories.first().copy(status = "APPROVED"))

    override suspend fun reject(projectId: String, memoryId: String, idempotencyKey: String): Result<MemoryDto> =
        Result.success(mockMemories.first().copy(status = "REJECTED"))

    override suspend fun archive(projectId: String, memoryId: String, idempotencyKey: String): Result<MemoryDto> =
        Result.success(mockMemories.first().copy(status = "ARCHIVED"))
}
