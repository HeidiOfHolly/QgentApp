package com.example.qgent.ui.auth

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import com.example.qgent.QgentApp
import com.example.qgent.R
import com.example.qgent.databinding.FragmentForgotPasswordBinding
import java.util.regex.Pattern

/**
 * 忘记密码页（§11.3）：输入注册邮箱 → 获取 6 位数字验证码（30 分钟有效、一次性）
 * → 填验证码 + 新密码提交；校验失败 422 INVALID_RESET_TOKEN。
 * 交互与注册页两步验证码流程一致。
 */
class ForgotPasswordFragment : Fragment() {

    private var _binding: FragmentForgotPasswordBinding? = null
    private val binding get() = _binding!!
    private val viewModel: AuthViewModel by viewModels {
        (requireActivity().application as QgentApp).container.authViewModelFactory
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentForgotPasswordBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.ivBack.setOnClickListener { popBackToLogin() }
        binding.tvLoginLink.setOnClickListener { popBackToLogin() }

        listOf(
            binding.emailLayout,
            binding.newPasswordLayout,
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
            viewModel.sendResetCode(email)
        }

        binding.btnReset.setOnClickListener {
            if (validate()) {
                viewModel.resetPassword(
                    binding.etCode.text.toString(),
                    binding.etNewPassword.text.toString()
                )
            }
        }

        observeCodeState()
        observeViewModel()
    }

    /** 观察发送验证码状态：按钮文案/倒计时/错误提示 */
    private fun observeCodeState() {
        viewModel.resetCodeState.observe(viewLifecycleOwner) { state ->
            binding.btnSendCode.isEnabled = state.retryInSeconds <= 0
            binding.btnSendCode.text = if (state.retryInSeconds > 0) {
                getString(R.string.forgot_password_resend_code, state.retryInSeconds)
            } else {
                getString(R.string.forgot_password_send_code)
            }
            if (state.sending) {
                binding.btnSendCode.isEnabled = false
                binding.btnSendCode.text = getString(R.string.forgot_password_sending_code)
            }
            state.error?.let {
                Toast.makeText(requireContext(), it, Toast.LENGTH_SHORT).show()
                viewModel.consumeResetCodeError()
            }
            if (state.sent && state.retryInSeconds == 60) {
                Toast.makeText(requireContext(), R.string.forgot_password_code_sent, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun observeViewModel() {
        viewModel.resetUiState.observe(viewLifecycleOwner) { state ->
            binding.btnReset.isEnabled = !state.loading
            binding.btnReset.text = getString(
                if (state.loading) R.string.forgot_password_resetting else R.string.forgot_password_button
            )
            state.error?.let {
                Toast.makeText(requireContext(), it, Toast.LENGTH_LONG).show()
                viewModel.consumeResetError()
            }
            if (state.success) {
                viewModel.consumeResetSuccess()
                Toast.makeText(requireContext(), R.string.forgot_password_success, Toast.LENGTH_LONG).show()
                popBackToLogin()
            }
        }
    }

    private fun popBackToLogin() {
        parentFragmentManager.popBackStack()
    }

    private fun validate(): Boolean {
        val email = binding.etEmail.text.toString().trim()
        val code = binding.etCode.text.toString().trim()
        val newPassword = binding.etNewPassword.text.toString()
        val confirm = binding.etConfirm.text.toString()

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
        if (newPassword.length < 6) {
            binding.newPasswordLayout.error = getString(R.string.error_password_short)
            valid = false
        }
        if (confirm != newPassword) {
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
        /** 验证码：6 位数字（§11.3 长度固定 6） */
        val CODE_PATTERN: Pattern = Pattern.compile("^\\d{6}$")
    }
}
