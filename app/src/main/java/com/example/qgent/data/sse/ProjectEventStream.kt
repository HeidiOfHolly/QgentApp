package com.example.qgent.data.sse

import android.content.Context
import android.util.Log
import com.example.qgent.data.SessionStore
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
 * SSE 事件流客户端（文档 §12.1 + 团队/通知级补充）。
 *
 * 支持三种流并行（各自独立连接与续传游标，对齐 web 前端逐流独立连接）：
 * - 项目级：`GET /projects/{projectId}/events`（任务/Diff/消息/群/Memory）
 * - 团队级：`GET /teams/{teamId}/events`（成员/项目动态）
 * - 通知级：`GET /notifications/events`（当前用户通知，驱动铃铛红点实时刷新）
 *
 * 复用 [httpClient]（已带 AuthInterceptor + TokenAuthenticator，鉴权与自动刷新由调用方注入的 client 承担）。
 *
 * 契约要点（文档 §12.1）：
 * - 事件 `id` 即流内单调递增 `sequenceNo`，通过 `Last-Event-ID` 请求头断线续传；
 * - 服务端每 15 秒发送心跳（注释行或空行），客户端以 readTimeout 兜底检测死连接；
 * - 续传点过期返回 `409 EVENT_CURSOR_EXPIRED` → 清空游标、丢弃游标重连；
 * - 事件仅用于刷新界面：上层收到事件后必须重新拉取对应查询接口，不把 payload 当完整 DTO。
 *
 * 线程模型：每条流的连接循环运行在内部 [CoroutineScope]（SupervisorJob + IO），
 * 通过 [events] SharedFlow 向订阅者（Fragment 的 lifecycleScope）分发事件。
 * 使用方必须在不再需要时调用 [stop]（Fragment onPause / onDestroyView）。
 */
class ProjectEventStream(
    httpClient: OkHttpClient,
    private val baseUrl: String,
    context: Context
) {

    private val cursorPrefs = context.applicationContext.getSharedPreferences(
        CURSOR_PREFS_NAME,
        Context.MODE_PRIVATE
    )

    private val sseClient: OkHttpClient = httpClient.newBuilder()
        // SSE 长连接：不设 readTimeout（对齐浏览器语义，避免后端心跳缺失/间隔超长时
        // 连接被误判死亡反复重连导致事件丢失）；服务端断开时 readUtf8Line 返回 EOF 检测，
        // 配合 Last-Event-ID 续传重连补齐断线期间事件。
        // connectTimeout 缩短到 10s：后端偶发不可达时快速失败重连，缩短事件丢失窗口。
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .connectTimeout(10, TimeUnit.SECONDS)
        .build()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 流标识（project:xxx / team:xxx / notifications）→ 连接任务；各流独立、可并行 */
    private val connectJobs = mutableMapOf<String, Job>()

    /** 流标识 → 最后收到的事件 id（sequenceNo），作为 Last-Event-ID 续传游标；409 时清空 */
    private val lastEventIdByStream = mutableMapOf<String, String>()

    private val _events = MutableSharedFlow<SseEvent>(extraBufferCapacity = 32)
    val events: SharedFlow<SseEvent> = _events

    /** 建立（或保持）项目级事件流连接。幂等：同 projectId 重复调用不重启。 */
    fun startProject(projectId: String) = start("project:$projectId") {
        "/projects/$projectId/events"
    }

    /** 建立（或保持）团队级事件流连接。 */
    fun startTeam(teamId: String) = start("team:$teamId") {
        "/teams/$teamId/events"
    }

    /** 断开指定流；streamKey 为空时断开全部并清空游标。重新 [startXxx] 可从最新事件开始。 */
    fun stop(streamKey: String? = null) {
        if (streamKey == null) {
            connectJobs.keys.toList().forEach { stop(it) }
        } else {
            connectJobs.remove(streamKey)?.cancel()
        }
    }

    private fun cursorKey(streamKey: String): String =
        "${SessionStore.user()?.id ?: ANONYMOUS_USER}:$streamKey"

    private fun lastEventId(streamKey: String): String? {
        val key = cursorKey(streamKey)
        return lastEventIdByStream[key] ?: cursorPrefs.getString(key, null)?.also {
            lastEventIdByStream[key] = it
        }
    }

    private fun saveLastEventId(streamKey: String, eventId: String) {
        val key = cursorKey(streamKey)
        lastEventIdByStream[key] = eventId
        cursorPrefs.edit().putString(key, eventId).apply()
    }

    private fun clearLastEventId(streamKey: String) {
        val key = cursorKey(streamKey)
        lastEventIdByStream.remove(key)
        cursorPrefs.edit().remove(key).apply()
    }

    private fun start(streamKey: String, pathBuilder: () -> String) {
        if (connectJobs[streamKey]?.isActive == true) return
        connectJobs[streamKey] = scope.launch { connectLoop(streamKey, pathBuilder) }
    }

    private fun isStreamActive(streamKey: String): Boolean =
        connectJobs[streamKey]?.isActive == true

    private suspend fun connectLoop(streamKey: String, pathBuilder: () -> String) {
        var backoffMs = INITIAL_BACKOFF_MS
        while (scope.isActive && isStreamActive(streamKey)) {
            val outcome = try {
                connectAndRead(streamKey, pathBuilder)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "sse error: ${e::class.simpleName}: ${e.message}")
                Outcome.ERROR
            }
            // 流被 stop() 取消后退出
            if (!isStreamActive(streamKey)) return

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
    private suspend fun connectAndRead(streamKey: String, pathBuilder: () -> String): Outcome =
        withContext(Dispatchers.IO) {
            val url = baseUrl.trimEnd('/') + pathBuilder()
            val builder = Request.Builder()
                .url(url)
                .header("Accept", "text/event-stream")
            lastEventId(streamKey)?.let { builder.header("Last-Event-ID", it) }

            try {
                sseClient.newCall(builder.build()).execute().use { response ->
                    when {
                        response.code == 409 -> {
                            // EVENT_CURSOR_EXPIRED：续传点已过期（事件保留 24h），清游标从最新重连
                            Log.w(TAG, "sse 409 cursor expired, reset cursor: $streamKey")
                            clearLastEventId(streamKey)
                            Outcome.CURSOR_EXPIRED
                        }
                        response.code == 401 -> {
                            // TokenAuthenticator handles explicit refresh-token rejection.
                            Outcome.UNAUTHORIZED
                        }
                        !response.isSuccessful -> {
                            Log.w(TAG, "sse HTTP ${response.code}")
                            Outcome.ERROR
                        }
                        else -> {
                            Log.d(TAG, "sse connected: $url")
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
                                                id?.let { saveLastEventId(streamKey, it) }
                                                // 诊断日志降级为 VERBOSE：每事件完整 payload 高频输出
                                                Log.v(TAG, "sse event: $name id=$id data=${dataLines}")
                                                _events.tryEmit(SseEvent(id, type, dataLines.toString()))
                                            } ?: Log.v(TAG, "unknown sse event: $name")
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
        private const val CURSOR_PREFS_NAME = "qgent_sse_cursors"
        private const val ANONYMOUS_USER = "anonymous"
        private const val INITIAL_BACKOFF_MS = 1_000L
        private const val MAX_BACKOFF_MS = 30_000L
    }
}
