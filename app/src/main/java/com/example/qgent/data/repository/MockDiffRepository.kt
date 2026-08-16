package com.example.qgent.data.repository

import com.example.qgent.data.model.DiffFileDto
import com.example.qgent.data.model.DiffHunkDto
import com.example.qgent.data.model.DiffHunkLineDto

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
}
