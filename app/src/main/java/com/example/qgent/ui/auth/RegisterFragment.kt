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
                startActivity(Intent(requireContext(), TeamEntryActivity::class.java))
                requireActivity().finish()
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

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private companion object {
        val EMAIL_PATTERN: Pattern = Pattern.compile(
            "^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$"
        )
    }
}
