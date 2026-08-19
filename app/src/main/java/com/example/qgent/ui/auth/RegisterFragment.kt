package com.example.qgent.ui.auth

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import com.example.qgent.MainActivity
import com.example.qgent.QgentApp
import com.example.qgent.R
import com.example.qgent.data.SessionStore
import com.example.qgent.databinding.FragmentRegisterBinding
import java.util.regex.Pattern

class RegisterFragment : Fragment() {

    private var _binding: FragmentRegisterBinding? = null
    private val binding get() = _binding!!
    private val viewModel: AuthViewModel by viewModels {
        (requireActivity().application as QgentApp).container.authViewModelFactory
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentRegisterBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.ivBack.setOnClickListener { popBackToLogin() }
        binding.tvLoginLink.setOnClickListener { popBackToLogin() }

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

        // 验证码输入框（6 位数字）：错误提示随输入清除
        binding.etCode.doAfterTextChanged {
            if (binding.codeLayout.error != null) binding.codeLayout.error = null
        }

        // 发送验证码：先校验邮箱格式，成功后进入 60s 重发倒计时
        binding.btnSendCode.setOnClickListener {
            val email = binding.etEmail.text.toString().trim()
            if (email.isEmpty()) {
                binding.emailLayout.error = getString(R.string.error_email_required)
                return@setOnClickListener
            }
            if (!EMAIL_PATTERN.matcher(email).matches()) {
                binding.emailLayout.error = getString(R.string.error_email_invalid)
                return@setOnClickListener
            }
            viewModel.sendVerificationCode(email)
        }

        binding.btnRegister.setOnClickListener {
            if (validate()) {
                viewModel.register(
                    binding.etEmail.text.toString(),
                    binding.etDisplayName.text.toString(),
                    binding.etPassword.text.toString(),
                    binding.etCode.text.toString()
                )
            }
        }

        observeViewModel()
        observeCodeState()
    }

    /** 观察发送验证码状态：按钮文案/倒计时/错误提示 */
    private fun observeCodeState() {
        viewModel.codeState.observe(viewLifecycleOwner) { state ->
            binding.btnSendCode.isEnabled = state.retryInSeconds <= 0
            binding.btnSendCode.text = if (state.retryInSeconds > 0) {
                getString(R.string.register_resend_code, state.retryInSeconds)
            } else {
                getString(R.string.register_send_code)
            }
            if (state.sending) {
                binding.btnSendCode.isEnabled = false
                binding.btnSendCode.text = getString(R.string.register_sending_code)
            }
            state.error?.let {
                Toast.makeText(requireContext(), it, Toast.LENGTH_SHORT).show()
                viewModel.consumeCodeError()
            }
            if (state.sent && state.retryInSeconds == 60) {
                Toast.makeText(requireContext(), R.string.register_code_sent, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun popBackToLogin() {
        parentFragmentManager.popBackStack()
    }

    private fun observeViewModel() {
        viewModel.uiState.observe(viewLifecycleOwner) { state ->
            binding.btnRegister.isEnabled = !state.loading
            state.error?.let {
                Toast.makeText(requireContext(), it, Toast.LENGTH_SHORT).show()
                viewModel.consumeError()
            }
            if (state.success) {
                viewModel.consumeSuccess()
                SessionStore.saveRememberedEmail(binding.etEmail.text.toString())
                val name = SessionStore.user()?.displayName ?: ""
                Toast.makeText(
                    requireContext(),
                    getString(R.string.register_success, name),
                    Toast.LENGTH_SHORT
                ).show()
                startActivity(Intent(requireContext(), MainActivity::class.java))
                requireActivity().finish()
            }
        }
    }

    private fun validate(): Boolean {
        val email = binding.etEmail.text.toString().trim()
        val displayName = binding.etDisplayName.text.toString().trim()
        val password = binding.etPassword.text.toString()
        val confirm = binding.etConfirm.text.toString()
        val code = binding.etCode.text.toString().trim()

        var valid = true
        if (email.isEmpty()) {
            binding.emailLayout.error = getString(R.string.error_email_required)
            valid = false
        } else if (!EMAIL_PATTERN.matcher(email).matches()) {
            binding.emailLayout.error = getString(R.string.error_email_invalid)
            valid = false
        }
        // 验证码：必填且为 6 位数字
        if (code.isEmpty()) {
            binding.codeLayout.error = getString(R.string.error_code_required)
            valid = false
        } else if (!CODE_PATTERN.matcher(code).matches()) {
            binding.codeLayout.error = getString(R.string.error_code_invalid)
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

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private companion object {
        val EMAIL_PATTERN: Pattern = Pattern.compile(
            "^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$"
        )
        /** 验证码：6 位数字（v2.0.6 §11.2 长度固定 6） */
        val CODE_PATTERN: Pattern = Pattern.compile("^\\d{6}$")
    }
}
