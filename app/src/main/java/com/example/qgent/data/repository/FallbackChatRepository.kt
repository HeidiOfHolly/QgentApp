package com.example.qgent.data.repository

import com.example.qgent.data.model.GroupDto
import com.example.qgent.data.model.GroupMemberDto
import com.example.qgent.data.model.GroupMessageDto

/** 真实请求失败时回退到 mock，保证演示环境可用 */
class FallbackChatRepository(
    private val real: ChatRepository,
    private val mock: ChatRepository
) : ChatRepository {

    override suspend fun getGroups(projectId: String, cursor: String?, limit: Int): Result<List<GroupDto>> =
        real.getGroups(projectId, cursor, limit).orFallback { mock.getGroups(projectId, cursor, limit) }

    override suspend fun createGroup(projectId: String, title: String, description: String?): Result<GroupDto> =
        real.createGroup(projectId, title, description).orFallback { mock.createGroup(projectId, title, description) }

    override suspend fun getGroup(projectId: String, groupId: String): Result<GroupDto> =
        real.getGroup(projectId, groupId).orFallback { mock.getGroup(projectId, groupId) }

    override suspend fun updateGroup(projectId: String, groupId: String, title: String?, description: String?): Result<GroupDto> =
        real.updateGroup(projectId, groupId, title, description).orFallback { mock.updateGroup(projectId, groupId, title, description) }

    override suspend fun archiveGroup(projectId: String, groupId: String): Result<GroupDto> =
        real.archiveGroup(projectId, groupId).orFallback { mock.archiveGroup(projectId, groupId) }

    override suspend fun getMembers(projectId: String, groupId: String): Result<List<GroupMemberDto>> =
        real.getMembers(projectId, groupId).orFallback { mock.getMembers(projectId, groupId) }

    override suspend fun leaveGroup(projectId: String, groupId: String): Result<Unit> =
        real.leaveGroup(projectId, groupId).orFallback { mock.leaveGroup(projectId, groupId) }

    override suspend fun getMessages(projectId: String, groupId: String, cursor: String?, limit: Int): Result<List<GroupMessageDto>> =
        real.getMessages(projectId, groupId, cursor, limit).orFallback { mock.getMessages(projectId, groupId, cursor, limit) }

    override suspend fun sendMessage(projectId: String, groupId: String, text: String, type: String, clientMessageId: String?): Result<GroupMessageDto> =
        real.sendMessage(projectId, groupId, text, type, clientMessageId).orFallback { mock.sendMessage(projectId, groupId, text, type, clientMessageId) }
}
