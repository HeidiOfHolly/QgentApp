package com.example.qgent.viewmodel

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import com.example.qgent.model.ChatGroup

/**
 * Activity 级共享状态：当前团队、当前项目、群聊列表。
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

    // mock：项目 -> 群聊列表（群聊随项目切换而变化）
    private val groupsByProject = mapOf(
        "Qgents Web" to listOf(
            ChatGroup("1", "登录功能", "李四：好的没问题", "14:05", 3),
            ChatGroup("2", "认证安全", "我：RSA 公钥我看一下", "13:40", 0)
        ),
        "Qgents Mobile" to listOf(
            ChatGroup("3", "任务编排", "王五：Planner 已完成", "昨天", 5),
            ChatGroup("4", "移动端 UI", "赵六：设置页改好了", "昨天", 0)
        ),
        "认证服务" to listOf(
            ChatGroup("5", "OAuth 对接", "张三：回调地址已更新", "周一", 2),
            ChatGroup("6", "Token 刷新", "我：异常情况如何处理", "周一", 0)
        ),
        "网关" to listOf(
            ChatGroup("7", "限流策略", "李四：阈值需要讨论", "周二", 1)
        ),
        "数据平台" to listOf(
            ChatGroup("8", "数据接入", "王五：Schema 已对齐", "周三", 0),
            ChatGroup("9", "报表需求", "赵六：新增 3 个维度", "周三", 4)
        ),
        "运营后台" to listOf(
            ChatGroup("10", "权限管理", "张三：角色树已更新", "周四", 0)
        )
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
        _currentProject.value = projectsByTeam[team]?.firstOrNull() ?: ""
    }

    fun setCurrentProject(project: String) {
        if (_currentProject.value != project) {
            _currentProject.value = project
        }
    }

    fun projectsOf(team: String): List<String> = projectsByTeam[team] ?: emptyList()

    fun groupsOf(project: String): List<ChatGroup> = groupsByProject[project] ?: emptyList()
}
