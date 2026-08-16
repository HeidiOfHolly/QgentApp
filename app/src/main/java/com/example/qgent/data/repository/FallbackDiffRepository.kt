package com.example.qgent.data.repository

import com.example.qgent.data.model.DiffFileDto

/** 真实请求失败时回退到 mock，保证演示环境可用（测试完成后可移除） */
class FallbackDiffRepository(
    real: DiffRepository,
    mock: DiffRepository
) : DiffRepository {
    private val fb = FallbackDelegate(real, mock)

    override suspend fun getDiffFiles(projectId: String, diffId: String, cursor: String?, limit: Int) =
        fb.call { getDiffFiles(projectId, diffId, cursor, limit) }
}
