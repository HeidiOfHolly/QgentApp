package com.example.qgent.ui.auth

import androidx.lifecycle.LiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.asLiveData
import androidx.lifecycle.viewModelScope
import com.example.qgent.data.SessionStore
import com.example.qgent.data.repository.AuthRepository
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

    fun register(email: String, displayName: String, password: String) {
        if (_uiState.value.loading) return
        _uiState.value = AuthUiState(loading = true)
        viewModelScope.launch {
            repo.register(email.trim(), displayName.trim(), password)
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
