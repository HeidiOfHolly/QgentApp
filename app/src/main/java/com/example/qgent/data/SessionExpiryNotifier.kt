package com.example.qgent.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 会话过期通知器：TokenAuthenticator 检测到登录过期（access + refresh 均失效）时通知，
 * 由 Application 层监听并执行自动退出登录 + Toast。
 * 防重复：同一会话只通知一次，登录成功后 [reset] 复位，使下次过期能再次触发。
 */
object SessionExpiryNotifier {

    private val _expired = MutableStateFlow(false)
    val expired: StateFlow<Boolean> = _expired.asStateFlow()

    @Volatile
    private var notified = false

    fun notifyExpired() {
        if (!notified) {
            notified = true
            _expired.value = true
        }
    }

    fun reset() {
        notified = false
        _expired.value = false
    }
}
