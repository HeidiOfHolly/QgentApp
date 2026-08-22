package com.example.qgent.ui.github

import android.app.AlertDialog
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.navigation.fragment.findNavController
import com.example.qgent.QgentApp
import com.example.qgent.R
import com.example.qgent.data.model.PersonalGithubOAuthDto
import com.example.qgent.databinding.FragmentPersonalGithubOauthBinding

/**
 * 个人 GitHub OAuth 绑定页（§50）：展示个人授权状态，发起绑定 / 撤销授权。
 * 授权跳转复用 GitHubAuthorizeFragment 的 WebView 拦截模式：后端 OAuth 回调 302 到
 * 移动端固定页面（{FRONTEND_URL_MOBILE}/app/settings/integrations/github?githubOAuth=…），
 * WebView 拦截解析参数：
 * - githubOAuth=authorized → 绑定成功，重新查询状态
 * - githubOAuth=failed&code=… → 按 §50.7 错误码提示，不静默
 * 回调不携带 code/Token；state 一次性消费由后端保证。
 */
class PersonalGithubOAuthFragment : Fragment() {

    private var _binding: FragmentPersonalGithubOauthBinding? = null
    private val binding get() = _binding!!

    private val viewModel: PersonalGithubOAuthViewModel by activityViewModels {
        (requireActivity().application as QgentApp).container.personalGithubOAuthViewModelFactory
    }

    /** 回调回跳地址：{FRONTEND_URL}/app/settings/integrations/github（§50.3 固定页面） */
    private val callbackHost = "mobile.qgents.dpdns.org"
    private val callbackPath = "/app/settings/integrations/github"

    /** 建仓错误触发重新授权时显示授权入口，即使旧 OAuth 记录仍存在。 */
    private var forceReauthorization = false

    /** 防止 shouldOverrideUrlLoading 与 onPageStarted 重复处理同一次回跳 */
    private var callbackHandled = false

    /** 系统返回键：WebView 内部可回退时先回退，否则收起 WebView 交还默认返回行为 */
    private val backCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            if (binding.webView.canGoBack()) {
                binding.webView.goBack()
            } else {
                hideWebView()
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentPersonalGithubOauthBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        forceReauthorization = arguments?.getBoolean(ARG_FORCE_REAUTH) == true

        binding.ivBack.setOnClickListener {
            if (binding.webView.isVisible) hideWebView() else findNavController().popBackStack()
        }

        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, backCallback)
        configureWebView()

        binding.bnBind.setOnClickListener { viewModel.startOAuth() }
        binding.bnRevoke.setOnClickListener { confirmRevoke() }

