package com.example.qgent.data.repository

import com.example.qgent.data.datasource.MockDataSource
import com.example.qgent.data.model.GroupDto
import com.example.qgent.data.model.GroupMemberDto
import com.example.qgent.data.model.GroupMessageDto
import com.example.qgent.data.model.MentionDto
import com.example.qgent.data.model.MessageContentDto

class MockChatRepository : ChatRepository {

    override suspend fun getGroups(
        projectId: String,
        cursor: String?,
        limit: Int
    ): Result<List<GroupDto>> = Result.success(MockDataSource.groupsOf(projectId))

    override suspend fun createGroup(
        projectId: String,
        title: String,
        description: String?,
        memberIds: List<String>?,
        idempotencyKey: String
    ): Result<GroupDto> = Result.success(
        GroupDto(
            id = "mock-group-${System.currentTimeMillis()}",
            projectId = projectId,
            title = title,
            description = description,
            type = "REQUIREMENT",
            status = "ACTIVE",
            memberCount = memberIds?.size ?: 0,
            repositoryIds = null,
            latestActivityAt = null,
            latestMessage = null
        )
    )

    override suspend fun getGroup(projectId: String, groupId: String): Result<GroupDto> {
        val group = MockDataSource.groupsOf(projectId).firstOrNull { it.id == groupId }
            ?: return Result.failure(NoSuchElementException("mock 群不存在"))
        return Result.success(group)
    }

    override suspend fun updateGroup(
        projectId: String,
        groupId: String,
        title: String?,
        description: String?,
        idempotencyKey: String
    ): Result<GroupDto> = Result.failure(UnsupportedOperationException("mock 不支持更新群"))

    override suspend fun archiveGroup(projectId: String, groupId: String, idempotencyKey: String): Result<GroupDto> =
        Result.failure(UnsupportedOperationException("mock 不支持归档群"))

    override suspend fun getMembers(projectId: String, groupId: String): Result<List<GroupMemberDto>> =
        Result.success(
            listOf(
                GroupMemberDto(id = "m1", nickname = "张三", avatar = null, memberType = "USER"),
                GroupMemberDto(id = "m2", nickname = "李四", avatar = null, memberType = "USER"),
                // Agent 通过 sendAsAgent 回群后成为群参与者（文档 §7：memberType=AGENT）
                GroupMemberDto(id = "agent-3", nickname = "Developer", avatar = null, memberType = "AGENT")
            )
        )

    override suspend fun leaveGroup(projectId: String, groupId: String, idempotencyKey: String): Result<Unit> = Result.success(Unit)

    override suspend fun getMessages(
        projectId: String,
        groupId: String,
        cursor: String?,
        limit: Int
    ): Result<List<GroupMessageDto>> = Result.success(
        listOf(
            // SYSTEM 消息：成员进群提示，senderType=SYSTEM、senderName=null、type=SYSTEM（灰色小字展示）
            GroupMessageDto(
                id = "sys-1",
                groupId = groupId,
                senderId = "",
                senderName = null,
                senderType = "SYSTEM",
                type = "SYSTEM",
                content = MessageContentDto(text = "张三 加入了群聊"),
                mentions = null,
                replyToId = null,
                clientMessageId = null,
                sequence = 1,
                createdAt = "2026-08-16T02:00:00Z"
            )
        )
    )

    override suspend fun sendMessage(
        projectId: String,
        groupId: String,
        type: String,
        content: MessageContentDto,
        clientMessageId: String?,
        mentions: List<MentionDto>?,
        replyToId: String?,
        idempotencyKey: String
    ): Result<GroupMessageDto> = Result.failure(UnsupportedOperationException("mock 不支持发送消息"))
}
