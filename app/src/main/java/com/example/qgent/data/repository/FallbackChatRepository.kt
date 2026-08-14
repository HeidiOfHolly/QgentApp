package com.example.qgent.data.repository

/** 真实请求失败时回退到 mock，保证演示环境可用 */
class FallbackChatRepository(
    real: ChatRepository,
    mock: ChatRepository
) : ChatRepository {
    private val fb = FallbackDelegate(real, mock)

    override suspend fun getGroups(projectId: String, cursor: String?, limit: Int) =
        fb.call { getGroups(projectId, cursor, limit) }

    override suspend fun createGroup(projectId: String, title: String, description: String?) =
        fb.call { createGroup(projectId, title, description) }

    override suspend fun getGroup(projectId: String, groupId: String) =
        fb.call { getGroup(projectId, groupId) }

    override suspend fun updateGroup(projectId: String, groupId: String, title: String?, description: String?) =
        fb.call { updateGroup(projectId, groupId, title, description) }

    override suspend fun archiveGroup(projectId: String, groupId: String) =
        fb.call { archiveGroup(projectId, groupId) }

    override suspend fun getMembers(projectId: String, groupId: String) =
        fb.call { getMembers(projectId, groupId) }

    override suspend fun leaveGroup(projectId: String, groupId: String) =
        fb.call { leaveGroup(projectId, groupId) }

    override suspend fun getMessages(projectId: String, groupId: String, cursor: String?, limit: Int) =
        fb.call { getMessages(projectId, groupId, cursor, limit) }

    override suspend fun sendMessage(projectId: String, groupId: String, text: String, type: String, clientMessageId: String?) =
        fb.call { sendMessage(projectId, groupId, text, type, clientMessageId) }
}
