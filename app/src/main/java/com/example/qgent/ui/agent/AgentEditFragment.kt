package com.example.qgent.ui.agent

import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.bumptech.glide.Glide
import com.example.qgent.QgentApp
import com.example.qgent.R
import com.example.qgent.data.api.RetrofitClient
import com.example.qgent.data.repository.AgentRepository
import com.example.qgent.databinding.FragmentAgentEditBinding
import com.example.qgent.model.AgentRole
import com.example.qgent.viewmodel.MainViewModel
import androidx.fragment.app.activityViewModels
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * 新建 / 编辑 Agent 表单。
 * - 新建：POST /teams/{teamId}/agents（PRIVATE）
 * - 编辑：PATCH /teams/{teamId}/agents/{agentId}（创建者）
 * 头像：选图 → 附件上传（项目 attachments）拿 URL 填入 avatar 字段。
 */
class AgentEditFragment : Fragment() {

    private var _binding: FragmentAgentEditBinding? = null
    private val binding get() = _binding!!
    private val mainViewModel: MainViewModel by activityViewModels {
        (requireActivity().application as QgentApp).container.mainViewModelFactory
    }

    private val agentId: String by lazy { arguments?.getString("agentId").orEmpty() }
    private var avatarUrl: String? = null

    // 系统相册选图 → 上传拿 URL 作 Agent 头像
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
        _binding = FragmentAgentEditBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val isEdit = agentId.isNotEmpty()
        binding.tvTitle.text = if (isEdit) "编辑 Agent" else "新建 Agent"
        binding.btnSave.text = if (isEdit) "保存修改" else "创建"

        // 编辑模式回填
        if (isEdit) {
            binding.etName.setText(arguments?.getString("agentName").orEmpty())
            binding.etDescription.setText(arguments?.getString("agentDescription").orEmpty())
            binding.etPrompt.setText(arguments?.getString("agentPrompt").orEmpty())
            avatarUrl = arguments?.getString("agentAvatar")?.takeIf { it.isNotBlank() }
            avatarUrl?.let { loadAvatar(it) }
            selectRole(arguments?.getString("agentRole").orEmpty())
        } else {
            selectRole("DEVELOPER")
        }

        binding.ivBack.setOnClickListener { findNavController().navigateUp() }

        binding.ivAvatar.setOnClickListener {
            pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }

        binding.btnSave.setOnClickListener { save() }
    }

    private fun selectRole(role: String) {
        val rb = when (role) {
            "GENERAL" -> binding.rbGeneral
            "TESTER" -> binding.rbTester
            "REVIEWER" -> binding.rbReviewer
            else -> binding.rbDeveloper
        }
        rb.isChecked = true
    }

    private fun selectedRole(): String = when (binding.rgRole.checkedRadioButtonId) {
        R.id.rbGeneral -> "GENERAL"
        R.id.rbTester -> "TESTER"
        R.id.rbReviewer -> "REVIEWER"
        else -> "DEVELOPER"
    }

    private fun loadAvatar(url: String) {
        Glide.with(binding.ivAvatar)
            .load(RetrofitClient.resolveMediaUrl(url))
            .centerCrop()
            .placeholder(R.drawable.ic_avatar_default)
            .error(R.drawable.ic_avatar_default)
            .into(binding.ivAvatar)
    }

    /** 选图上传：Agent 头像走专用接口（v2.0.6 §5.2，teams/{teamId}/agents/avatar 的 credential/confirm） */
    private fun uploadAvatar(uri: Uri) {
        val teamId = mainViewModel.currentTeamId() ?: run {
            Toast.makeText(requireContext(), "请先选择团队", Toast.LENGTH_SHORT).show()
            return
        }
        viewLifecycleOwner.lifecycleScope.launch {
            val app = requireActivity().application as QgentApp
            val meta = readFileMeta(uri)
            val bytes = withContext(Dispatchers.IO) {
                runCatching { requireContext().contentResolver.openInputStream(uri)?.use { it.readBytes() } }.getOrNull()
            }
            if (bytes == null || bytes.isEmpty()) {
                Toast.makeText(requireContext(), "读取图片失败", Toast.LENGTH_SHORT).show()
                return@launch
            }
            val size = if (meta.sizeBytes > 0) meta.sizeBytes else bytes.size.toLong()
            Toast.makeText(requireContext(), "正在上传头像…", Toast.LENGTH_SHORT).show()
            app.container.agentAvatarUploader.upload(teamId, meta.mimeType ?: "image/jpeg", size, bytes)
                .onSuccess { url ->
                    avatarUrl = url
                    loadAvatar(url)
                    Toast.makeText(requireContext(), "头像已选择", Toast.LENGTH_SHORT).show()
                }
                .onFailure { e ->
                    Toast.makeText(requireContext(), "头像上传失败：${e.message}", Toast.LENGTH_LONG).show()
                }
        }
    }

    private fun save() {
        val name = binding.etName.text?.toString()?.trim().orEmpty()
        if (name.isEmpty()) {
            Toast.makeText(requireContext(), "请输入名称", Toast.LENGTH_SHORT).show()
            return
        }
        // v2.0.6 §5.1：prompt 必填
        val prompt = binding.etPrompt.text?.toString()?.trim()
        if (prompt.isNullOrEmpty()) {
            Toast.makeText(requireContext(), "请输入系统提示词（prompt 必填）", Toast.LENGTH_SHORT).show()
            return
        }
        // 用途描述必填（产品约定：创建自定义 Agent 时用途说明必须填写）
        val description = binding.etDescription.text?.toString()?.trim()
        if (description.isNullOrEmpty()) {
            Toast.makeText(requireContext(), "请输入用途描述（必填）", Toast.LENGTH_SHORT).show()
            return
        }
        val teamId = mainViewModel.currentTeamId() ?: run {
            Toast.makeText(requireContext(), "请先选择团队", Toast.LENGTH_SHORT).show()
            return
        }
        val role = selectedRole()
        binding.btnSave.isEnabled = false
        viewLifecycleOwner.lifecycleScope.launch {
            val result = if (agentId.isEmpty()) {
                agentRepo().createAgent(teamId, name, role, avatarUrl, null, prompt, UUID.randomUUID().toString())
            } else {
                agentRepo().updateAgent(teamId, agentId, name, avatarUrl, null, prompt, UUID.randomUUID().toString())
            }
            result.onSuccess {
                // 创建/编辑成功 → 刷新团队 Agent 列表，让新建的 Agent 立即出现在 Agent 名片中
                mainViewModel.refreshAgents()
                Toast.makeText(requireContext(), if (agentId.isEmpty()) "Agent 已创建" else "已保存", Toast.LENGTH_SHORT).show()
                findNavController().navigateUp()
            }.onFailure { e ->
                binding.btnSave.isEnabled = true
                Toast.makeText(requireContext(), "保存失败：${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun agentRepo(): AgentRepository =
        (requireActivity().application as QgentApp).container.agentRepository

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
}
