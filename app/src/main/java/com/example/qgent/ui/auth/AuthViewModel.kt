package com.example.qgent.ui.auth

import androidx.lifecycle.LiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.asLiveData
import androidx.lifecycle.viewModelScope
import com.example.qgent.data.SessionStore
import com.example.qgent.data.repository.AuthRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/**
 * 登录 / 注册共用的 ViewModel。
 * success 为一次性事件：Activity 观察到 true 后保存会话并跳转，随后置回 false。
 * AuthRepository 由 AppContainer 构造注入。
 */
class AuthViewModel(private val repo: AuthRepository) : ViewModel() {

    data class AuthUiState(
        val loading: Boolean = false,
        val error: String? = null,
        val success: Boolean = false
    )

    private val _uiState = MutableStateFlow(AuthUiState())
    val uiState: LiveData<AuthUiState> = _uiState.asLiveData()

    /** 发送验证码状态（v2.0.6 §11.1）：sending 发送中、sent 已发送、error 失败文案、retryInSeconds 倒计时 */
    data class CodeState(
        val sending: Boolean = false,
        val sent: Boolean = false,
        val error: String? = null,
        val retryInSeconds: Int = 0
    )

    private val _codeState = MutableStateFlow(CodeState())
    val codeState: LiveData<CodeState> = _codeState.asLiveData()

    /** 忘记密码页发送验证码状态（§11.3）：与注册验证码独立，避免跨页倒计时/发送态串扰 */
    private val _resetCodeState = MutableStateFlow(CodeState())
    val resetCodeState: LiveData<CodeState> = _resetCodeState.asLiveData()

    /** 忘记密码页提交重置状态：success 一次性事件，成功后回到登录页 */
    private val _resetUiState = MutableStateFlow(AuthUiState())
    val resetUiState: LiveData<AuthUiState> = _resetUiState.asLiveData()

    fun login(email: String, password: String) {
        if (_uiState.value.loading) return
        _uiState.value = AuthUiState(loading = true)
        viewModelScope.launch {
            repo.login(email.trim(), password)
                .onSuccess { session ->
                    SessionStore.saveSession(session)
                    _uiState.value = AuthUiState(success = true)
                }
                .onFailure { e ->
                    _uiState.value = AuthUiState(error = e.message ?: "登录失败，请稍后重试")
                }
        }
    }

    /** 发送注册邮箱验证码（v2.0.6 §11.1）：成功后进入 60s 重发倒计时 */
    fun sendVerificationCode(email: String) {
        if (_codeState.value.sending || _codeState.value.retryInSeconds > 0) return
        _codeState.value = CodeState(sending = true)
        viewModelScope.launch {
            repo.sendRegisterVerificationCode(email.trim())
                .onSuccess {
                    _codeState.value = CodeState(sent = true, retryInSeconds = 60)
                    startCountdown()
                }
                .onFailure { e ->
                    _codeState.value = CodeState(error = e.message ?: "验证码发送失败，请稍后重试")
                }
        }
    }

    /** 发送成功后 60s 倒计时递减；归零后允许再次发送 */
    private fun startCountdown() {
        viewModelScope.launch {
            var seconds = _codeState.value.retryInSeconds
            while (seconds > 0) {
                delay(1000)
                seconds--
                _codeState.value = _codeState.value.copy(retryInSeconds = seconds)
            }
        }
    }

    /** 发送密码重置邮箱验证码（§11.3）：成功后进入 60s 重发倒计时 */
    fun sendResetCode(email: String) {
        if (_resetCodeState.value.sending || _resetCodeState.value.retryInSeconds > 0) return
        _resetCodeState.value = CodeState(sending = true)
        viewModelScope.launch {
            repo.sendPasswordResetCode(email.trim())
                .onSuccess {
                    _resetCodeState.value = CodeState(sent = true, retryInSeconds = 60)
                    startResetCountdown()
                }
                .onFailure { e ->
                    _resetCodeState.value = CodeState(error = e.message ?: "验证码发送失败，请稍后重试")
                }
        }
    }

    /** 发送成功后 60s 倒计时递减；归零后允许再次发送 */
    private fun startResetCountdown() {
        viewModelScope.launch {
            var seconds = _resetCodeState.value.retryInSeconds
            while (seconds > 0) {
                delay(1000)
                seconds--
                _resetCodeState.value = _resetCodeState.value.copy(retryInSeconds = seconds)
            }
        }
    }

    /** 清除密码重置发送验证码的错误提示 */
    fun consumeResetCodeError() {
        _resetCodeState.value = _resetCodeState.value.copy(error = null)
    }

    /** 用邮箱验证码设置新密码（§11.3）：成功后置 success，界面跳回登录 */
    fun resetPassword(token: String, newPassword: String) {
        if (_resetUiState.value.loading) return
        _resetUiState.value = AuthUiState(loading = true)
        viewModelScope.launch {
            repo.resetPassword(token.trim(), newPassword)
                .onSuccess {
                    _resetUiState.value = AuthUiState(success = true)
                }
                .onFailure { e ->
                    _resetUiState.value = AuthUiState(error = e.message ?: "密码重置失败，请稍后重试")
                }
        }
    }

    /** 重置成功后清除一次性 success 事件 */
    fun consumeResetSuccess() {
        _resetUiState.value = _resetUiState.value.copy(success = false)
    }

    fun consumeResetError() {
        _resetUiState.value = _resetUiState.value.copy(error = null)
    }

    /** 清除发送验证码的错误提示 */
    fun consumeCodeError() {
        _codeState.value = _codeState.value.copy(error = null)
    }

    fun register(email: String, displayName: String, password: String, verificationCode: String) {
        if (_uiState.value.loading) return
        _uiState.value = AuthUiState(loading = true)
        viewModelScope.launch {
            repo.register(email.trim(), displayName.trim(), password, verificationCode.trim())
                .onSuccess { session ->
                    SessionStore.saveSession(session)
                    _uiState.value = AuthUiState(success = true)
                }
                .onFailure { e ->
                    _uiState.value = AuthUiState(error = e.message ?: "注册失败，请稍后重试")
                }
        }
    }

    /** Activity 跳转后清除一次性 success 事件 */
    fun consumeSuccess() {
        _uiState.value = _uiState.value.copy(success = false)
    }

    fun consumeError() {
        _uiState.value = _uiState.value.copy(error = null)
    }
}
