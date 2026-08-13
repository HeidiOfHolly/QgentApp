package com.example.qgent.data.repository

/** 真实请求失败时回退到 mock 结果的组合助手（演示环境行为） */
internal suspend fun <T> Result<T>.orFallback(mock: suspend () -> Result<T>): Result<T> =
    if (isSuccess) this else mock()
