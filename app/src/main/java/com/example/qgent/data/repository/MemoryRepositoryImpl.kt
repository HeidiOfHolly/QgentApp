package com.example.qgent.data.repository

import com.example.qgent.data.api.QgApiService
import com.example.qgent.data.model.AiMemoryDraftRequest
import com.example.qgent.data.model.CreateMemoryRequest
import com.example.qgent.data.model.MemoryDto
import com.example.qgent.data.model.toDataOrThrow

class MemoryRepositoryImpl(private val service: QgApiService) : MemoryRepository {

    override suspend fun getMemories(projectId: String, status: String?, tag: String?): Result<List<MemoryDto>> = apiCall {
        service.getMemories(projectId, status, tag).toDataOrThrow()
    }

    override suspend fun getMemory(projectId: String, memoryId: String): Result<MemoryDto> = apiCall {
        service.getMemory(projectId, memoryId).toDataOrThrow()
    }

    override suspend fun createMemory(projectId: String, request: CreateMemoryRequest, idempotencyKey: String): Result<MemoryDto> = apiCall {
        service.createMemory(projectId, idempotencyKey, request).toDataOrThrow()
    }

    override suspend fun createAiDraft(projectId: String, request: AiMemoryDraftRequest, idempotencyKey: String): Result<MemoryDto> = apiCall {
        service.createMemoryAiDraft(projectId, idempotencyKey, request).toDataOrThrow()
    }

    override suspend fun submitReview(projectId: String, memoryId: String, idempotencyKey: String): Result<MemoryDto> = apiCall {
        service.submitMemoryReview(projectId, memoryId, idempotencyKey).toDataOrThrow()
    }

    override suspend fun approve(projectId: String, memoryId: String, idempotencyKey: String): Result<MemoryDto> = apiCall {
        service.approveMemory(projectId, memoryId, idempotencyKey).toDataOrThrow()
    }

    override suspend fun reject(projectId: String, memoryId: String, idempotencyKey: String): Result<MemoryDto> = apiCall {
        service.rejectMemory(projectId, memoryId, idempotencyKey).toDataOrThrow()
    }

    override suspend fun archive(projectId: String, memoryId: String, idempotencyKey: String): Result<MemoryDto> = apiCall {
        service.archiveMemory(projectId, memoryId, idempotencyKey).toDataOrThrow()
    }
}
