package com.example.qgent.data.api

import com.example.qgent.data.model.ApiResponse
import com.example.qgent.data.model.GroupMessageDto
import com.example.qgent.data.model.ProjectDto
import com.example.qgent.data.model.RequirementGroupDto
import com.example.qgent.data.model.TeamDto
import com.example.qgent.data.model.UserProfileDto
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * QG 后端 API 接口定义。
 * 接口文档 v1.0.1 —— 仅覆盖已实现页面所需接口。
 * 未实现页面/缺失接口见 memory 或与后端对接后补充。
 */
interface QgApiService {

    // ── 用户 ──

    @GET("me")
    suspend fun getUserProfile(): ApiResponse<UserProfileDto>

    // ── 团队 ──

    @GET("teams")
    suspend fun getTeams(): ApiResponse<List<TeamDto>>

    // ── 项目 ──

    @GET("teams/{teamId}/projects")
    suspend fun getProjects(
        @Path("teamId") teamId: String
    ): ApiResponse<List<ProjectDto>>

    // ── 需求群（群聊） ──

    @GET("projects/{projectId}/requirement-groups")
    suspend fun getRequirementGroups(
        @Path("projectId") projectId: String,
        @Query("cursor") cursor: String? = null,
        @Query("limit") limit: Int = 30
    ): ApiResponse<List<RequirementGroupDto>>

    data class CreateGroupBody(val name: String)

    @POST("projects/{projectId}/requirement-groups")
    suspend fun createRequirementGroup(
        @Path("projectId") projectId: String,
        @Body body: CreateGroupBody
    ): ApiResponse<RequirementGroupDto>

    // ── 单个需求群 ──

    @GET("requirement-groups/{groupId}")
    suspend fun getRequirementGroup(
        @Path("groupId") groupId: String
    ): ApiResponse<RequirementGroupDto>

    data class UpdateGroupBody(val name: String)

    @PATCH("requirement-groups/{groupId}")
    suspend fun updateRequirementGroup(
        @Path("groupId") groupId: String,
        @Body body: UpdateGroupBody
    ): ApiResponse<RequirementGroupDto>

    // ── 群聊消息 ──

    @GET("requirement-groups/{groupId}/messages")
    suspend fun getMessages(
        @Path("groupId") groupId: String,
        @Query("cursor") cursor: String? = null,
        @Query("limit") limit: Int = 30
    ): ApiResponse<List<GroupMessageDto>>

    data class SendMessageBody(val content: String, val type: String = "TEXT")

    @POST("requirement-groups/{groupId}/messages")
    suspend fun sendMessage(
        @Path("groupId") groupId: String,
        @Body body: SendMessageBody
    ): ApiResponse<GroupMessageDto>
}
