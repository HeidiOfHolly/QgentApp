package com.example.qgent.ui.search

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.qgent.R
import com.example.qgent.databinding.FragmentSearchBinding
import com.example.qgent.ui.chat.ChatListAdapter
import com.example.qgent.viewmodel.MainViewModel

class SearchFragment : Fragment() {

    private var _binding: FragmentSearchBinding? = null
    private val binding get() = _binding!!
    private val mainViewModel: MainViewModel by activityViewModels()
    private val searchAdapter by lazy {
        ChatListAdapter(
            items = emptyList(),
            onGroupClick = { group ->
                findNavController().navigate(
                    R.id.action_search_to_chatDetail,
                    androidx.core.os.bundleOf("groupName" to group.name, "groupId" to group.id)
                )
            }
        )
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSearchBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.btnSearchBack.setOnClickListener { findNavController().popBackStack() }
        binding.tvSearchCancel.setOnClickListener { findNavController().popBackStack() }

        // 清空搜索历史
        binding.btnClearHistory.setOnClickListener {
            binding.chipGroupHistory.removeAllViews()
            binding.tvHistoryEmpty.isVisible = true
        }

        // 搜索结果列表
        binding.rvSearchResults.layoutManager = LinearLayoutManager(requireContext())
        binding.rvSearchResults.addItemDecoration(
            DividerItemDecoration(requireContext(), DividerItemDecoration.VERTICAL)
        )
        binding.rvSearchResults.adapter = searchAdapter

        // 键盘搜索键 → 执行搜索
        binding.etSearch.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                performSearch()
                true
            } else false
        }

        // 自动聚焦
        binding.etSearch.requestFocus()
        binding.root.post {
            val imm = requireContext()
                .getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showSoftInput(binding.etSearch, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    private fun performSearch() {
        val query = binding.etSearch.text.toString().trim()
        if (query.isEmpty()) return

        // 收起键盘
        val imm = requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(binding.etSearch.windowToken, 0)

        // 从 ViewModel 拿当前项目的群聊列表，按群名模糊匹配
        val allGroups = mainViewModel.groups.value ?: emptyList()
        val results = allGroups.filter { it.name.contains(query, ignoreCase = true) }

        // 隐藏历史区域，展示搜索结果
        binding.historyContainer.isVisible = false
        binding.rvSearchResults.isVisible = true
        binding.tvNoResults.isVisible = results.isEmpty()
        searchAdapter.submitList(results)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
