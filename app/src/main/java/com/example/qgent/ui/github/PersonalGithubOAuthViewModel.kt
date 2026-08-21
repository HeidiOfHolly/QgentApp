package com.example.qgent.ui.github

import androidx.lifecycle.LiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.asLiveData
import androidx.lifecycle.viewModelScope
import com.example.qgent.data.model.ApiException
import com.example.qgent.data.model.GitHubOAuthStartResponse
import com.example.qgent.data.model.PersonalGithubOAuthDto
import com.example.qgent.data.repository.GitHubRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * 个人 GitHub OAuth 授权 ViewModel（§50）：
 * 查询授权状态、发起授权（获取 authorizationUrl）、撤销授权。
 * 幂等键由每次用户操作生成一次 UUID（同次操作重试复用、重新点击生成新键）。
 */
class PersonalGithubOAuthViewModel(
    private val repo: GitHubRepository
) : ViewModel() {

    data class UiState(
        /** 首次/刷新状态查询中 */
        val loading: Boolean = false,
        /** 授权状态；null = 尚未成功加载 */
        val status: PersonalGithubOAuthDto? = null,
        /** 发起授权返回的 GitHub 授权地址（一次性，消费后清空） */
        val authorizationUrl: String? = null,
        /** 授权地址过期时间（ISO8601，仅展示用） */
        val authorizationExpiresAt: String? = null,
        /** 撤销进行中（防止重复点击） */
        val revoking: Boolean = false,
        /** 一次性事件：OAuth 回调返回 authorized 成功（用于 toast） */
        val authorizedEvent: Boolean = false,
        /** 一次性事件：撤销成功（用于 toast） */
        val revokeDone: Boolean = false,
        val error: String? = null
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState: LiveData<UiState> = _uiState.asLiveData()

    /** 查询当前用户个人 GitHub 授权状态（进入页面 / 回调返回 / onResume 均调用） */
    fun refreshStatus() {
        if (_uiState.value.loading) return
        _uiState.value = _uiState.value.copy(loading = true, error = null)
        viewModelScope.launch {
            repo.getPersonalOAuthStatus()
                .onSuccess { status ->
                    _uiState.value = _uiState.value.copy(loading = false, status = status)
                }
                .onFailure { e ->
                    _uiState.value = _uiState.value.copy(
                        loading = false,
                        error = e.message ?: "查询 GitHub 绑定状态失败，请稍后重试"
                    )
                }
        }
    }

    /** 发起个人 GitHub OAuth 授权：POST start 拿 authorizationUrl 交给 WebView 跳转 */
    fun startOAuth() {
        if (_uiState.value.loading || _uiState.value.revoking) return
        _uiState.value = _uiState.value.copy(loading = true, error = null)
        viewModelScope.launch {
            repo.startPersonalOAuth(UUID.randomUUID().toString())
                .onSuccess { resp: GitHubOAuthStartResponse ->
                    _uiState.value = _uiState.value.copy(
                        loading = false,
                        authorizationUrl = resp.authorizationUrl,
                        authorizationExpiresAt = resp.expiresAt
                    )
                }
                .onFailure { e ->
                    _uiState.value = _uiState.value.copy(
                        loading = false,
                        error = startErrorMessage(e)
                    )
                }
        }
    }

    /** 撤销个人 GitHub 授权：DELETE，成功后刷新状态 */
    fun revoke() {
        if (_uiState.value.revoking) return
        _uiState.value = _uiState.value.copy(revoking = true, error = null)
        viewModelScope.launch {
            repo.revokePersonalOAuth(UUID.randomUUID().toString())
                .onSuccess {
                    _uiState.value = _uiState.value.copy(revoking = false, revokeDone = true)
                    refreshStatus()
                }
                .onFailure { e ->
                    _uiState.value = _uiState.value.copy(
                        revoking = false,
                        error = revokeErrorMessage(e)
                    )
                }
        }
    }

    /** OAuth 回调返回 authorized 成功：置一次性事件（UI toast）并刷新状态 */
    fun onAuthorized() {
        _uiState.value = _uiState.value.copy(authorizedEvent = true)
        refreshStatus()
    }

    fun consumeAuthorizationUrl() {
        _uiState.value = _uiState.value.copy(authorizationUrl = null, authorizationExpiresAt = null)
    }

    fun consumeAuthorizedEvent() {
        _uiState.value = _uiState.value.copy(authorizedEvent = false)
    }

    fun consumeRevokeDone() {
        _uiState.value = _uiState.value.copy(revokeDone = false)
    }

    fun consumeError() {
        _uiState.value = _uiState.value.copy(error = null)
    }

    /** §50.7：发起授权失败错误码 → 用户文案 */
    private fun startErrorMessage(e: Throwable): String = when {
        e is ApiException && e.code == "GITHUB_OAUTH_NOT_CONFIGURED" ->
            "服务端未配置 GitHub OAuth，暂不可用"
        e is ApiException && e.code == "GITHUB_OAUTH_UPSTREAM_UNAVAILABLE" ->
            "GitHub 服务暂不可用，请稍后重试"
        else -> e.message ?: "获取 GitHub 授权地址失败，请稍后重试"
    }

    /** §50.5/§50.7：撤销失败错误码 → 用户文案（401 GITHUB_OAUTH_REVOKE_REJECTED 时本地不标记已撤销） */
    private fun revokeErrorMessage(e: Throwable): String = when {
        e is ApiException && e.code == "GITHUB_OAUTH_REVOKE_REJECTED" ->
            "GitHub 拒绝撤销（应用凭证或 Token 校验失败），请稍后重试"
        e is ApiException && e.code == "GITHUB_OAUTH_UPSTREAM_UNAVAILABLE" ->
            "撤销失败：GitHub 服务暂不可用，请稍后重试"
        else -> e.message ?: "撤销失败，请稍后重试"
    }

    companion object {
        /** §50.3 回调失败 code（githubOAuth=failed&code=...）→ 用户文案 */
        fun callbackErrorMessage(code: String?): String = when (code) {
            "GITHUB_OAUTH_STATE_INVALID" -> "GitHub 授权链接无效，请重新发起绑定"
            "GITHUB_OAUTH_STATE_EXPIRED" -> "GitHub 授权链接已过期，请重新发起绑定"
            "GITHUB_OAUTH_STATE_REPLAYED" -> "GitHub 授权链接已被使用，请重新发起绑定"
            "GITHUB_OAUTH_CALLBACK_DENIED" -> "你在 GitHub 拒绝了授权"
            "GITHUB_OAUTH_CODE_EXCHANGE_FAILED" -> "GitHub 授权失败（换取 Token 失败），请稍后重试"
            "GITHUB_OAUTH_ACCOUNT_LOOKUP_FAILED" -> "GitHub 授权失败（账号查询失败），请稍后重试"
            "GITHUB_OAUTH_ACCOUNT_MISMATCH" -> "该 GitHub 账号已绑定其他 Qgents 用户，无法绑定"
            "GITHUB_OAUTH_CALLBACK_CONFLICT" -> "GitHub 授权状态冲突，请稍后重试"
            "GITHUB_OAUTH_UPSTREAM_UNAVAILABLE" -> "GitHub 服务暂不可用，请稍后重试"
            else -> if (code.isNullOrBlank()) "GitHub 授权失败，请重试" else "GitHub 授权失败（$code）"
        }
    }
}
