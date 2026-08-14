package com.example.qgent.data.repository

/** 真实请求失败时回退到 mock 结果的组合助手（演示环境行为） */
internal suspend fun <T> Result<T>.orFallback(mock: suspend () -> Result<T>): Result<T> =
    if (isSuccess) this else mock()

/** 统一 API 调用入口：捕获异常转 Result，收敛各 Impl 里重复的 runCatching */
internal suspend fun <T> apiCall(block: suspend () -> T): Result<T> = runCatching { block() }

/** 真实请求失败时回退到 mock 的通用委托，收敛各 FallbackRepository 的机械重复 */
internal class FallbackDelegate<T : Any>(private val real: T, private val mock: T) {
    suspend fun <R> call(block: suspend T.() -> Result<R>): Result<R> =
        real.block().orFallback { mock.block() }
}
