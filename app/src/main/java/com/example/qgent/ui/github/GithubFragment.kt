package com.example.qgent.ui.github

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.os.bundleOf
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.GridLayoutManager
import com.example.qgent.MainActivity
import com.example.qgent.QgentApp
import com.example.qgent.R
import com.example.qgent.databinding.FragmentGithubBinding
import com.example.qgent.viewmodel.MainViewModel

/** GitHub 页：网格展示团队仓库概况，列表项使用 item_team_github */
class GithubFragment : Fragment() {

    private var _binding: FragmentGithubBinding? = null
    private val binding get() = _binding!!
    private val mainViewModel: MainViewModel by activityViewModels {
        (requireActivity().application as QgentApp).container.mainViewModelFactory
    }

    private val adapter = GithubTeamAdapter { team ->
        findNavController().navigate(
            R.id.githubAuthorizeFragment,
            bundleOf("teamId" to team.id, "teamName" to team.name)
        )
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentGithubBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // 头像 → 打开个人中心抽屉（与群聊列表页一致）
        binding.btnAvatar.setOnClickListener {
            (activity as? MainActivity)?.openDrawer()
        }

        binding.rvGithubTeams.layoutManager = GridLayoutManager(requireContext(), 2)
        binding.rvGithubTeams.adapter = adapter

        mainViewModel.teamDtos.observe(viewLifecycleOwner) { adapter.submitList(it) }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
