package com.example.qgent.data.repository

import com.example.qgent.data.model.GroupDto
import com.example.qgent.data.model.GroupMemberDto
import com.example.qgent.data.model.GroupMessageDto
import com.example.qgent.data.model.GroupReadResponse
import com.example.qgent.data.model.MentionDto
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
    /** v2.0.6 §9：邀请项目成员入群（群创建者或 Project Admin；仅 REQUIREMENT 群） */
    suspend fun addGroupMember(projectId: String, groupId: String, userId: String, idempotencyKey: String): Result<GroupMemberDto>
    /** v2.0.6 §9：移出群成员（群创建者或 Project Admin；仅 REQUIREMENT 群） */
    suspend fun removeGroupMember(projectId: String, groupId: String, memberUserId: String, idempotencyKey: String): Result<Unit>
    suspend fun getMessages(projectId: String, groupId: String, cursor: String? = null, limit: Int = 30): Result<List<GroupMessageDto>>
    /** v2.0.6 §1.3：按消息 ID 拉取单条群消息（通知直达被 @ 消息定位） */
    suspend fun getMessage(projectId: String, groupId: String, messageId: String): Result<GroupMessageDto>
    /** v2.0.6 §1.2：进群全读（后端已读游标推进，前端不再本地假已读） */
    suspend fun markGroupRead(projectId: String, groupId: String, idempotencyKey: String): Result<GroupReadResponse>
    // v2.0.6 §1.4：消息体恢复 mentions（@用户通知 / @Agent 自动触发任务）；QUOTE 回复正文走顶层 replyText
    suspend fun sendMessage(projectId: String, groupId: String, type: String, content: MessageContentDto, clientMessageId: String? = null, mentions: List<MentionDto>? = null, replyText: String? = null, replyToId: String? = null, idempotencyKey: String): Result<GroupMessageDto>
}
