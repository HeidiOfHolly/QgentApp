package com.example.qgent.data.repository

import kotlinx.coroutines.CancellationException

/** 统一 API 调用入口：捕获异常转 Result，收敛各 Impl 里重复的 runCatching */
internal suspend fun <T> apiCall(block: suspend () -> T): Result<T> =
    try {
        Result.success(block())
    } catch (e: CancellationException) {
        // 协程取消必须向上抛出：runCatching 吞掉会破坏结构化并发，
        // 导致取消旧团队加载时误触 mock 回退并提前结束加载标记
        throw e
    } catch (e: Throwable) {
        Result.failure(e)
    }