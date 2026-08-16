package com.example.qgent.data.repository

import com.example.qgent.data.model.DiffFileDto

/**
 * Diff 仓库（文档 §12.3）：拉取 DIFF 消息卡片对应的文件 diff 内容。
 */
interface DiffRepository {
    suspend fun getDiffFiles(projectId: String, diffId: String, cursor: String? = null, limit: Int = 20): Result<List<DiffFileDto>>
}
