package com.example.qgent.data.repository

import com.example.qgent.data.model.CreateMemoryRequest
import com.example.qgent.data.model.MemoryDto

/** 真实请求失败时回退到 mock，保证演示环境可用（测试完成后可移除 Fallback 层） */
class FallbackMemoryRepository(
    real: MemoryRepository,
    mock: MemoryRepository
) : MemoryRepository {
    private val fb = FallbackDelegate(real, mock)

    override suspend fun getMemories(projectId: String, status: String?, tag: String?) =
        fb.call { getMemories(projectId, status, tag) }

    override suspend fun getMemory(projectId: String, memoryId: String) =
        fb.call { getMemory(projectId, memoryId) }

    override suspend fun createMemory(projectId: String, request: CreateMemoryRequest, idempotencyKey: String) =
        fb.call { createMemory(projectId, request, idempotencyKey) }

    override suspend fun createAiDraft(projectId: String, request: CreateMemoryRequest, idempotencyKey: String) =
        fb.call { createAiDraft(projectId, request, idempotencyKey) }

    override suspend fun submitReview(projectId: String, memoryId: String, idempotencyKey: String) =
        fb.call { submitReview(projectId, memoryId, idempotencyKey) }

    override suspend fun approve(projectId: String, memoryId: String, idempotencyKey: String) =
        fb.call { approve(projectId, memoryId, idempotencyKey) }

    override suspend fun reject(projectId: String, memoryId: String, idempotencyKey: String) =
        fb.call { reject(projectId, memoryId, idempotencyKey) }

    override suspend fun archive(projectId: String, memoryId: String, idempotencyKey: String) =
        fb.call { archive(projectId, memoryId, idempotencyKey) }
}
