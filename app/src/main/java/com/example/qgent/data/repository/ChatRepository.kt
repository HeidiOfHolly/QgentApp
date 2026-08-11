package com.example.qgent.data.repository

import com.example.qgent.data.api.RetrofitClient
import com.example.qgent.data.model.CreateGroupRequest
import com.example.qgent.data.model.GroupDto
import com.example.qgent.data.model.GroupMemberDto
import com.example.qgent.data.model.GroupMessageDto
import com.example.qgent.data.model.MessageContentDto
import com.example.qgent.data.model.SendMessageRequest
import com.example.qgent.data.model.UpdateGroupRequest

class ChatRepository {

    private val service = RetrofitClient.service

    // ── 群 ──

    suspend fun getGroups(
        projectId: String,
        cursor: String? = null,
        limit: Int = 30
    ): Result<List<GroupDto>> = runCatching {
        service.getGroups(projectId, cursor, limit).data!!
    }

    suspend fun createGroup(
        projectId: String,
        title: String,
        description: String? = null
    ): Result<GroupDto> = runCatching {
        service.createGroup(
            projectId,
            CreateGroupRequest(title = title, description = description)
        ).data!!
    }

    suspend fun getGroup(projectId: String, groupId: String): Result<GroupDto> = runCatching {
        service.getGroup(projectId, groupId).data!!
    }

    suspend fun updateGroup(
        projectId: String,
        groupId: String,
        title: String? = null,
        description: String? = null
    ): Result<GroupDto> = runCatching {
        service.updateGroup(
            projectId, groupId,
            UpdateGroupRequest(title = title, description = description)
        ).data!!
    }

    suspend fun archiveGroup(projectId: String, groupId: String): Result<GroupDto> = runCatching {
        service.archiveGroup(projectId, groupId).data!!
    }

    // ── 成员 ──

    suspend fun getMembers(projectId: String, groupId: String): Result<List<GroupMemberDto>> = runCatching {
        service.getGroupMembers(projectId, groupId).data!!
    }

    suspend fun leaveGroup(projectId: String, groupId: String): Result<Unit> = runCatching {
        service.leaveGroup(projectId, groupId).data ?: Unit
    }

    // ── 消息 ──

    suspend fun getMessages(
        projectId: String,
        groupId: String,
        cursor: String? = null,
        limit: Int = 30
    ): Result<List<GroupMessageDto>> = runCatching {
        service.getMessages(projectId, groupId, cursor, limit).data!!
    }

    suspend fun sendMessage(
        projectId: String,
        groupId: String,
        text: String,
        type: String = "TEXT",
        clientMessageId: String? = null
    ): Result<GroupMessageDto> = runCatching {
        service.sendMessage(
            projectId, groupId,
            SendMessageRequest(
                type = type,
                content = MessageContentDto(text = text),
                clientMessageId = clientMessageId
            )
        ).data!!
    }
}
