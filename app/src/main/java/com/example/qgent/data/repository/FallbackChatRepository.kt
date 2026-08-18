package com.example.qgent.data.repository

import com.example.qgent.data.model.MentionDto
import com.example.qgent.data.model.MessageContentDto

/** 真实请求失败时回退到 mock，保证演示环境可用 */
class FallbackChatRepository(
    real: ChatRepository,
    mock: ChatRepository
) : ChatRepository {
    private val fb = FallbackDelegate(real, mock)

    override suspend fun getGroups(projectId: String, cursor: String?, limit: Int) =
        fb.call { getGroups(projectId, cursor, limit) }

    override suspend fun createGroup(projectId: String, title: String, description: String?, memberIds: List<String>?, idempotencyKey: String) =
        fb.call { createGroup(projectId, title, description, memberIds, idempotencyKey) }

    override suspend fun getGroup(projectId: String, groupId: String) =
        fb.call { getGroup(projectId, groupId) }

    override suspend fun updateGroup(projectId: String, groupId: String, title: String?, description: String?, idempotencyKey: String) =
        fb.call { updateGroup(projectId, groupId, title, description, idempotencyKey) }

    override suspend fun archiveGroup(projectId: String, groupId: String, idempotencyKey: String) =
        fb.call { archiveGroup(projectId, groupId, idempotencyKey) }

    override suspend fun getMembers(projectId: String, groupId: String) =
        fb.call { getMembers(projectId, groupId) }

    override suspend fun leaveGroup(projectId: String, groupId: String, idempotencyKey: String) =
        fb.call { leaveGroup(projectId, groupId, idempotencyKey) }

    override suspend fun getMessages(projectId: String, groupId: String, cursor: String?, limit: Int) =
        fb.call { getMessages(projectId, groupId, cursor, limit) }

    override suspend fun getMessage(projectId: String, groupId: String, messageId: String) =
        fb.call { getMessage(projectId, groupId, messageId) }

    override suspend fun markGroupRead(projectId: String, groupId: String, idempotencyKey: String) =
        fb.call { markGroupRead(projectId, groupId, idempotencyKey) }

    override suspend fun sendMessage(projectId: String, groupId: String, type: String, content: MessageContentDto, clientMessageId: String?, mentions: List<MentionDto>?, replyText: String?, replyToId: String?, idempotencyKey: String) =
        fb.call { sendMessage(projectId, groupId, type, content, clientMessageId, mentions, replyText, replyToId, idempotencyKey) }
}
