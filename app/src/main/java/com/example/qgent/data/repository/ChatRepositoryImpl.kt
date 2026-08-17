package com.example.qgent.data.repository

import android.util.Log
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
    ): Result<List<GroupDto>> = apiCall {
        service.getGroups(projectId, cursor, limit).toDataOrThrow()
    }

    override suspend fun createGroup(
        projectId: String,
        title: String,
        description: String?,
        memberIds: List<String>?,
        idempotencyKey: String
    ): Result<GroupDto> = apiCall {
        service.createGroup(
            projectId,
            idempotencyKey,
            CreateGroupRequest(title = title, description = description, memberIds = memberIds)
        ).toDataOrThrow()
    }

    override suspend fun getGroup(projectId: String, groupId: String): Result<GroupDto> = apiCall {
        service.getGroup(projectId, groupId).toDataOrThrow()
    }

    override suspend fun updateGroup(
        projectId: String,
        groupId: String,
        title: String?,
        description: String?,
        idempotencyKey: String
    ): Result<GroupDto> = apiCall {
        service.updateGroup(
            projectId, groupId,
            idempotencyKey,
            UpdateGroupRequest(title = title, description = description)
        ).toDataOrThrow()
    }

    override suspend fun archiveGroup(projectId: String, groupId: String, idempotencyKey: String): Result<GroupDto> = apiCall {
        service.archiveGroup(projectId, groupId, idempotencyKey).toDataOrThrow()
    }

    override suspend fun getMembers(projectId: String, groupId: String): Result<List<GroupMemberDto>> = apiCall {
        service.getGroupMembers(projectId, groupId).toDataOrThrow()
    }

    override suspend fun leaveGroup(projectId: String, groupId: String, idempotencyKey: String): Result<Unit> = apiCall {
        service.leaveGroup(projectId, groupId, idempotencyKey).toUnitOrThrow()
    }

    override suspend fun getMessages(
        projectId: String,
        groupId: String,
        cursor: String?,
        limit: Int
    ): Result<List<GroupMessageDto>> = apiCall {
        service.getMessages(projectId, groupId, cursor, limit).toDataOrThrow()
    }

    override suspend fun sendMessage(
        projectId: String,
        groupId: String,
        type: String,
        content: MessageContentDto,
        clientMessageId: String?,
        replyToId: String?,
        idempotencyKey: String
    ): Result<GroupMessageDto> = apiCall {
        val body = SendMessageRequest(
            type = type,
            content = content,
            clientMessageId = clientMessageId,
            replyToId = replyToId
        )
        // 排查「请求体格式不对」等后端校验错误时核对实际发送的 JSON
        Log.d("SendMsg", "sendMessage body: ${requestGson.toJson(body)}")
        service.sendMessage(projectId, groupId, idempotencyKey, body).toDataOrThrow()
    }

    companion object {
        private val requestGson = com.google.gson.Gson()
    }
}
