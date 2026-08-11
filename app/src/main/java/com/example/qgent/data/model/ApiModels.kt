package com.example.qgent.data.model

import com.google.gson.annotations.SerializedName

/** API 统一成功响应：{ data, requestId, page } */
data class ApiResponse<T>(
    val data: T?,
    val requestId: String?,
    val error: ApiError?,
    val page: PageInfo?
)

/** API 错误：{ code, message, details } */
data class ApiError(
    val code: String,
    val message: String,
    val details: List<String>?
)

/** 游标分页信息 */
data class PageInfo(
    val nextCursor: String?,
    val hasMore: Boolean
)

// ── 业务 DTO ──

data class UserProfileDto(
    val id: String,
    val nickname: String,
    val email: String,
    val avatar: String?,
    @SerializedName("githubLinked") val githubLinked: Boolean
)

data class TeamDto(
    val id: String,
    val name: String,
    val role: String,           // TEAM_OWNER / TEAM_MEMBER
    @SerializedName("memberCount") val memberCount: Int,
    @SerializedName("createdAt") val createdAt: String
)

data class ProjectDto(
    val id: String,
    @SerializedName("teamId") val teamId: String,
    val name: String,
    val description: String?,
    val status: String
)

data class RequirementGroupDto(
    val id: String,
    @SerializedName("projectId") val projectId: String,
    val name: String,
    @SerializedName("memberCount") val memberCount: Int,
    @SerializedName("lastMessage") val lastMessage: String?,
    @SerializedName("lastMessageSender") val lastMessageSender: String?,
    @SerializedName("updatedAt") val updatedAt: String
)

data class GroupMessageDto(
    val id: String,
    @SerializedName("groupId") val groupId: String,
    @SerializedName("senderId") val senderId: String,
    @SerializedName("senderName") val senderName: String,
    val content: String,
    val type: String,           // TEXT / IMAGE / FILE
    @SerializedName("createdAt") val createdAt: String
)
