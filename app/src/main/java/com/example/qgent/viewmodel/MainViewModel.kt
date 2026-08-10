package com.example.qgent.viewmodel

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel

/**
 * Activity 级共享状态：当前团队、当前项目。
 * 个人中心抽屉（切团队/切项目）与群聊列表页（显示所在团队）共用。
 * 数据为 mock，后续接入接口。
 */
class MainViewModel : ViewModel() {

    // mock：团队 -> 项目列表
    private val projectsByTeam = mapOf(
        "团队A" to listOf("Qgents Web", "Qgents Mobile"),
        "团队B" to listOf("认证服务", "网关"),
        "团队C" to listOf("数据平台"),
        "团队D" to listOf("运营后台")
    )

    private val _currentTeam = MutableLiveData("团队A")
    val currentTeam: LiveData<String> = _currentTeam

    private val _currentProject = MutableLiveData<String>()
    val currentProject: LiveData<String> = _currentProject

    init {
        _currentProject.value = projectsByTeam.getValue("团队A").first()
    }

    fun setCurrentTeam(team: String) {
        if (_currentTeam.value == team) return
        _currentTeam.value = team
        // 切换团队时，项目切到该团队第一个项目
        _currentProject.value = projectsByTeam[team]?.firstOrNull() ?: ""
    }

    fun setCurrentProject(project: String) {
        if (_currentProject.value != project) {
            _currentProject.value = project
        }
    }

    fun projectsOf(team: String): List<String> = projectsByTeam[team] ?: emptyList()
}
