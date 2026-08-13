package com.example.qgent.model

enum class DiffLineType { ADD, DELETE, CONTEXT }

data class DiffLine(
    val type: DiffLineType,
    val oldLineNo: Int?,
    val newLineNo: Int?,
    val text: String
)

data class DiffFile(
    val fileName: String,
    val additions: Int,
    val deletions: Int,
    val lines: List<DiffLine>
)
