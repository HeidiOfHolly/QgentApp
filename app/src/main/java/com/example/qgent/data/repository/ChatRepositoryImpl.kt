package com.example.qgent.data.repository

import com.example.qgent.data.api.QgApiService
import com.example.qgent.data.model.CreateGroupRequest
import com.example.qgent.data.model.GroupDto
import com.example.qgent.data.model.GroupMemberDto
import com.example.qgent.data.model.GroupMessageDto
import com.example.qgent.data.model.MessageContentDto
import com.example.qgent.data.model.SendMessageRequest
import com.example.qgent.data.model.UpdateGroupRequest
import com.example.qgent.data.model.toDataOrThrow
import com.example.qgent.data.model.toUnitOrThrow

class ChatRepositoryImpl(private val service: QgApiService) : ChatRepository {

    override suspend fun getGroups(
        projectId: String,
        cursor: String?,
        limit: Int
    ): Result<List<GroupDto>> = runCatching {
        service.getGroups(projectId, cursor, limit).toDataOrThrow()
    }

    override suspend fun createGroup(
        projectId: String,
        title: String,
        description: String?
    ): Result<GroupDto> = runCatching {
        service.createGroup(
            projectId,
            CreateGroupRequest(title = title, description = description)
        ).toDataOrThrow()
    }

    override suspend fun getGroup(projectId: String, groupId: String): Result<GroupDto> = runCatching {
        service.getGroup(projectId, groupId).toDataOrThrow()
    }

    override suspend fun updateGroup(
        projectId: String,
        groupId: String,
        title: String?,
        description: String?
    ): Result<GroupDto> = runCatching {
        service.updateGroup(
            projectId, groupId,
            UpdateGroupRequest(title = title, description = description)
        ).toDataOrThrow()
    }

    override suspend fun archiveGroup(projectId: String, groupId: String): Result<GroupDto> = runCatching {
        service.archiveGroup(projectId, groupId).toDataOrThrow()
    }

    override suspend fun getMembers(projectId: String, groupId: String): Result<List<GroupMemberDto>> = runCatching {
        service.getGroupMembers(projectId, groupId).toDataOrThrow()
    }

    override suspend fun leaveGroup(projectId: String, groupId: String): Result<Unit> = runCatching {
        service.leaveGroup(projectId, groupId).toUnitOrThrow()
    }

    override suspend fun getMessages(
        projectId: String,
        groupId: String,
        cursor: String?,
        limit: Int
    ): Result<List<GroupMessageDto>> = runCatching {
        service.getMessages(projectId, groupId, cursor, limit).toDataOrThrow()
    }

    override suspend fun sendMessage(
        projectId: String,
        groupId: String,
        text: String,
        type: String,
        clientMessageId: String?
    ): Result<GroupMessageDto> = runCatching {
        service.sendMessage(
            projectId, groupId,
            SendMessageRequest(
                type = type,
                content = MessageContentDto(text = text),
                clientMessageId = clientMessageId
            )
        ).toDataOrThrow()
    }
}
