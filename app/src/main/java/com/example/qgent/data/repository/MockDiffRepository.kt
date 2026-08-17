package com.example.qgent.data.repository

import com.example.qgent.data.model.DiffFileDto
import com.example.qgent.data.model.DiffHunkDto
import com.example.qgent.data.model.DiffHunkLineDto
import com.example.qgent.data.model.DiffReviewBatchDto
import com.example.qgent.data.model.RepositoryDeliveryDto
import com.example.qgent.data.model.RepositoryDeliveryMergeRequestDto
import com.google.gson.JsonPrimitive

/**
 * mock Diff 仓库（保底用，真实接口测试完成后删除）。
 *
 * MR_FIRST B 方案场景（§v1.10.0）：getTaskDiffReview 按 taskId 后缀返回对应批次——
 * - `-mr-auto`：ACCEPTED + SYSTEM + DELIVERING（自动交付进行中）
 * - `-mr-partial`：PARTIALLY_DELIVERED（一个仓库 MR_CREATED、一个 FAILED）
 * - `-mr-failed`：FAILED（含 failureCode / failureReason，重试成功后返回 DELIVERING）
 * - 其他：PENDING_CONFIRMATION + USER（DIFF_FIRST 常规场景）
 */
class MockDiffRepository : DiffRepository {

    /** MR_FIRST + ACCEPTED + SYSTEM + DELIVERING */
    fun mrFirstAutoDeliveringBatch(taskId: String) = DiffReviewBatchDto(
        id = "mock-batch-mr-auto",
        taskId = taskId,
        reviewStatus = "ACCEPTED",
        deliveryStatus = "DELIVERING",
        confirmationSource = "SYSTEM",
        repositoryCount = 2,
        filesChanged = 3,
        additions = 10,
        deletions = 4,
        diffs = emptyList(),
        repositoryDeliveries = listOf(
            RepositoryDeliveryDto(
                repositoryId = "repo-1",
                repositoryName = "qgents-web",
                deliveryStatus = "COMMITTED",
                updatedAt = "2026-08-19T10:00:00Z"
            ),
            RepositoryDeliveryDto(
                repositoryId = "repo-2",
                repositoryName = "qgents-mobile",
                deliveryStatus = "NOT_STARTED"
            )
        )
    )

    /** MR_FIRST + PARTIALLY_DELIVERED：一个仓库 MR_CREATED、一个仓库 FAILED */
    fun mrFirstPartialBatch(taskId: String) = DiffReviewBatchDto(
        id = "mock-batch-mr-partial",
        taskId = taskId,
        reviewStatus = "ACCEPTED",
        deliveryStatus = "PARTIALLY_DELIVERED",
        confirmationSource = "SYSTEM",
        repositoryCount = 2,
        filesChanged = 3,
        additions = 10,
        deletions = 4,
        diffs = emptyList(),
        repositoryDeliveries = listOf(
            RepositoryDeliveryDto(
                repositoryId = "repo-1",
                repositoryName = "qgents-web",
                deliveryStatus = "MR_CREATED",
                mergeRequest = RepositoryDeliveryMergeRequestDto(
                    webUrl = "https://github.com/Yjingwen-svg/qgents-web/pull/42",
                    number = 42,
                    title = "feat: 邮箱登录"
                ),
                updatedAt = "2026-08-19T10:05:00Z"
            ),
            RepositoryDeliveryDto(
                repositoryId = "repo-2",
                repositoryName = "qgents-mobile",
                deliveryStatus = "FAILED",
                failureCode = "GIT_PUSH_REJECTED",
                failureReason = "推送被拒绝：目标分支保护规则",
                updatedAt = "2026-08-19T10:06:00Z"
            )
        )
    )

    /** MR_FIRST + FAILED（重试成功后返回 DELIVERING） */
    fun mrFirstFailedBatch(taskId: String) = DiffReviewBatchDto(
        id = "mock-batch-mr-failed",
        taskId = taskId,
        reviewStatus = "ACCEPTED",
        deliveryStatus = "FAILED",
        confirmationSource = "SYSTEM",
        repositoryCount = 1,
        filesChanged = 1,
        additions = 3,
        deletions = 1,
        diffs = emptyList(),
        repositoryDeliveries = listOf(
            RepositoryDeliveryDto(
                repositoryId = "repo-1",
                repositoryName = "qgents-web",
                deliveryStatus = "FAILED",
                failureCode = "GIT_PUSH_REJECTED",
                failureReason = "推送被拒绝：token 无效",
                updatedAt = "2026-08-19T10:10:00Z"
            )
        )
    )

    /** DIFF_FIRST 常规：PENDING_CONFIRMATION + USER */
    fun pendingConfirmationBatch(taskId: String) = DiffReviewBatchDto(
        id = "mock-batch-1",
        taskId = taskId,
        reviewStatus = "PENDING_CONFIRMATION",
        confirmationSource = "USER",
        deliveryStatus = null,
        repositoryCount = 1,
        filesChanged = 1,
        additions = 3,
        deletions = 1,
        diffs = emptyList()
    )

    override suspend fun getDiffFiles(projectId: String, diffId: String, cursor: String?, limit: Int): Result<List<DiffFileDto>> =
        Result.success(
            listOf(
                DiffFileDto(
                    id = "diff-file-1",
                    sequence = 1,
                    path = "app/src/main/java/com/example/qgent/MainActivity.kt",
                    changeType = "MODIFY",
                    additions = 3,
                    deletions = 1,
                    binary = false,
                    hunks = listOf(
                        DiffHunkDto(
                            newStart = 10, oldStart = 10,
                            lines = listOf(
                                DiffHunkLineDto("CONTEXT", 10, 10, "        super.onCreate(savedInstanceState)"),
                                DiffHunkLineDto("DELETE", 11, null, "        setContentView(R.layout.activity_main)"),
                                DiffHunkLineDto("ADD", null, 11, "        enableEdgeToEdge()"),
                                DiffHunkLineDto("ADD", null, 12, "        binding = ActivityMainBinding.inflate(layoutInflater)"),
                                DiffHunkLineDto("CONTEXT", 12, 13, "        setContentView(binding.root)")
                            )
                        )
                    )
                )
            )
        )

    override suspend fun acceptDiff(projectId: String, diffId: String, reason: String?, idempotencyKey: String): Result<Unit> =
        Result.success(Unit)

    override suspend fun rejectDiff(projectId: String, diffId: String, reason: String?, idempotencyKey: String): Result<Unit> =
        Result.success(Unit)

    override suspend fun getTaskDiffReview(projectId: String, taskId: String): Result<DiffReviewBatchDto?> =
        Result.success(
            when {
                taskId.endsWith("-mr-auto") -> mrFirstAutoDeliveringBatch(taskId)
                taskId.endsWith("-mr-partial") -> mrFirstPartialBatch(taskId)
                taskId.endsWith("-mr-failed") -> mrFirstFailedBatch(taskId)
                else -> pendingConfirmationBatch(taskId)
            }
        )

    override suspend fun getDiffReviewPatch(projectId: String, taskId: String, diffId: String): Result<com.google.gson.JsonElement> =
        Result.success(JsonPrimitive("mock patch"))

    override suspend fun confirmDiffReview(projectId: String, taskId: String, idempotencyKey: String): Result<Unit> =
        Result.success(Unit)

    override suspend fun rejectDiffReview(projectId: String, taskId: String, reason: String?, idempotencyKey: String): Result<Unit> =
        Result.success(Unit)

    override suspend fun retryDiffDelivery(projectId: String, taskId: String, idempotencyKey: String): Result<Unit> =
        Result.success(Unit)
}
