package com.example.qgent.data.repository

import com.example.qgent.data.api.QgApiService
import com.example.qgent.data.api.RetrofitClient
import com.example.qgent.data.model.GroupMessageDto
import com.example.qgent.data.model.RequirementGroupDto

class ChatRepository {

    private val service = RetrofitClient.service

    suspend fun getGroups(
        projectId: String,
        cursor: String? = null,
        limit: Int = 30
    ): Result<List<RequirementGroupDto>> = runCatching {
        service.getRequirementGroups(projectId, cursor, limit).data!!
    }

    suspend fun createGroup(
        projectId: String,
        name: String
    ): Result<RequirementGroupDto> = runCatching {
        service.createRequirementGroup(
            projectId,
            QgApiService.CreateGroupBody(name)
        ).data!!
    }

    suspend fun getGroup(groupId: String): Result<RequirementGroupDto> = runCatching {
        service.getRequirementGroup(groupId).data!!
    }

    suspend fun updateGroupName(groupId: String, name: String): Result<RequirementGroupDto> = runCatching {
        service.updateRequirementGroup(groupId, QgApiService.UpdateGroupBody(name)).data!!
    }

    suspend fun getMessages(
        groupId: String,
        cursor: String? = null,
        limit: Int = 30
    ): Result<List<GroupMessageDto>> = runCatching {
        service.getMessages(groupId, cursor, limit).data!!
    }

    suspend fun sendMessage(
        groupId: String,
        content: String,
        type: String = "TEXT"
    ): Result<GroupMessageDto> = runCatching {
        service.sendMessage(groupId, QgApiService.SendMessageBody(content, type)).data!!
    }
}
