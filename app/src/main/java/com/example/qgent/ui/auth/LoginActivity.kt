package com.example.qgent.ui.auth

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doAfterTextChanged
import com.example.qgent.MainActivity
import com.example.qgent.QgentApp
import com.example.qgent.R
import com.example.qgent.data.SessionStore
import com.example.qgent.databinding.ActivityLoginBinding
import java.util.regex.Pattern

class LoginActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLoginBinding
    private val viewModel: AuthViewModel by viewModels {
        (application as QgentApp).container.authViewModelFactory
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 记住的邮箱预填
        SessionStore.rememberedEmail()?.let { binding.etEmail.setText(it) }

        binding.etEmail.doAfterTextChanged {
            if (binding.emailLayout.error != null) binding.emailLayout.error = null
        }
        binding.etPassword.doAfterTextChanged {
            if (binding.passwordLayout.error != null) binding.passwordLayout.error = null
        }

        binding.btnLogin.setOnClickListener {
            if (validate()) {
                viewModel.login(
                    binding.etEmail.text.toString(),
                    binding.etPassword.text.toString()
                )
            }
        }

        binding.tvRegister.setOnClickListener {
            supportFragmentManager.beginTransaction()
                .add(R.id.register_container, RegisterFragment())
                .addToBackStack("register")
                .commit()
        }

        binding.tvForgotPassword.setOnClickListener {
            Toast.makeText(this, R.string.todo_placeholder, Toast.LENGTH_SHORT).show()
        }

        observeViewModel()
    }

    private fun observeViewModel() {
        viewModel.uiState.observe(this) { state ->
            binding.btnLogin.isEnabled = !state.loading
            state.error?.let {
                Toast.makeText(this, it, Toast.LENGTH_SHORT).show()
                viewModel.consumeError()
            }
            if (state.success) {
                viewModel.consumeSuccess()
                if (binding.cbRemember.isChecked) {
                    SessionStore.saveRememberedEmail(binding.etEmail.text.toString())
                } else {
                    SessionStore.saveRememberedEmail("")
                }
                val name = SessionStore.user()?.displayName ?: ""
                Toast.makeText(this, getString(R.string.login_success, name), Toast.LENGTH_SHORT).show()
                startActivity(Intent(this, MainActivity::class.java))
                finish()
            }
        }
    }

    private fun validate(): Boolean {
        val email = binding.etEmail.text.toString().trim()
        val password = binding.etPassword.text.toString()

        var valid = true
        if (email.isEmpty()) {
            binding.emailLayout.error = getString(R.string.error_email_required)
            valid = false
        } else if (!EMAIL_PATTERN.matcher(email).matches()) {
            binding.emailLayout.error = getString(R.string.error_email_invalid)
            valid = false
        }
        if (password.isEmpty()) {
            binding.passwordLayout.error = getString(R.string.error_password_required)
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
