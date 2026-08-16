package com.example.qgent.data.repository

import com.example.qgent.data.model.ActivityDto
import com.example.qgent.data.model.DiffFileResponseDto
import com.example.qgent.data.model.MergeRequestDetailDto
import com.example.qgent.data.model.MergeRequestDto
import com.example.qgent.data.model.TaskDetailDto
import com.example.qgent.data.model.TaskListItemDto
import com.example.qgent.data.model.TaskRunDetailListItemDto
import com.example.qgent.data.model.TaskRunListItemDto
import com.example.qgent.data.model.TaskStepListItemDto

/** 任务/动态/MR 仓库：任务页三列表查询（§16 任务 / §19.4 动态 / §13 MR） */
interface TaskRepository {
    /** 查询项目可见任务（§16.1），支持需求群/状态/发起人/仓库过滤，分页返回单页数据 */
    suspend fun getTasks(
        projectId: String,
        groupId: String? = null,
        status: String? = null,
        createdBy: String? = null,
        repositoryId: String? = null,
        cursor: String? = null,
        limit: Int = 20
    ): Result<List<TaskListItemDto>>

    /** 团队最近动态（§19.4），按 createdAt 倒序 */
    suspend fun getActivities(
        teamId: String,
        cursor: String? = null,
        limit: Int = 20
    ): Result<List<ActivityDto>>

    /** 查询项目关联 MR（§13） */
    suspend fun getMergeRequests(
        projectId: String,
        cursor: String? = null,
        limit: Int = 20
    ): Result<List<MergeRequestDto>>

    /** 项目级按 Agent 查询 TaskRun（§20.6） */
    suspend fun getTaskRuns(
        projectId: String,
        agentId: String,
        cursor: String? = null,
        limit: Int = 20
    ): Result<List<TaskRunListItemDto>>

    /** 任务详情（§16.2） */
    suspend fun getTaskDetail(projectId: String, taskId: String): Result<TaskDetailDto>

    /** 任务步骤列表（§16.3） */
    suspend fun getTaskSteps(projectId: String, taskId: String): Result<List<TaskStepListItemDto>>

    /** 任务运行列表（§16.4，每次执行的 TaskRun） */
    suspend fun getTaskRunsOfTask(projectId: String, taskId: String): Result<List<TaskRunDetailListItemDto>>

    /** MR 详情（§13，含 diffId） */
    suspend fun getMergeRequestDetail(projectId: String, mergeRequestId: String): Result<MergeRequestDetailDto>

    /** 读取 Diff 文件与代码行（§12.3） */
    suspend fun getDiffFiles(projectId: String, diffId: String): Result<List<DiffFileResponseDto>>

    /** 取消任务（§11.3，POST cancel，202 异步受理；终态返回 409 TASK_NOT_CANCELLABLE） */
    suspend fun cancelTask(projectId: String, taskId: String, idempotencyKey: String): Result<Unit>

    /** 替换步骤执行 Agent（§11.3，仅 PENDING 步骤可替换） */
    suspend fun replaceAgent(
        projectId: String,
        taskId: String,
        stepId: String,
        agentId: String,
        idempotencyKey: String
    ): Result<TaskStepListItemDto>
}
