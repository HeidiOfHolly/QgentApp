package com.example.qgent.model

/** TASK_STATUS 卡 plan.steps 单步快照（v23 契约；stepId 为数据库 TaskStepEntity.id） */
data class TaskStepSnapshot(
    val stepId: String? = null,
    val sequence: Int? = null,
    val title: String? = null,
    val role: String? = null,
    val status: String? = null,
    val message: String? = null
)

/** TASK_STATUS 卡片仓库映射项（§39）：workspacePath 为 Workspace 内一级目录，currentRepositoryPaths 按它匹配；
 *  展示名优先 fullName → name → repositoryId。 */
data class TaskRepositoryMapping(
    val workspacePath: String? = null,
    val repositoryId: String? = null,
    val name: String? = null,
    val fullName: String? = null,
    val provider: String? = null,
    val baseRef: String? = null,
    val sourceBranch: String? = null
) {
    val displayName: String
        get() = fullName?.takeIf { it.isNotBlank() }
            ?: name?.takeIf { it.isNotBlank() }
            ?: repositoryId?.takeIf { it.isNotBlank() }
            ?: "仓库"
}
