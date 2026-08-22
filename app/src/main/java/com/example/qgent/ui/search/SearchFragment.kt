package com.example.qgent.ui.search

import android.content.Context
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import androidx.core.os.bundleOf
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.qgent.QgentApp
import com.example.qgent.R
import com.example.qgent.databinding.FragmentSearchBinding
import com.example.qgent.model.ChatMessage
import com.example.qgent.ui.search.LocalSearchAdapter.Row
import com.example.qgent.viewmodel.MainViewModel
import com.google.android.material.chip.Chip
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 搜索页：本地搜索（不依赖后端 /search）——
 * 1) 群名匹配：当前项目群列表（内存）
 * 2) 消息内容匹配：各群 Room 本地缓存消息（打开过的群才有缓存）
 * 点击群结果 → 进群；点击消息结果 → 进群并滚动高亮到该消息（targetMessageId）。
 * 后端 /search 实现后如需跨项目搜索可再切回接口（接口与降级逻辑保留在数据层/旧代码）。
 */
class SearchFragment : Fragment() {

    private var _binding: FragmentSearchBinding? = null
    private val binding get() = _binding!!

    private val mainViewModel: MainViewModel by activityViewModels {
        (requireActivity().application as QgentApp).container.mainViewModelFactory
    }
    private val searchAdapter by lazy {
        LocalSearchAdapter(
            onGroupClick = { group -> openGroup(group.id, group.name, null) },
            onMessageClick = { groupName, groupId, message ->
                openGroup(groupId, groupName, message.id)
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

        // 清空搜索历史（本地 SharedPreferences）
        binding.btnClearHistory.setOnClickListener {
            historyPrefs().edit().remove(KEY_HISTORY).apply()
            renderHistory(emptyList())
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

        // 加载本地搜索历史
        renderHistory(loadHistory())

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

        // 记录搜索历史并刷新
        saveHistory(query)
        renderHistory(loadHistory())

        // 隐藏历史区域，展示搜索结果
        binding.historyContainer.isVisible = false
        binding.rvSearchResults.isVisible = true

        viewLifecycleOwner.lifecycleScope.launch {
            val rows = withContext(Dispatchers.IO) { localSearch(query) }
            Log.d("GroupSearch", "local search '$query' -> ${rows.size} rows")
            binding.tvNoResults.isVisible = rows.isEmpty()
            searchAdapter.submitList(rows)
        }
    }

    /** 本地搜索：群名匹配（内存）+ 消息内容匹配（Room 缓存），结果：群行在前，消息按时间倒序 */
    private suspend fun localSearch(query: String): List<Row> {
        val groups = mainViewModel.groups.value.orEmpty()
        val groupHits = groups.filter { it.name.contains(query, ignoreCase = true) }
            .map { Row.GroupHit(it) }

        val messageHits = groups.flatMap { group ->
            (requireActivity().application as QgentApp).container.messageCache
                .load(group.id)
                .asSequence()
                .filter { it.content.contains(query, ignoreCase = true) }
                .sortedByDescending { it.timestamp }
                .take(MAX_HITS_PER_GROUP)
                .map { Row.MessageHit(group.name, group.id, it) }
        }.sortedByDescending { (it as? Row.MessageHit)?.message?.timestamp ?: 0L }

        return (groupHits + messageHits).take(MAX_TOTAL_HITS)
    }

    /** 跳群聊：消息结果带 targetMessageId 滚动高亮（复用通知直达定位机制） */
    private fun openGroup(groupId: String, groupName: String, targetMessageId: String?) {
        val bundle = bundleOf("groupName" to groupName, "groupId" to groupId)
        if (!targetMessageId.isNullOrBlank()) {
            bundle.putString("targetMessageId", targetMessageId)
        }
        findNavController().navigate(R.id.action_search_to_chatDetail, bundle)
    }

    // ── 本地搜索历史（SharedPreferences，最近最多 8 条，去重置顶） ──

    private fun historyPrefs() =
        requireContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun loadHistory(): List<String> =
        historyPrefs().getStringSet(KEY_HISTORY, emptySet()).orEmpty().toList()

    private fun saveHistory(query: String) {
        val cur = loadHistory().toMutableList()
        cur.remove(query)
        val updated = (listOf(query) + cur).take(MAX_HISTORY)
        historyPrefs().edit().putStringSet(KEY_HISTORY, updated.toSet()).apply()
    }

    private fun renderHistory(history: List<String>) {
        binding.chipGroupHistory.removeAllViews()
        binding.tvHistoryEmpty.isVisible = history.isEmpty()
        history.forEach { word ->
            val chip = Chip(requireContext()).apply {
                text = word
                isClickable = true
                setOnClickListener {
                    binding.etSearch.setText(word)
                    performSearch()
                }
            }
            binding.chipGroupHistory.addView(chip)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        private const val PREFS_NAME = "search_history"
        private const val KEY_HISTORY = "history"
        private const val MAX_HISTORY = 8
        private const val MAX_HITS_PER_GROUP = 20
        private const val MAX_TOTAL_HITS = 100
    }
}
