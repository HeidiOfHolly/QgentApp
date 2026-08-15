package com.example.qgent.data.repository

import kotlinx.coroutines.CancellationException

/** 真实请求失败时回退到 mock 结果的组合助手（演示环境行为） */
internal suspend fun <T> Result<T>.orFallback(mock: suspend () -> Result<T>): Result<T> =
    if (isSuccess) this else mock()

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

/** 真实请求失败时回退到 mock 的通用委托，收敛各 FallbackRepository 的机械重复 */
internal class FallbackDelegate<T : Any>(private val real: T, private val mock: T) {
    suspend fun <R> call(block: suspend T.() -> Result<R>): Result<R> =
        real.block().orFallback { mock.block() }
}
