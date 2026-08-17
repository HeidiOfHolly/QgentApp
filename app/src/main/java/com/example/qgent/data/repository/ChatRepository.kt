package com.example.qgent.data.repository

import com.example.qgent.data.model.GroupDto
import com.example.qgent.data.model.GroupMemberDto
import com.example.qgent.data.model.GroupMessageDto
import com.example.qgent.data.model.MessageContentDto

/**
 * 群聊仓库。所有写操作（createGroup / updateGroup / archiveGroup / leaveGroup / sendMessage）
 * 都要求传 [idempotencyKey]（对应后端强制的 Idempotency-Key 头，缺失返回 400）：
 * 它用于防重复——同一逻辑操作重试时复用同一 UUID，后端据此去重，不是鉴权。
 */
interface ChatRepository {
    suspend fun getGroups(projectId: String, cursor: String? = null, limit: Int = 30): Result<List<GroupDto>>
    suspend fun createGroup(projectId: String, title: String, description: String? = null, memberIds: List<String>? = null, idempotencyKey: String): Result<GroupDto>
    suspend fun getGroup(projectId: String, groupId: String): Result<GroupDto>
    suspend fun updateGroup(projectId: String, groupId: String, title: String? = null, description: String? = null, idempotencyKey: String): Result<GroupDto>
    suspend fun archiveGroup(projectId: String, groupId: String, idempotencyKey: String): Result<GroupDto>
    suspend fun getMembers(projectId: String, groupId: String): Result<List<GroupMemberDto>>
    suspend fun leaveGroup(projectId: String, groupId: String, idempotencyKey: String): Result<Unit>
    suspend fun getMessages(projectId: String, groupId: String, cursor: String? = null, limit: Int = 30): Result<List<GroupMessageDto>>
    // 契约 §7：消息体不再携带 mentions；@Agent 触发任务改为发消息成功后调 trigger-task
    suspend fun sendMessage(projectId: String, groupId: String, type: String, content: MessageContentDto, clientMessageId: String? = null, replyToId: String? = null, idempotencyKey: String): Result<GroupMessageDto>
}
