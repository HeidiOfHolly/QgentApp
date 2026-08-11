package com.example.qgent.ui.auth

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doAfterTextChanged
import com.example.qgent.MainActivity
import com.example.qgent.R
import com.example.qgent.data.SessionStore
import com.example.qgent.databinding.ActivityRegisterBinding
import java.util.regex.Pattern

class RegisterActivity : AppCompatActivity() {

    private lateinit var binding: ActivityRegisterBinding
    private val viewModel: AuthViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityRegisterBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.ivBack.setOnClickListener { finish() }
        binding.tvLoginLink.setOnClickListener { finish() }

        listOf(
            binding.emailLayout,
            binding.displayNameLayout,
            binding.passwordLayout,
            binding.confirmLayout
        ).forEach { layout ->
            layout.editText?.doAfterTextChanged {
                if (layout.error != null) layout.error = null
            }
        }

        binding.btnRegister.setOnClickListener {
            if (validate()) {
                viewModel.register(
                    binding.etEmail.text.toString(),
                    binding.etDisplayName.text.toString(),
                    binding.etPassword.text.toString()
                )
            }
        }

        observeViewModel()
    }

    private fun observeViewModel() {
        viewModel.uiState.observe(this) { state ->
            binding.btnRegister.isEnabled = !state.loading
            state.error?.let {
                Toast.makeText(this, it, Toast.LENGTH_SHORT).show()
                viewModel.consumeError()
            }
            if (state.success) {
                viewModel.consumeSuccess()
                SessionStore.saveRememberedEmail(binding.etEmail.text.toString())
                val name = SessionStore.user()?.displayName ?: ""
                Toast.makeText(this, getString(R.string.register_success, name), Toast.LENGTH_SHORT).show()
                startActivity(Intent(this, MainActivity::class.java))
                finish()
            }
        }
    }

    private fun validate(): Boolean {
        val email = binding.etEmail.text.toString().trim()
        val displayName = binding.etDisplayName.text.toString().trim()
        val password = binding.etPassword.text.toString()
        val confirm = binding.etConfirm.text.toString()

        var valid = true
        if (email.isEmpty()) {
            binding.emailLayout.error = getString(R.string.error_email_required)
            valid = false
        } else if (!EMAIL_PATTERN.matcher(email).matches()) {
            binding.emailLayout.error = getString(R.string.error_email_invalid)
            valid = false
        }
        if (displayName.isEmpty()) {
            binding.displayNameLayout.error = getString(R.string.error_display_name_required)
            valid = false
        }
        if (password.length < 6) {
            binding.passwordLayout.error = getString(R.string.error_password_short)
            valid = false
        }
        if (confirm != password) {
            binding.confirmLayout.error = getString(R.string.error_password_mismatch)
            valid = false
        }
        return valid
    }

    private companion object {
        val EMAIL_PATTERN: Pattern = Pattern.compile(
            "^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$"
        )
    }
}
