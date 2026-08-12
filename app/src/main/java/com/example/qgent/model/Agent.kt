package com.example.qgent.model

enum class AgentStatus { IDLE, RUNNING, ERROR }

data class Agent(
    val id: String,
    val name: String,
    val description: String,
    val status: AgentStatus = AgentStatus.IDLE
)
