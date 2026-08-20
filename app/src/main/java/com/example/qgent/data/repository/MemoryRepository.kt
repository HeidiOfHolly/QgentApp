package com.example.qgent.data.repository

import com.example.qgent.data.model.AiMemoryDraftRequest
import com.example.qgent.data.model.CreateMemoryRequest
import com.example.qgent.data.model.MemoryDto

/**
 * 共享 Memory 仓库（文档 §9）。
 * 写操作（create/createAiDraft/submitReview/approve/reject/archive）需 Idempotency-Key。
 */
interface MemoryRepository {
    suspend fun getMemories(projectId: String, status: String? = null, tag: String? = null): Result<List<MemoryDto>>
    suspend fun getMemory(projectId: String, memoryId: String): Result<MemoryDto>
    suspend fun createMemory(projectId: String, request: CreateMemoryRequest, idempotencyKey: String): Result<MemoryDto>
    /** 群聊 AI 总结生成草稿（v2.0.6 §9：按群自动检索，客户端不勾选消息） */
    suspend fun createAiDraft(projectId: String, request: AiMemoryDraftRequest, idempotencyKey: String): Result<MemoryDto>
    suspend fun submitReview(projectId: String, memoryId: String, idempotencyKey: String): Result<MemoryDto>
    suspend fun approve(projectId: String, memoryId: String, idempotencyKey: String): Result<MemoryDto>
    suspend fun reject(projectId: String, memoryId: String, idempotencyKey: String): Result<MemoryDto>
    suspend fun archive(projectId: String, memoryId: String, idempotencyKey: String): Result<MemoryDto>
}
