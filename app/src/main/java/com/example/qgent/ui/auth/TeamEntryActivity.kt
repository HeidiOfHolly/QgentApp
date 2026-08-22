package com.example.qgent.ui.auth

import android.app.Dialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import com.bumptech.glide.Glide
import com.example.qgent.MainActivity
import com.example.qgent.QgentApp
import com.example.qgent.R
import com.example.qgent.data.SessionStore
import com.example.qgent.data.api.RetrofitClient
import com.example.qgent.data.model.ApiException
import com.example.qgent.databinding.ActivityTeamEntryBinding
import com.example.qgent.databinding.DialogJoinTeamBinding
import com.example.qgent.databinding.DialogNewTewmBinding
import com.example.qgent.ui.personal.joinTeamErrorMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

/** 登录 / 注册成功后的团队引导页：创建团队（加入团队待接入） */
class TeamEntryActivity : AppCompatActivity() {

    private lateinit var binding: ActivityTeamEntryBinding

    /** 创建团队弹窗 binding（选图预览用；弹窗关闭后置空） */
    private var createDialogBinding: DialogNewTewmBinding? = null
    private var avatarBytes: ByteArray? = null
    private var avatarMime: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityTeamEntryBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnCreateTeam.setOnClickListener { showCreateTeamDialog() }
        binding.btnJoinTeam.setOnClickListener { showJoinTeamDialog() }
    }

    override fun onResume() {
        super.onResume()
        // 会话过期兜底：token 失效自动登出后，本页若仍存活则强制回登录页
        if (!SessionStore.isLoggedIn()) {
            startActivity(
                Intent(this, LoginActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                }
            )
            finish()
        }
    }

    /** 加入团队：输入邀请码后调用接受邀请接口，成功后进入主界面 */
    private fun showJoinTeamDialog() {
        val dialogBinding = DialogJoinTeamBinding.inflate(layoutInflater)
        val dialog = Dialog(this)
        dialog.setContentView(dialogBinding.root)
        dialog.window?.setBackgroundDrawableResource(R.drawable.bg_card)
        dialog.window?.setLayout(
            (resources.displayMetrics.widthPixels * 0.85f).toInt(),
            WindowManager.LayoutParams.WRAP_CONTENT
        )

        dialogBinding.etCode.doAfterTextChanged {
            if (dialogBinding.codeLayout.error != null) dialogBinding.codeLayout.error = null
        }

        dialogBinding.btnJoin.setOnClickListener {
            val token = dialogBinding.etCode.text.toString().trim()
            if (token.isEmpty()) {
                dialogBinding.codeLayout.error = getString(R.string.error_join_code_required)
                return@setOnClickListener
            }
            dialogBinding.btnJoin.isEnabled = false
            val repo = (application as QgentApp).container.userRepository
            lifecycleScope.launch {
                repo.acceptTeamInvitation(token, UUID.randomUUID().toString())
                    .onSuccess {
                        dialog.dismiss()
                        Toast.makeText(
                            this@TeamEntryActivity,
                            R.string.join_team_success,
                            Toast.LENGTH_SHORT
                        ).show()
                        startActivity(Intent(this@TeamEntryActivity, MainActivity::class.java))
                        finish()
                    }
                    .onFailure { e ->
                        dialogBinding.btnJoin.isEnabled = true
                        Toast.makeText(
                            this@TeamEntryActivity,
                            joinTeamErrorMessage(this@TeamEntryActivity, e, getString(R.string.join_team_failed)),
                            Toast.LENGTH_SHORT
                        ).show()
                    }
            }
        }
        dialog.show()
    }

    private fun showCreateTeamDialog() {
        val dialog = Dialog(this)
        val dialogBinding = DialogNewTewmBinding.inflate(layoutInflater)
        dialog.setContentView(dialogBinding.root)
        dialog.window?.setBackgroundDrawableResource(R.drawable.bg_card)
        dialog.window?.setLayout(
            (resources.displayMetrics.widthPixels * 0.85f).toInt(),
            WindowManager.LayoutParams.WRAP_CONTENT
        )
        createDialogBinding = dialogBinding
        dialog.setOnDismissListener { createDialogBinding = null }

        // 头像：点击选图预览（创建成功后上传，§28.3；失败不阻断创建）
        dialogBinding.ivPhoto.setOnClickListener {
            pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }

        dialogBinding.etName.doAfterTextChanged {
            if (dialogBinding.nameLayout.error != null) dialogBinding.nameLayout.error = null
        }

        dialogBinding.bnNewTeam.setOnClickListener {
            val name = dialogBinding.etName.text.toString().trim()
            if (name.isEmpty()) {
                dialogBinding.nameLayout.error = getString(R.string.error_team_name_required)
                return@setOnClickListener
            }
            val description = dialogBinding.etInformation.text.toString().trim().ifEmpty { null }
            dialogBinding.bnNewTeam.isEnabled = false
            val idempotencyKey = UUID.randomUUID().toString()
            val repo = (application as QgentApp).container.userRepository
            lifecycleScope.launch {
                repo.createTeam(name, description, idempotencyKey)
                    .onSuccess { team ->
                        // 选了头像 → 上传并 PATCH 回写（失败提示但不阻断进入主界面）
                        if (avatarBytes != null) {
                            lifecycleScope.launch { uploadTeamAvatarAndPatch(team.id) }
                        }
                        dialog.dismiss()
                        startActivity(Intent(this@TeamEntryActivity, MainActivity::class.java))
                        finish()
                    }
                    .onFailure { e ->
                        dialogBinding.bnNewTeam.isEnabled = true
                        Toast.makeText(
                            this@TeamEntryActivity,
                            e.message ?: getString(R.string.error_team_create_failed),
                            Toast.LENGTH_SHORT
                        ).show()
                    }
            }
        }
        dialog.show()
    }

    private val pickImage = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) onAvatarPicked(uri)
    }

    /** 选中头像：读字节 + 预览（上传延迟到团队创建成功后，此时才有 teamId） */
    private fun onAvatarPicked(uri: Uri) {
        lifecycleScope.launch {
            val bytes = withContext(Dispatchers.IO) {
                runCatching { contentResolver.openInputStream(uri)?.use { it.readBytes() } }.getOrNull()
            }
            if (bytes == null || bytes.isEmpty()) {
                Toast.makeText(this@TeamEntryActivity, "读取图片失败", Toast.LENGTH_SHORT).show()
                return@launch
            }
            if (bytes.size > AVATAR_MAX_BYTES) {
                Toast.makeText(this@TeamEntryActivity, "头像图片不能超过 5MB", Toast.LENGTH_SHORT).show()
                return@launch
            }
            avatarBytes = bytes
            avatarMime = contentResolver.getType(uri) ?: "image/png"
            createDialogBinding?.ivPhoto?.let {
                Glide.with(it).load(uri).centerCrop().into(it)
            }
        }
    }

    /** 团队头像上传（§28.1）：credential → OSS PUT → confirm → PATCH /teams/{id} 回写 */
    private suspend fun uploadTeamAvatarAndPatch(teamId: String) {
        val bytes = avatarBytes ?: return
        val uploader = (application as QgentApp).container.avatarUploader
        uploader.uploadFor(
            avatarMime ?: "image/png",
            bytes.size.toLong(),
            bytes,
            { key, body -> RetrofitClient.service.createTeamAvatarCredential(teamId, key, body) },
            { key, body -> RetrofitClient.service.confirmTeamAvatar(teamId, key, body) }
        ).onSuccess { avatarUrl ->
            (application as QgentApp).container.userRepository
                .updateTeam(teamId, avatarUrl, UUID.randomUUID().toString())
                .onFailure { error ->
                    Toast.makeText(
                        this@TeamEntryActivity,
                        "头像已上传，但团队头像保存失败：${error.message}",
                        Toast.LENGTH_LONG
                    ).show()
                }
        }.onFailure { e ->
            val code = (e as? ApiException)?.code
            Toast.makeText(
                this@TeamEntryActivity,
                if (code == "AVATAR_STORAGE_NOT_CONFIGURED") "头像上传暂不可用" else "头像上传失败：${e.message}",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    companion object {
        private const val AVATAR_MAX_BYTES = 5 * 1024 * 1024L
    }
}
