package com.example.qgent.ui.auth

import android.app.Dialog
import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doAfterTextChanged
import com.example.qgent.MainActivity
import com.example.qgent.R
import com.example.qgent.databinding.ActivityTeamEntryBinding
import com.example.qgent.databinding.DialogNewTewmBinding

/** 登录 / 注册成功后的团队引导页：创建团队（加入团队待接入） */
class TeamEntryActivity : AppCompatActivity() {

    private lateinit var binding: ActivityTeamEntryBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityTeamEntryBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnCreateTeam.setOnClickListener { showCreateTeamDialog() }
        binding.btnJoinTeam.setOnClickListener {
            Toast.makeText(this, R.string.todo_placeholder, Toast.LENGTH_SHORT).show()
        }
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

        dialogBinding.etName.doAfterTextChanged {
            if (dialogBinding.nameLayout.error != null) dialogBinding.nameLayout.error = null
        }

        dialogBinding.bnNewTeam.setOnClickListener {
            if (dialogBinding.etName.text.toString().trim().isEmpty()) {
                dialogBinding.nameLayout.error = getString(R.string.error_team_name_required)
                return@setOnClickListener
            }
            dialog.dismiss()
            // 创建团队 API 待后端就绪后接入；先进入 GitHub 页配置仓库
            startActivity(
                Intent(this, MainActivity::class.java).apply {
                    putExtra(MainActivity.EXTRA_OPEN_GITHUB, true)
                }
            )
            finish()
        }
        dialog.show()
    }
}
