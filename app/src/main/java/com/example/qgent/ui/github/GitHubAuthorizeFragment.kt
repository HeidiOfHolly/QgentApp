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
import com.example.qgent.databinding.FragmentGithubAuthorizeBinding

/**
 * GitHub 授权页：为所选团队生成 GitHub App 安装链接并用 WebView 打开。
 * 后端安装回调会 302 到移动端前端地址（{FRONTEND_URL_MOBILE}/app/integrations/github?…），
 * 浏览器/CustomTab 拦不到该回跳，故改用 WebView 拦截并解析参数：
 * - installed=1 → 安装成功，刷新安装与仓库列表
 * - conflict=GITHUB_INSTALLATION_TEAM_CONFLICT → 展示后端 message（账号已绑定其他团队）
 */
class GitHubAuthorizeFragment : Fragment() {

    private var _binding: FragmentGithubAuthorizeBinding? = null
    private val binding get() = _binding!!
    private val githubViewModel: GithubViewModel by activityViewModels {
        (requireActivity().application as QgentApp).container.githubViewModelFactory
    }

    private val teamId: String by lazy { arguments?.getString("teamId").orEmpty() }
    private val teamName: String by lazy { arguments?.getString("teamName").orEmpty() }

    /** 移动端回调回跳地址：{FRONTEND_URL_MOBILE}/app/integrations/github */
    private val callbackHost = "mobile.qgents.dpdns.org"
    private val callbackPath = "/app/integrations/github"

    /** 防止 shouldOverrideUrlLoading 与 onPageStarted 重复处理同一次回跳 */
    private var callbackHandled = false

    /** 点击「去授权」时团队是否已绑定 GitHub 账号（存在 ACTIVE 安装）；已绑定则跳过账号绑定提醒与安装成功提示 */
    private var hadGitHubAccount = false

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
        _binding = FragmentGithubAuthorizeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.ivBack.setOnClickListener {
            if (binding.webView.isVisible) hideWebView() else findNavController().popBackStack()
        }

        // 标题带所选团队名（有值时）
        if (teamName.isNotEmpty()) {
            binding.tvAuthorizeTitle.text = getString(R.string.github_authorize_title_team, teamName)
        }

        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, backCallback)
        configureWebView()

        binding.btnAuthorize.setOnClickListener {
            if (teamId.isEmpty()) {
                Toast.makeText(requireContext(), R.string.github_authorize_missing_team, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            // 点击时记录团队是否已有 GitHub 账号（存在 ACTIVE 安装），决定本次是否弹提示
            hadGitHubAccount = githubViewModel.uiState.value?.installations?.any { it.status == "ACTIVE" } ?: false
            githubViewModel.createInstallationUrl(teamId)
        }

        githubViewModel.uiState.observe(viewLifecycleOwner) { state ->
            // 安装链接就绪：未绑定 GitHub 账号的团队先弹账号绑定提醒，已绑定的直接打开 WebView
            state.installationUrl?.let { url ->
                githubViewModel.consumeInstallationUrl()
                if (hadGitHubAccount) showWebView(url) else confirmBeforeAuthorize(url)
            }
            if (state.installed) {
                // 已绑定账号的团队重复授权不算新安装，不弹安装成功提示
                if (!hadGitHubAccount) {
                    Toast.makeText(requireContext(), R.string.github_install_success, Toast.LENGTH_SHORT).show()
                }
                githubViewModel.consumeInstalled()
            }
            state.error?.let {
                Toast.makeText(requireContext(), it, Toast.LENGTH_SHORT).show()
                githubViewModel.consumeError()
            }
        }
    }

    private fun configureWebView() {
        binding.webView.apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            // 必须有 WebChromeClient：GitHub 安装页点「Uninstall」会调 window.confirm() 弹确认框，
            // 缺省时 WebView 静默吞掉确认框（默认返回 false），导致卸载没有任何反应
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

    /**
     * 打开 GitHub 前提示账号绑定规则（文档 §6）：
     * 一个 GitHub 账号只能授权给一个团队；若该账号已绑定其他团队，本次授权会进入其原安装，
     * 在 GitHub 配置页增删的仓库将作用于原团队，后端无法拦截。
     */
    private fun confirmBeforeAuthorize(url: String) {
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.github_authorize_warning_title)
            .setMessage(R.string.github_authorize_warning_message)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.github_authorize_continue) { _, _ -> showWebView(url) }
            .show()
    }

    private fun showWebView(url: String) {
        callbackHandled = false
        binding.scrollView.isVisible = false
        binding.webView.isVisible = true
        backCallback.isEnabled = true
        // 每次授权前清除 WebView 的登录态：一个 GitHub 账号只能绑定一个团队，
        // 若不清理，上次登录的账号 cookie 会被自动带入，导致无法为其他团队选择不同账号
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

    /** 解析后端回跳参数并交给 ViewModel 刷新列表 / 提示冲突 */
    private fun handleCallback(uri: Uri) {
        callbackHandled = true
        hideWebView()
        val conflict = uri.getQueryParameter("conflict")
        val installed = uri.getQueryParameter("installed")
        when {
            conflict == "GITHUB_INSTALLATION_TEAM_CONFLICT" -> {
                val message = uri.getQueryParameter("message")
                    ?: getString(R.string.github_install_conflict)
                githubViewModel.handleCallbackResult(teamId, installed = false, conflictMessage = message)
            }
            installed == "1" -> githubViewModel.handleCallbackResult(teamId, installed = true, conflictMessage = null)
            else -> {
                // 未识别回跳：兜底刷新，保持页面与后端一致
                githubViewModel.refreshInstallations(teamId)
                githubViewModel.loadRepositories(teamId)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // 从后台返回（或初次进入）时刷新安装/仓库状态；
        // syncInstallations 会强制后端重拉仓库元数据，让网页端删除的仓库也能同步消失
        if (teamId.isNotEmpty()) {
            githubViewModel.refreshInstallations(teamId)
            githubViewModel.syncInstallations(teamId)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
