package com.example.qgent.ui.personal

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.bumptech.glide.Glide
import com.example.qgent.QgentApp
import com.example.qgent.R
import com.example.qgent.data.SessionStore
import com.example.qgent.data.api.RetrofitClient
import com.example.qgent.data.model.ApiException
import com.example.qgent.databinding.FragmentProfileBinding
import com.example.qgent.ui.auth.LoginActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 个人信息页：展示当前登录用户（头像 + 昵称），支持点击头像上传，支持退出登录 */
class ProfileFragment : Fragment() {

    private var _binding: FragmentProfileBinding? = null
    private val binding get() = _binding!!

    // 系统相册选图：免存储权限，返回 content:// URI
    private val pickImage = registerForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) uploadAvatar(uri)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentProfileBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // 返回上一页
        binding.ivBack.setOnClickListener { findNavController().navigateUp() }

        // 展示当前登录用户信息
        val user = SessionStore.user()
        binding.tvUserName.text = user?.displayName ?: getString(R.string.user_name_placeholder)
        loadAvatar(user?.avatarUrl)

        // 点击头像 → 选择本地图片上传（§7.0 /me/avatar）
        binding.ivAvatar.setOnClickListener {
            pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }

        // GitHub 绑定入口（§50）：进入个人信息页查询个人 OAuth 状态，点击进入绑定页
        binding.layoutGithub.setOnClickListener {
            findNavController().navigate(R.id.personalGithubOAuthFragment)
        }

        // 退出登录：清空会话并回到登录页
        binding.btnLogout.setOnClickListener {
            SessionStore.clear()
            startActivity(Intent(requireContext(), LoginActivity::class.java))
            requireActivity().finish()
        }
    }

    override fun onResume() {
        super.onResume()
        // 从绑定页返回后重新查询状态（不依赖本地缓存，§50.4）
        loadGithubOAuthStatus()
    }

    /** 查询个人 GitHub OAuth 授权状态，更新个人信息页入口行的绑定状态文本 */
    private fun loadGithubOAuthStatus() {
        viewLifecycleOwner.lifecycleScope.launch {
            (requireActivity().application as QgentApp).container.githubRepository
                .getPersonalOAuthStatus()
                .onSuccess { status ->
                    binding.tvGithubStatus.text = if (status.authorized) {
                        getString(R.string.profile_github_bound, status.githubLogin ?: "")
                    } else {
                        getString(R.string.profile_github_unbound)
                    }
                }
                .onFailure {
                    // 查询失败保持默认文本，不打断个人信息页
                }
        }
    }

    /** 展示头像：有 URL 用 Glide 加载（公共读地址，无需鉴权头），centerCrop 占满圆形画框；否则默认占位 */
    private fun loadAvatar(avatarUrl: String?) {
        if (avatarUrl.isNullOrBlank()) {
            binding.ivAvatar.setImageResource(R.drawable.ic_avatar_default)
            return
        }
        Glide.with(binding.ivAvatar)
            .load(RetrofitClient.resolveMediaUrl(avatarUrl))
            .centerCrop()
            .placeholder(R.drawable.ic_avatar_default)
            .error(R.drawable.ic_avatar_default)
            .into(binding.ivAvatar)
    }

    /** 头像上传流程：credential 签发凭证 → OSS PUT 直传 → confirm 确认 → 更新本地展示 */
    private fun uploadAvatar(uri: Uri) {
        viewLifecycleOwner.lifecycleScope.launch {
            val meta = readFileMeta(uri)
            val bytes = withContext(Dispatchers.IO) {
                runCatching {
                    requireContext().contentResolver.openInputStream(uri)?.use { it.readBytes() }
                }.getOrNull()
            }
            if (bytes == null || bytes.isEmpty()) {
                Toast.makeText(requireContext(), "读取图片失败", Toast.LENGTH_SHORT).show()
                return@launch
            }
            // 部分 provider 读不到 SIZE，用实际字节数兜底
            val size = if (meta.sizeBytes > 0) meta.sizeBytes else bytes.size.toLong()
            if (size > AVATAR_MAX_BYTES) {
                Toast.makeText(requireContext(), "头像图片不能超过 5MB", Toast.LENGTH_SHORT).show()
                return@launch
            }
            Toast.makeText(requireContext(), "正在上传头像…", Toast.LENGTH_SHORT).show()
            avatarUploader().upload(meta.mimeType ?: "image/jpeg", size, bytes)
                .onSuccess { avatarUrl ->
                    SessionStore.updateAvatar(avatarUrl)
                    loadAvatar(avatarUrl)
                    Toast.makeText(requireContext(), "头像已更新", Toast.LENGTH_SHORT).show()
                }
                .onFailure { e ->
                    // OSS 未启用：501 AVATAR_STORAGE_NOT_CONFIGURED → 提示暂不可用
                    val code = (e as? ApiException)?.code
                    val msg = if (code == "AVATAR_STORAGE_NOT_CONFIGURED") {
                        "头像上传暂不可用"
                    } else {
                        "头像上传失败：${e.message}"
                    }
                    Toast.makeText(requireContext(), msg, Toast.LENGTH_LONG).show()
                }
        }
    }

    private fun avatarUploader(): com.example.qgent.data.repository.AvatarUploader =
        (requireActivity().application as QgentApp).container.avatarUploader

    private data class FileMeta(val fileName: String, val sizeBytes: Long, val mimeType: String?)

    private fun readFileMeta(uri: Uri): FileMeta {
        val resolver = requireContext().contentResolver
        var fileName = "avatar"
        var sizeBytes = 0L
        resolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (nameIdx >= 0) fileName = cursor.getString(nameIdx) ?: fileName
                val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (sizeIdx >= 0 && !cursor.isNull(sizeIdx)) sizeBytes = cursor.getLong(sizeIdx)
            }
        }
        return FileMeta(fileName, sizeBytes, resolver.getType(uri))
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        /** 头像大小上限：5MB（文档 §7.0） */
        private const val AVATAR_MAX_BYTES = 5L * 1024 * 1024
    }
}
