package com.example.qgent.data.ws

import android.util.Log
import com.example.qgent.data.SessionStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject

/**
 * 实时通道事件帧（WebSocket 单连接用户级聚合，后端更新说明 2026-08-17）：
 * `type` 与 SSE 事件名一致（message.created / task.updated / notification.created …），
 * `scope` = project | team | notification；`payload` 与 SSE data 同源脱敏。
 * REST 是真相：收到帧只作「刷新界面」信号，以对应查询接口为准。
 */
data class RealtimeFrame(
    val type: String,
    val scope: String?,
    val projectId: String?,
    val groupId: String?,
    val payload: String?
)

/**
 * WebSocket 实时通道客户端（后端新增：每个账号单条连接，聚合该用户可见项目的所有事件、
 * 所属团队事件、本人通知；SSE 三端点保留兼容，本客户端用于聊天页实时刷新）。
 *
 * - 握手鉴权：query 参数 `?token=<accessToken>`（浏览器 WebSocket 升级无法带 Authorization 头）；
 * - 心跳：服务端每 15 秒 Ping，OkHttp 自动应答 Pong；
 * - 断线自动重连（指数退避）；重连成功（onOpen）回调 [onReconnected]，调用方重查当前列表兜底；
 * - token 每次连接时从 SessionStore 读最新（刷新后重连自动用新 token）；
 * - 控制帧 hello/heartbeat 不分发，业务事件经 [events] SharedFlow 分发。
 */
class RealtimeClient(
    private val httpClient: OkHttpClient,
    private val baseUrl: String
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var connectJob: Job? = null

    @Volatile
    private var stopped = true

    private var backoffMs = INITIAL_BACKOFF_MS

    /** 当前连接结束信号（onClosed/onFailure 完成，驱动重连循环） */
    private var closeSignal: CompletableDeferred<Unit>? = null

    /** 当前 WebSocket 连接（stop 时主动关闭，防止连接泄漏） */
    private var webSocket: WebSocket? = null

    private val _events = MutableSharedFlow<RealtimeFrame>(extraBufferCapacity = 32)
    val events: SharedFlow<RealtimeFrame> = _events

    /** 断线重连成功后回调（重查当前群列表/消息，REST 兜底补齐断线期间事件） */
    @Volatile
    var onReconnected: (() -> Unit)? = null

    /** 建立（或保持）连接。幂等：已连接时重复调用不重启。 */
    fun start() {
        stopped = false
        if (connectJob?.isActive == true) return
        connectJob = scope.launch { connectLoop() }
    }

    /** 断开连接并停止重连；重新 [start] 可再次建立。 */
    fun stop() {
        stopped = true
        connectJob?.cancel()
        connectJob = null
        webSocket?.close(1000, "client stop")
        webSocket = null
        closeSignal?.complete(Unit)
        closeSignal = null
    }

    private suspend fun connectLoop() {
        while (scope.isActive && !stopped) {
            val stayedUp = try {
                connectOnce()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e // 正常停止（stop）：安静退出，不打错误日志
            } catch (e: Exception) {
                Log.w(TAG, "ws error: ${e::class.simpleName}: ${e.message}")
                false
            }
            if (stopped || !scope.isActive) return
            if (stayedUp) {
                backoffMs = INITIAL_BACKOFF_MS
            } else {
                delay(backoffMs)
                backoffMs = (backoffMs * 2).coerceAtMost(MAX_BACKOFF_MS)
            }
        }
    }

    /** 建立一次连接并阻塞到连接结束；onOpen 成功保持过返回 true */
    private suspend fun connectOnce(): Boolean {
        val token = SessionStore.accessToken() ?: return false
        val request = Request.Builder().url(buildWsUrl() + "?token=" + token).build()
        val signal = CompletableDeferred<Unit>()
        var opened = false
        closeSignal = signal
        val ws = httpClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                opened = true
                backoffMs = INITIAL_BACKOFF_MS
                Log.d(TAG, "ws connected")
                // 重连成功：通知调用方重查当前列表兜底
                onReconnected?.invoke()
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    parseFrame(text)?.let { _events.tryEmit(it) }
                } catch (e: Exception) {
                    Log.w(TAG, "ws frame error: ${e.message}")
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "ws closed code=$code reason=$reason")
                signal.complete(Unit)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.w(TAG, "ws failure: ${t.message}")
                signal.complete(Unit)
            }
        })
        webSocket = ws
        signal.await()
        closeSignal = null
        if (webSocket === ws) webSocket = null
        return opened
    }

    private fun buildWsUrl(): String {
        val base = baseUrl.trimEnd('/')
        val wsBase = when {
            base.startsWith("https://") -> "wss://" + base.removePrefix("https://")
            base.startsWith("http://") -> "ws://" + base.removePrefix("http://")
            else -> base
        }
        return "$wsBase/ws/realtime"
    }

    /** 解析业务事件帧；hello/heartbeat 等控制帧返回 null（不分发） */
    private fun parseFrame(text: String): RealtimeFrame? {
        val json = JSONObject(text)
        val type = json.optString("type")
        if (type.isEmpty() || type == "hello" || type == "heartbeat") return null
        return RealtimeFrame(
            type = type,
            scope = json.optString("scope").takeIf { it.isNotBlank() },
            projectId = json.optString("projectId").takeIf { it.isNotBlank() },
            groupId = json.optString("groupId").takeIf { it.isNotBlank() },
            payload = json.optJSONObject("payload")?.toString()
        )
    }

    companion object {
        private const val TAG = "RealtimeClient"
        private const val INITIAL_BACKOFF_MS = 1_000L
        private const val MAX_BACKOFF_MS = 30_000L
    }
}
