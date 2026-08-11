package com.example.qgent.data.api

import com.example.qgent.data.model.ApiResponse
import com.example.qgent.data.model.CreateGroupRequest
import com.example.qgent.data.model.GroupDto
import com.example.qgent.data.model.GroupMemberDto
import com.example.qgent.data.model.AuthSessionDto
import com.example.qgent.data.model.GroupMessageDto
import com.example.qgent.data.model.LoginRequest
import com.example.qgent.data.model.PasswordPublicKeyDto
import com.example.qgent.data.model.ProjectDto
import com.example.qgent.data.model.RegisterRequest
import com.example.qgent.data.model.RequirementGroupDto
import com.example.qgent.data.model.SendMessageRequest
import com.example.qgent.data.model.TeamDto
import com.example.qgent.data.model.UpdateGroupRequest
import com.example.qgent.data.model.UserProfileDto
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * QG 后端 API 接口定义。
 * 接口文档 v1.1.2 —— 仅覆盖已实现页面所需接口。
 * 未实现页面/缺失接口见 memory 或与后端对接后补充。
 * QG 后端 API 接口定义（基于更新后的 v1 接口文档）。
 * 群（Group）统一建模为 PROJECT_MAIN + REQUIREMENT，路径挂在 /projects/{id}/groups 下。
 */
interface QgApiService {

    // ── 认证与账户（§4）──

    @GET("auth/password-public-key")
    suspend fun getPasswordPublicKey(): ApiResponse<PasswordPublicKeyDto>

    @POST("auth/register")
    suspend fun register(@Body body: RegisterRequest): ApiResponse<AuthSessionDto>

    @POST("auth/login")
    suspend fun login(@Body body: LoginRequest): ApiResponse<AuthSessionDto>

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

    // ── 群（Group） ──

    @GET("projects/{projectId}/groups")
    suspend fun getGroups(
        @Path("projectId") projectId: String,
        @Query("cursor") cursor: String? = null,
        @Query("limit") limit: Int = 30
    ): ApiResponse<List<GroupDto>>

    @POST("projects/{projectId}/groups")
    suspend fun createGroup(
        @Path("projectId") projectId: String,
        @Body body: CreateGroupRequest
    ): ApiResponse<GroupDto>

    @GET("projects/{projectId}/groups/{groupId}")
    suspend fun getGroup(
        @Path("projectId") projectId: String,
        @Path("groupId") groupId: String
    ): ApiResponse<GroupDto>

    @PATCH("projects/{projectId}/groups/{groupId}")
    suspend fun updateGroup(
        @Path("projectId") projectId: String,
        @Path("groupId") groupId: String,
        @Body body: UpdateGroupRequest
    ): ApiResponse<GroupDto>

    @POST("projects/{projectId}/groups/{groupId}/archive")
    suspend fun archiveGroup(
        @Path("projectId") projectId: String,
        @Path("groupId") groupId: String
    ): ApiResponse<GroupDto>

    // ── 群成员 ──

    @GET("projects/{projectId}/groups/{groupId}/members")
    suspend fun getGroupMembers(
        @Path("projectId") projectId: String,
        @Path("groupId") groupId: String
    ): ApiResponse<List<GroupMemberDto>>

    @POST("projects/{projectId}/groups/{groupId}/leave")
    suspend fun leaveGroup(
        @Path("projectId") projectId: String,
        @Path("groupId") groupId: String
    ): ApiResponse<Unit>

    // ── 群聊消息 ──

    @GET("projects/{projectId}/groups/{groupId}/messages")
    suspend fun getMessages(
        @Path("projectId") projectId: String,
        @Path("groupId") groupId: String,
        @Query("cursor") cursor: String? = null,
        @Query("limit") limit: Int = 30
    ): ApiResponse<List<GroupMessageDto>>

    @POST("projects/{projectId}/groups/{groupId}/messages")
    suspend fun sendMessage(
        @Path("projectId") projectId: String,
        @Path("groupId") groupId: String,
        @Body body: SendMessageRequest
    ): ApiResponse<GroupMessageDto>
}
