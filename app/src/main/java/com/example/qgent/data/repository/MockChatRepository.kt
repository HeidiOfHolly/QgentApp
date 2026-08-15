package com.example.qgent.data.repository

import com.example.qgent.data.datasource.MockDataSource
import com.example.qgent.data.model.GroupDto
import com.example.qgent.data.model.GroupMemberDto
import com.example.qgent.data.model.GroupMessageDto
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
        idempotencyKey: String
    ): Result<GroupDto> = Result.failure(UnsupportedOperationException("mock 不支持创建群"))

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
        Result.success(listOf(GroupMemberDto(id = "m1", nickname = "张三", avatar = null), GroupMemberDto(id = "m2", nickname = "李四", avatar = null)))

    override suspend fun leaveGroup(projectId: String, groupId: String, idempotencyKey: String): Result<Unit> = Result.success(Unit)

    override suspend fun getMessages(
        projectId: String,
        groupId: String,
        cursor: String?,
        limit: Int
    ): Result<List<GroupMessageDto>> = Result.success(emptyList())

    override suspend fun sendMessage(
        projectId: String,
        groupId: String,
        type: String,
        content: MessageContentDto,
        clientMessageId: String?,
        idempotencyKey: String
    ): Result<GroupMessageDto> = Result.failure(UnsupportedOperationException("mock 不支持发送消息"))
}
