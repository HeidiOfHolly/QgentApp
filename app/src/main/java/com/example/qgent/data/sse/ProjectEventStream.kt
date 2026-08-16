package com.example.qgent.data.sse

import android.util.Log
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * 项目级 SSE 事件流客户端（文档 §12.1）。
 *
 * 建立 `GET /projects/{projectId}/events` 长连接（Content-Type: text/event-stream），
 * 复用 [httpClient]（已带 AuthInterceptor + TokenAuthenticator，鉴权与自动刷新由调用方注入的 client 承担）。
 *
 * 契约要点（文档 §12.1）：
 * - 事件 `id` 即项目内单调递增 `sequenceNo`，通过 `Last-Event-ID` 请求头断线续传；
 * - 服务端每 15 秒发送心跳（注释行或空行），客户端以 readTimeout 兜底检测死连接；
 * - 续传点过期返回 `409 EVENT_CURSOR_EXPIRED` → 清空游标、丢弃游标重连；
 * - 事件仅用于刷新界面：上层收到事件后必须重新拉取对应查询接口，不把 payload 当完整 DTO。
 *
 * 线程模型：连接循环运行在内部 [CoroutineScope]（SupervisorJob + IO），
 * 通过 [events] SharedFlow 向订阅者（Fragment 的 lifecycleScope）分发事件。
 * 使用方必须在不再需要时调用 [stop]（Fragment onPause / onDestroyView）。
 */
class ProjectEventStream(
    httpClient: OkHttpClient,
    private val baseUrl: String
) {

    private val sseClient: OkHttpClient = httpClient.newBuilder()
        // 服务端 15s 心跳：90s 内无任何字节视为死连接，readUtf8Line 抛 SocketTimeoutException 触发重连
        .readTimeout(90, TimeUnit.SECONDS)
        .build()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var connectJob: Job? = null

    /** 最后收到的事件 id（sequenceNo），作为 Last-Event-ID 续传游标；409 时清空 */
    @Volatile
    private var lastEventId: String? = null

    private val _events = MutableSharedFlow<SseEvent>(extraBufferCapacity = 32)
    val events: SharedFlow<SseEvent> = _events

    /** 当前连接的 projectId；换项目时自动断开旧连接 */
    @Volatile
    private var currentProjectId: String? = null

    /**
     * 建立（或保持）项目事件流连接。幂等：同 projectId 重复调用不重启。
     * 不同 projectId 调用会先断开旧连接再连新的。
     */
    fun start(projectId: String) {
        if (currentProjectId == projectId && connectJob?.isActive == true) return
        stop()
        currentProjectId = projectId
        lastEventId = null // 切项目时从最新开始（项目内游标不可跨项目复用）
        connectJob = scope.launch { connectLoop(projectId) }
    }

    /** 断开连接并停止重连。重新 [start] 可从最新事件开始（游标同时清空）。 */
    fun stop() {
        currentProjectId = null
        connectJob?.cancel()
        connectJob = null
    }

    private suspend fun connectLoop(projectId: String) {
        var backoffMs = INITIAL_BACKOFF_MS
        while (scope.isActive && currentProjectId == projectId) {
            val outcome = try {
                connectAndRead(projectId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "sse error: ${e::class.simpleName}: ${e.message}")
                Outcome.ERROR
            }
            // 连接被 stop() 取消或换项目后退出
            if (currentProjectId != projectId) return

            when (outcome) {
                Outcome.UNAUTHORIZED -> {
                    Log.w(TAG, "sse 401 after refresh → stop retrying")
                    return
                }
                Outcome.EOF, Outcome.ERROR -> {
                    delay(backoffMs)
                    backoffMs = (backoffMs * 2).coerceAtMost(MAX_BACKOFF_MS)
                }
                // 409 游标过期：已清空游标，立即重连（无需退避）
                Outcome.CURSOR_EXPIRED -> backoffMs = INITIAL_BACKOFF_MS
            }
        }
    }

    /** 建立一次连接并阻塞读取事件流，直到断开/出错；返回重连策略依据 */
    private suspend fun connectAndRead(projectId: String): Outcome = withContext(Dispatchers.IO) {
        val url = baseUrl.trimEnd('/') + "/projects/$projectId/events"
        val builder = Request.Builder()
            .url(url)
            .header("Accept", "text/event-stream")
        lastEventId?.let { builder.header("Last-Event-ID", it) }

        try {
            sseClient.newCall(builder.build()).execute().use { response ->
                when {
                    response.code == 409 -> {
                        // EVENT_CURSOR_EXPIRED：续传点已过期（事件保留 24h），清游标从最新重连
                        Log.w(TAG, "sse 409 cursor expired, reset cursor")
                        lastEventId = null
                        Outcome.CURSOR_EXPIRED
                    }
                    response.code == 401 -> {
                        // TokenAuthenticator 已尝试刷新；仍 401 → 停止（避免无限重试）
                        Outcome.UNAUTHORIZED
                    }
                    !response.isSuccessful -> {
                        Log.w(TAG, "sse HTTP ${response.code}")
                        Outcome.ERROR
                    }
                    else -> {
                        val body = response.body ?: return@use Outcome.ERROR
                        val source = body.source()
                        var eventName: String? = null
                        var id: String? = null
                        val dataLines = StringBuilder()
                        while (scope.isActive) {
                            val line = source.readUtf8Line() ?: break // EOF：服务端断开
                            when {
                                line.startsWith("id:") -> id = line.removePrefix("id:").trim()
                                line.startsWith("event:") -> eventName = line.removePrefix("event:").trim()
                                line.startsWith("data:") -> {
                                    if (dataLines.isNotEmpty()) dataLines.append('\n')
                                    dataLines.append(line.removePrefix("data:").trimStart())
                                }
                                line.startsWith(":") -> Unit // 心跳注释行，忽略
                                line.isEmpty() -> {
                                    // 事件边界：分发
                                    val name = eventName
                                    if (name != null) {
                                        SseEventType.fromWire(name)?.let { type ->
                                            if (id != null) lastEventId = id
                                            _events.tryEmit(SseEvent(id, type, dataLines.toString()))
                                        } ?: Log.d(TAG, "unknown sse event: $name")
                                    }
                                    eventName = null
                                    id = null
                                    dataLines.setLength(0)
                                }
                                else -> Unit // 忽略未知字段行
                            }
                        }
                        Outcome.EOF
                    }
                }
            }
        } catch (e: IOException) {
            Log.w(TAG, "sse io: ${e.message}")
            Outcome.ERROR
        }
    }

    private enum class Outcome { EOF, ERROR, CURSOR_EXPIRED, UNAUTHORIZED }

    companion object {
        private const val TAG = "ProjectEventStream"
        private const val INITIAL_BACKOFF_MS = 1_000L
        private const val MAX_BACKOFF_MS = 30_000L
    }
}
