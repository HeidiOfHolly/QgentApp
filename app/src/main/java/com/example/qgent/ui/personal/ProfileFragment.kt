package com.example.qgent.ui.personal

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.example.qgent.R
import com.example.qgent.data.SessionStore
import com.example.qgent.databinding.FragmentProfileBinding
import com.example.qgent.ui.auth.LoginActivity

/** 个人信息页：展示当前登录用户，支持退出登录 */
class ProfileFragment : Fragment() {

    private var _binding: FragmentProfileBinding? = null
    private val binding get() = _binding!!

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
        binding.tvUserId.text = user?.id ?: ""

        // 退出登录：清空会话并回到登录页
        binding.btnLogout.setOnClickListener {
            SessionStore.clear()
            startActivity(Intent(requireContext(), LoginActivity::class.java))
            requireActivity().finish()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