        viewModel.uiState.observe(viewLifecycleOwner) { state ->
            binding.progressBar.isVisible = state.loading
            state.status?.let { renderStatus(it) }
            // 授权地址就绪：打开 WebView 跳 GitHub
            state.authorizationUrl?.let { url ->
                viewModel.consumeAuthorizationUrl()
                showWebView(url)
            }
            if (state.authorizedEvent) {
                Toast.makeText(requireContext(), R.string.personal_github_oauth_authorized_success, Toast.LENGTH_SHORT).show()
                viewModel.consumeAuthorizedEvent()
            }
            if (state.revokeDone) {
                Toast.makeText(requireContext(), R.string.personal_github_oauth_revoke_success, Toast.LENGTH_SHORT).show()
                viewModel.consumeRevokeDone()
            }
            state.error?.let {
                Toast.makeText(requireContext(), it, Toast.LENGTH_LONG).show()
                viewModel.consumeError()
            }
        }
    }

    /** 按授权状态渲染：未绑定 → 去绑定 + 前置引导；已绑定 → 账号信息 + 撤销 */
    private fun renderStatus(status: PersonalGithubOAuthDto) {
        val accountMismatch = status.personalRepositorySetup == "ACCOUNT_MISMATCH"
        val needsReauthorization = forceReauthorization || accountMismatch
        binding.tvStatusTitle.setText(
            if (needsReauthorization) R.string.personal_github_oauth_reauthorize_title
            else if (status.authorized) R.string.personal_github_oauth_authorized_title
            else R.string.personal_github_oauth_unauthorized_title
        )
        binding.tvStatusDesc.setText(
            if (needsReauthorization) R.string.personal_github_oauth_reauthorize_desc
            else if (status.authorized) R.string.personal_github_oauth_authorized_desc
            else R.string.personal_github_oauth_unauthorized_desc
        )
        if (status.authorized) {
            binding.bnBind.isVisible = needsReauthorization
            binding.authorizedSection.isVisible = true
            binding.bnRevoke.isVisible = !needsReauthorization
            val setupHint = setupHint(status.personalRepositorySetup, status.expectedInstallationLogin)
                ?: if (needsReauthorization) getString(R.string.personal_github_oauth_reauthorize_hint) else null
            binding.tvSetupHint.isVisible = !setupHint.isNullOrBlank()
            binding.tvSetupHint.text = setupHint
            binding.tvGithubLogin.text = status.githubLogin?.let { "@$it" } ?: ""
            val scopeText = scopeText(status.scopes)
            binding.tvGithubScopes.isVisible = !scopeText.isNullOrBlank()
            binding.tvGithubScopes.text = scopeText
            binding.tvGithubAuthorizedAt.isVisible = !status.authorizedAt.isNullOrBlank()
            binding.tvGithubAuthorizedAt.text = status.authorizedAt?.let {
                getString(R.string.personal_github_oauth_authorized_at, it)
            }
        } else {
            binding.bnBind.isVisible = true
            binding.authorizedSection.isVisible = false
            binding.bnRevoke.isVisible = false
            // personalRepositorySetup 前置引导（§50.4 五态）
            val hint = setupHint(status.personalRepositorySetup, status.expectedInstallationLogin)
            binding.tvSetupHint.isVisible = !hint.isNullOrBlank()
            binding.tvSetupHint.text = hint
        }
    }

    /** §50.4 personalRepositorySetup → 引导文案；READY/null 不展示 */
    private fun setupHint(setup: String?, expectedLogin: String?): String? = when (setup) {
        "NOT_OWNER" -> getString(R.string.personal_github_oauth_setup_not_owner)
        "NEED_INSTALLATION" -> getString(R.string.personal_github_oauth_setup_need_installation)
        "NEED_OAUTH" -> getString(R.string.personal_github_oauth_setup_need_oauth)
        "ACCOUNT_MISMATCH" -> getString(
            R.string.personal_github_oauth_setup_account_mismatch,
            expectedLogin ?: getString(R.string.personal_github_oauth_unknown_account)
        )
        else -> null
    }

    /** scopes → 可读文案：repo=私有与公开，public_repo=公开，其余原样拼接；空返回 null */
    private fun scopeText(scopes: List<String>?): String? {
        if (scopes.isNullOrEmpty()) return null
        val names = scopes.map { scope ->
            when (scope) {
                "repo" -> getString(R.string.personal_github_oauth_scope_repo)
                "public_repo" -> getString(R.string.personal_github_oauth_scope_public_repo)
                else -> scope
            }
        }
        return getString(R.string.personal_github_oauth_scope_text, names.joinToString("、"))
    }

    private fun configureWebView() {
        binding.webView.apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            webChromeClient = WebChromeClient()
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    if (!callbackHandled && isCallbackUrl(request.url)) {
                        handleCallback(request.url)
                        return true
                    }
                    return false
                }

                @Deprecated("Deprecated in Java")
                override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean {
                    if (!callbackHandled && isCallbackUrl(Uri.parse(url))) {
                        handleCallback(Uri.parse(url))
                        return true
                    }
                    return false
                }

                // 兜底：个别 302 场景可能不经过 shouldOverrideUrlLoading
                override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                    super.onPageStarted(view, url, favicon)
                    if (!callbackHandled && url != null && isCallbackUrl(Uri.parse(url))) {
                        handleCallback(Uri.parse(url))
                    }
                }
            }
        }
    }

    private fun isCallbackUrl(uri: Uri): Boolean =
        uri.host == callbackHost && uri.path?.startsWith(callbackPath) == true

    private fun showWebView(url: String) {
        callbackHandled = false
        binding.scrollView.isVisible = false
        binding.webView.isVisible = true
        backCallback.isEnabled = true
        // 每次授权前清除 WebView 的 GitHub 登录态：个人 OAuth 需要用户自己选择账号
        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)
        cookieManager.removeAllCookies { binding.webView.loadUrl(url) }
    }

    private fun hideWebView() {
        binding.webView.stopLoading()
        binding.webView.loadUrl("about:blank")
        binding.webView.isVisible = false
        binding.scrollView.isVisible = true
        backCallback.isEnabled = false
    }

    /** 解析后端 OAuth 回跳参数：authorized → 提示成功并刷新；failed → 按 code 提示；未识别 → 兜底刷新 */
    private fun handleCallback(uri: Uri) {
        callbackHandled = true
        hideWebView()
        val result = uri.getQueryParameter("githubOAuth")
        when (result) {
            "authorized" -> {
                forceReauthorization = false
                viewModel.onAuthorized()
            }
            "failed" -> {
                val code = uri.getQueryParameter("code")
                Toast.makeText(
                    requireContext(),
                    PersonalGithubOAuthViewModel.callbackErrorMessage(code),
                    Toast.LENGTH_LONG
                ).show()
                viewModel.refreshStatus()
            }
            else -> viewModel.refreshStatus()
        }
    }

    /** 撤销二次确认：成功后由 ViewModel 刷新状态（本地授权置为不可用，远端由后端尽力撤销） */
    private fun confirmRevoke() {
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.personal_github_oauth_revoke_confirm_title)
            .setMessage(R.string.personal_github_oauth_revoke_confirm_message)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.personal_github_oauth_revoke_confirm_ok) { _, _ -> viewModel.revoke() }
            .show()
    }

    override fun onResume() {
        super.onResume()
        // 从后台返回 / 授权完成返回时刷新状态（不依赖回跳参数或本地缓存，§50.4）
        viewModel.refreshStatus()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        const val ARG_FORCE_REAUTH = "forceGithubReauthorization"
    }
}
