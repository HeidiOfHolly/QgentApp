package com.example.qgent.ui.github

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.browser.customtabs.CustomTabsIntent
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.navigation.fragment.findNavController
import com.example.qgent.QgentApp
import com.example.qgent.R
import com.example.qgent.databinding.FragmentGithubAuthorizeBinding

/**
 * GitHub 授权页：为所选团队生成 GitHub App 安装链接并用 Chrome Custom Tab 打开。
 * 回调由后端 302 到网页地址，App 拦不到，因此在 onResume 重新拉取 Installation 列表
 * 检测新安装（检测到则提示成功）。
 */
class GitHubAuthorizeFragment : Fragment() {

    private var _binding: FragmentGithubAuthorizeBinding? = null
    private val binding get() = _binding!!
    private val githubViewModel: GithubViewModel by activityViewModels {
        (requireActivity().application as QgentApp).container.githubViewModelFactory
    }

    private val teamId: String by lazy { arguments?.getString("teamId").orEmpty() }
    private val teamName: String by lazy { arguments?.getString("teamName").orEmpty() }

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

        binding.ivBack.setOnClickListener { findNavController().popBackStack() }

        // 标题带所选团队名（有值时）
        if (teamName.isNotEmpty()) {
            binding.tvAuthorizeTitle.text = getString(R.string.github_authorize_title_team, teamName)
        }

        binding.btnAuthorize.setOnClickListener {
            if (teamId.isEmpty()) {
                Toast.makeText(requireContext(), R.string.github_authorize_missing_team, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            githubViewModel.createInstallationUrl(teamId)
        }

        githubViewModel.uiState.observe(viewLifecycleOwner) { state ->
            // 安装链接就绪 → 打开 GitHub 授权页
            state.installationUrl?.let { url ->
                openCustomTab(url)
                githubViewModel.consumeInstallationUrl()
            }
            // 回调后检测到新安装 → 提示成功
            if (state.installed) {
                Toast.makeText(requireContext(), R.string.github_install_success, Toast.LENGTH_SHORT).show()
                githubViewModel.consumeInstalled()
            }
            state.error?.let {
                Toast.makeText(requireContext(), it, Toast.LENGTH_SHORT).show()
                githubViewModel.consumeError()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // 从 GitHub 授权页返回（或初次进入）时刷新安装/仓库状态
        if (teamId.isNotEmpty()) {
            githubViewModel.refreshInstallations(teamId)
            githubViewModel.loadRepositories(teamId)
        }
    }

    /** 用 Chrome Custom Tab 打开授权链接（复用系统浏览器登录态） */
    private fun openCustomTab(url: String) {
        try {
            CustomTabsIntent.Builder()
                .setShowTitle(true)
                .build()
                .launchUrl(requireContext(), Uri.parse(url))
        } catch (_: Exception) {
            // 无 Custom Tabs 支持时退化为普通浏览器
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
