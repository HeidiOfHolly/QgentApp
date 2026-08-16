package com.example.qgent.data.datasource

import com.example.qgent.data.model.AgentDto
import com.example.qgent.data.model.GitHubInstallationDto
import com.example.qgent.data.model.GitHubRepositoryDto
import com.example.qgent.data.model.GroupDto
import com.example.qgent.data.model.GroupLatestMessageDto
import com.example.qgent.data.model.ProjectDto
import com.example.qgent.data.model.ProjectRepositoryDto
import com.example.qgent.data.model.TeamDto
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * 演示用 mock 数据源。后端不可用或无 token 时由 mock Repository 提供数据，
 * 保证 App 在离线/演示环境仍可完整走查。
 *
 * 数据全部以 DTO 形态存储；UI 模型映射由 ViewModel 完成。
 */
object MockDataSource {

    private const val MINUTE = 60_000L
    private const val HOUR = 60 * MINUTE
    private const val DAY = 24 * HOUR

    val teams: List<TeamDto> = listOf(
        TeamDto("1", "团队A", "TEAM_OWNER", 8, "2026-06-01"),
        TeamDto("2", "团队B", "TEAM_MEMBER", 12, "2026-06-10"),
        TeamDto("3", "团队C", "TEAM_MEMBER", 5, "2026-07-02"),
        TeamDto("4", "团队D", "TEAM_OWNER", 15, "2026-07-20")
    )

    private val projectsByTeamId = mapOf(
        "1" to listOf(ProjectDto("p1", "1", "Qgents Web", null, "ACTIVE"), ProjectDto("p2", "1", "Qgents Mobile", null, "ACTIVE")),
        "2" to listOf(ProjectDto("p3", "2", "认证服务", null, "ACTIVE"), ProjectDto("p4", "2", "网关", null, "ACTIVE")),
        "3" to listOf(ProjectDto("p5", "3", "数据平台", null, "ACTIVE")),
        "4" to listOf(ProjectDto("p6", "4", "运营后台", null, "ACTIVE"))
    )

    /** 团队 id → 项目 */
    fun projectsOf(teamId: String): List<ProjectDto> = projectsByTeamId[teamId] ?: emptyList()

    private val groupsByProjectId = mapOf(
        "p1" to listOf(
            // 项目总群（PROJECT_MAIN）：创建项目时自动生成，恒置顶
            GroupDto("main-1", "p1", "项目总群", null, "PROJECT_MAIN", "ACTIVE", 4, null, iso(20 * MINUTE), GroupLatestMessageDto("李四", "总群最新消息")),
            GroupDto("1", "p1", "登录功能", null, "REQUIREMENT", "ACTIVE", 3, null, iso(30 * MINUTE), GroupLatestMessageDto("李四", "好的没问题")),
            GroupDto("2", "p1", "认证安全", null, "REQUIREMENT", "ACTIVE", 2, null, iso(90 * MINUTE), GroupLatestMessageDto("我", "RSA 公钥我看一下"))
        ),
        "p2" to listOf(
            GroupDto("3", "p2", "任务编排", null, "REQUIREMENT", "ACTIVE", 5, null, iso(26 * HOUR), GroupLatestMessageDto("王五", "Planner 已完成")),
            GroupDto("4", "p2", "移动端 UI", null, "REQUIREMENT", "ACTIVE", 1, null, iso(30 * HOUR), GroupLatestMessageDto("赵六", "设置页改好了"))
        ),
        "p3" to listOf(
            GroupDto("5", "p3", "OAuth 对接", null, "REQUIREMENT", "ACTIVE", 2, null, iso(2 * DAY), GroupLatestMessageDto("张三", "回调地址已更新")),
            GroupDto("6", "p3", "Token 刷新", null, "REQUIREMENT", "ACTIVE", 0, null, iso(3 * DAY), GroupLatestMessageDto("我", "异常情况如何处理"))
        ),
        "p4" to listOf(
            GroupDto("7", "p4", "限流策略", null, "REQUIREMENT", "ACTIVE", 1, null, iso(4 * DAY), GroupLatestMessageDto("李四", "阈值需要讨论"))
        ),
        "p5" to listOf(
            GroupDto("8", "p5", "数据接入", null, "REQUIREMENT", "ACTIVE", 0, null, iso(5 * DAY), GroupLatestMessageDto("王五", "Schema 已对齐")),
            GroupDto("9", "p5", "报表需求", null, "REQUIREMENT", "ACTIVE", 4, null, iso(5 * DAY + 12 * HOUR), GroupLatestMessageDto("赵六", "新增 3 个维度"))
        ),
        "p6" to listOf(
            GroupDto("10", "p6", "权限管理", null, "REQUIREMENT", "ACTIVE", 0, null, iso(6 * DAY), GroupLatestMessageDto("张三", "角色树已更新"))
        )
    )

