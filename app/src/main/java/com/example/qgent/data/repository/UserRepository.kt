package com.example.qgent.data.repository

import com.example.qgent.data.api.RetrofitClient
import com.example.qgent.data.model.ProjectDto
import com.example.qgent.data.model.TeamDto
import com.example.qgent.data.model.UserProfileDto

class UserRepository {

    private val service = RetrofitClient.service

    suspend fun getUserProfile(): Result<UserProfileDto> = runCatching {
        service.getUserProfile().data!!
    }

    suspend fun getTeams(): Result<List<TeamDto>> = runCatching {
        service.getTeams().data!!
    }

    suspend fun getProjects(teamId: String): Result<List<ProjectDto>> = runCatching {
        service.getProjects(teamId).data!!
    }
}
