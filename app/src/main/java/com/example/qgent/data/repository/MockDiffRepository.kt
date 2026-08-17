package com.example.qgent.data.repository

import com.example.qgent.data.model.DiffFileDto
import com.example.qgent.data.model.DiffHunkDto
import com.example.qgent.data.model.DiffHunkLineDto
import com.example.qgent.data.model.DiffReviewBatchDto
import com.google.gson.JsonPrimitive

/**
 * mock Diff 仓库（保底用，真实接口测试完成后删除）。
 */
class MockDiffRepository : DiffRepository {

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
            DiffReviewBatchDto(
                id = "mock-batch-1",
                taskId = taskId,
                reviewStatus = "PENDING_CONFIRMATION",
                deliveryStatus = null,
                repositoryCount = 1,
                filesChanged = 1,
                additions = 3,
                deletions = 1,
                diffs = emptyList()
            )
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