    /** 项目名 → 项目 id（用于按项目名反查 mock 群） */
    private val projectNameById: Map<String, String> =
        projectsByTeamId.values.flatten().associate { it.name to it.id }

    /** 项目 id 或项目名 → mock 群聊 */
    fun groupsOf(projectIdOrName: String): List<GroupDto> =
        groupsByProjectId[projectIdOrName]
            ?: projectNameById[projectIdOrName]?.let { groupsByProjectId[it] }
            ?: emptyList()

    // ── Agent（系统内置“新手大礼包”） ──

    val mockAgents: List<AgentDto> = listOf(
        AgentDto("1", "AgentOrchestrator", null, "ORCHESTRATOR", listOf("任务调度", "工作流编排", "质量门禁"), null, "PRIVATE", "ACTIVE", "system"),
        AgentDto("2", "Planner", null, "PLANNER", listOf("需求分析", "任务拆分", "计划编排"), null, "PRIVATE", "ACTIVE", "system"),
        AgentDto("3", "Developer", null, "DEVELOPER", listOf("java", "spring-boot", "api", "react"), null, "PRIVATE", "ACTIVE", "system"),
        AgentDto("4", "Tester", null, "TESTER", listOf("单元测试", "集成测试", "回归测试"), null, "PRIVATE", "ACTIVE", "system"),
        AgentDto("5", "Reviewer", null, "REVIEWER", listOf("代码审查", "规范检查", "安全扫描"), null, "PRIVATE", "ACTIVE", "system")
    )

    // ── GitHub 集成（Installation / 授权仓库 / 项目绑定） ──

    val githubInstallations: List<GitHubInstallationDto> = listOf(
        GitHubInstallationDto(
            id = "installation-1",
            providerInstallationId = 12345678,
            accountLogin = "Yjingwen-svg",
            accountType = "ORGANIZATION",
            status = "ACTIVE",
            installedAt = "2026-08-01T08:00:00Z",
            metadataSyncedAt = "2026-08-13T10:00:00Z"
        )
    )

    val githubRepositories: List<GitHubRepositoryDto> = listOf(
        GitHubRepositoryDto(
            id = "repository-1",
            installationId = "installation-1",
            providerRepositoryId = 987654321,
            fullName = "Yjingwen-svg/qgents-web",
            githubUrl = "https://github.com/Yjingwen-svg/qgents-web",
            defaultBranch = "main",
            visibility = "PRIVATE",
            archived = false,
            authorizationStatus = "AUTHORIZED",
            metadataSyncedAt = "2026-08-13T10:00:00Z"
        ),
        GitHubRepositoryDto(
            id = "repository-2",
            installationId = "installation-1",
            providerRepositoryId = 987654322,
            fullName = "Yjingwen-svg/qgents-mobile",
            githubUrl = "https://github.com/Yjingwen-svg/qgents-mobile",
            defaultBranch = "main",
            visibility = "PRIVATE",
            archived = false,
            authorizationStatus = "AUTHORIZED",
            metadataSyncedAt = "2026-08-13T10:00:00Z"
        )
    )

    val projectRepositories: List<ProjectRepositoryDto> = listOf(
        ProjectRepositoryDto(
            id = "project-binding-1",
            repositoryId = "repository-1",
            installationId = "installation-1",
            providerRepositoryId = 987654321,
            fullName = "Yjingwen-svg/qgents-web",
            githubUrl = "https://github.com/Yjingwen-svg/qgents-web",
            defaultBranch = "main",
            displayName = "qgents-web",
            authorizationStatus = "AUTHORIZED",
            metadataSyncedAt = "2026-08-13T10:00:00Z",
            boundAt = "2026-08-13T10:00:00Z"
        )
    )

    /** 生成「当前时间 - offset」的 UTC RFC3339 时间戳 */
    private fun iso(offsetMillis: Long): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("UTC") }
            .format(Date(System.currentTimeMillis() - offsetMillis))
}
