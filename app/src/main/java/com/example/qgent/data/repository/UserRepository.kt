package com.example.qgent.data.repository

import com.example.qgent.data.model.ProjectDto
import com.example.qgent.data.model.TeamDto
import com.example.qgent.data.model.UserProfileDto

interface UserRepository {
    suspend fun getUserProfile(): Result<UserProfileDto>
    suspend fun getTeams(): Result<List<TeamDto>>
    suspend fun getProjects(teamId: String): Result<List<ProjectDto>>
}
