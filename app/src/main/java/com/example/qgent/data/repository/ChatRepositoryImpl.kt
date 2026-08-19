package com.example.qgent.data.repository

import android.util.Log
import com.example.qgent.data.api.QgApiService
import com.example.qgent.data.model.AddGroupMemberRequest
import com.example.qgent.data.model.CreateGroupRequest
import com.example.qgent.data.model.GroupDto
import com.example.qgent.data.model.GroupMemberDto
import com.example.qgent.data.model.GroupMessageDto
import com.example.qgent.data.model.GroupReadResponse
import com.example.qgent.data.model.MentionDto
import com.example.qgent.data.model.MessageContentDto
import com.example.qgent.data.model.MessagePageDto
import com.example.qgent.data.model.requireData
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
        val resp = service.getGroupMembers(projectId, groupId)
        // 诊断日志：群友头像不显示时核对后端成员响应的实际字段名（avatar / avatarUrl / …）
        android.util.Log.d("MemberRaw", "getMembers($groupId): ${resp.body()?.let { com.google.gson.Gson().toJson(it) }}")
        resp.toDataOrThrow()
    }

    override suspend fun leaveGroup(projectId: String, groupId: String, idempotencyKey: String): Result<Unit> = apiCall {
        service.leaveGroup(projectId, groupId, idempotencyKey).toUnitOrThrow()
    }

    override suspend fun addGroupMember(projectId: String, groupId: String, userId: String, idempotencyKey: String): Result<GroupMemberDto> = apiCall {
        service.addGroupMember(projectId, groupId, idempotencyKey, AddGroupMemberRequest(userId)).toDataOrThrow()
    }

    override suspend fun removeGroupMember(projectId: String, groupId: String, memberUserId: String, idempotencyKey: String): Result<Unit> = apiCall {
        service.removeGroupMember(projectId, groupId, memberUserId, idempotencyKey).toUnitOrThrow()
    }

    override suspend fun getMessages(
        projectId: String,
        groupId: String,
        cursor: String?,
        limit: Int
    ): Result<List<GroupMessageDto>> = apiCall {
        service.getMessages(projectId, groupId, cursor, limit).toDataOrThrow()
    }

    /** 消息分页：解析 data + page（nextCursor/hasMore），供聊天页上滑加载更早消息 */
    override suspend fun getMessagesPage(
        projectId: String,
        groupId: String,
        cursor: String?,
        limit: Int
    ): Result<MessagePageDto> = apiCall {
        val resp = service.getMessages(projectId, groupId, cursor, limit)
        val body = resp.body()
        val data = body?.requireData()
            ?: throw com.example.qgent.data.model.ApiException("EMPTY_RESPONSE", "响应为空")
        MessagePageDto(
            messages = data,
            nextCursor = body.page?.nextCursor,
            hasMore = body.page?.hasMore ?: false
        )
    }

    override suspend fun getMessage(projectId: String, groupId: String, messageId: String): Result<GroupMessageDto> = apiCall {
        service.getMessage(projectId, groupId, messageId).toDataOrThrow()
    }

    override suspend fun markGroupRead(projectId: String, groupId: String, idempotencyKey: String): Result<GroupReadResponse> = apiCall {
        service.markGroupRead(projectId, groupId, idempotencyKey).toDataOrThrow()
    }

    override suspend fun sendMessage(
        projectId: String,
        groupId: String,
        type: String,
        content: MessageContentDto,
        clientMessageId: String?,
        mentions: List<MentionDto>?,
        replyText: String?,
        replyToId: String?,
        idempotencyKey: String
    ): Result<GroupMessageDto> = apiCall {
        val body = SendMessageRequest(
            type = type,
            content = content,
            replyText = replyText,
            mentions = mentions,
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
