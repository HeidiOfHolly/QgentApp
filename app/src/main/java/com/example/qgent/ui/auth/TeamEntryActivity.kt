package com.example.qgent.ui.auth

import android.app.Dialog
import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import com.example.qgent.MainActivity
import com.example.qgent.QgentApp
import com.example.qgent.R
import com.example.qgent.databinding.ActivityTeamEntryBinding
import com.example.qgent.databinding.DialogNewTewmBinding
import kotlinx.coroutines.launch
import java.util.UUID

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
                    .onSuccess {
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
}
