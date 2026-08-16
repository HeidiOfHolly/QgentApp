package com.example.qgent.data.repository

import com.example.qgent.data.api.QgApiService
import com.example.qgent.data.model.DiffFileDto
import com.example.qgent.data.model.toDataOrThrow

class DiffRepositoryImpl(private val service: QgApiService) : DiffRepository {

    override suspend fun getDiffFiles(projectId: String, diffId: String, cursor: String?, limit: Int): Result<List<DiffFileDto>> = apiCall {
        service.getDiffFiles(projectId, diffId, cursor, limit).toDataOrThrow()
    }
}
