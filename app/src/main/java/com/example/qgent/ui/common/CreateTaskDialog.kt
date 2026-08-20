package com.example.qgent.ui.common

import android.content.Context
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.view.isVisible
import com.example.qgent.R
import com.example.qgent.data.model.GroupDto
import com.example.qgent.data.model.TaskCreateRequest
import com.example.qgent.data.model.TaskTriggerRequest
import com.example.qgent.data.repository.GitHubRepository
import com.example.qgent.data.repository.TaskRepository
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * 新建任务弹窗：任务页与群聊共用。
 * - 标题 + 需求描述 + 项目绑定仓库多选（§6 project_repositories.id）；
 * - 可选「分支群」选择器：仅列出已加入的 ACTIVE REQUIREMENT 群（任务页用；
 *   群聊传 showGroupSelector=false，直接用当前群，不显示选择器）；
 * - 续作引用（quotingDiff）时不加载仓库、允许空需求（服务端复用源 Workspace）。
 *
 * 提交通过 [onSubmit] 回调，由调用方决定 createTask 或 triggerTask。
 */
class CreateTaskDialog(
    private val context: Context,
    private val projectId: String,
    private val taskRepo: TaskRepository,
    private val githubRepo: GitHubRepository,
    private val scope: CoroutineScope,
    private val candidateGroups: List<GroupDto>,
    private val initialGroupId: String?,
    private val showGroupSelector: Boolean,
    private val prefillTitle: String = "",
    private val prefillRequirement: String = "",
    private val quotingDiff: Boolean = false,
    private val onSubmit: (projectId: String, groupId: String, title: String, requirement: String, repoIds: List<String>, baseRef: String?) -> Unit
) {

    private var selectedGroupId: String = initialGroupId ?: candidateGroups.firstOrNull()?.id.orEmpty()

    fun show() {
        val binding = com.example.qgent.databinding.DialogCreateTaskBinding.inflate(android.view.LayoutInflater.from(context))
        val etTitle = binding.etTitle
        val etRequirement = binding.etRequirement
        val container = binding.root
        etTitle.setText(prefillTitle)
        etRequirement.setText(prefillRequirement)

        // 分支群选择器：仅任务页显示（群聊直接用当前群）
        if (showGroupSelector) {
            binding.tvGroupLabel.isVisible = true
            if (candidateGroups.isEmpty()) {
                binding.containerGroups.addView(android.widget.TextView(context).apply {
                    text = context.getString(R.string.start_task_group_empty)
                    textSize = 13f
                    setTextColor(context.getColor(R.color.text_secondary))
                })
            } else {
                val groupChecks = candidateGroups.map { group ->
                    CheckBox(context).apply {
                        text = group.title
                        textSize = 14f
                        tag = group.id
                        isChecked = group.id == selectedGroupId
                    }
                }
                groupChecks.forEach { cb ->
                    cb.setOnCheckedChangeListener { _, isChecked ->
                        if (isChecked) {
                            // 单选：勾选当前项时取消其余，保证只选一个分支群
                            selectedGroupId = cb.tag as String
                            groupChecks.filter { it !== cb }.forEach { it.isChecked = false }
                        }
                    }
                    binding.containerGroups.addView(cb)
                }
            }
        }

        // 引用 DIFF 卡：展示续作提示，不提供仓库多选（服务端复用源 Workspace）
        binding.tvRepoLabel.text = if (quotingDiff) {
            context.getString(R.string.start_task_quoting_diff_hint)
        } else {
            context.getString(R.string.manage_repositories)
        }
        val repoChecks = mutableListOf<CheckBox>()

        // 加载项目绑定仓库；续作引用时不加载
        val repoBranchMap = mutableMapOf<String, String>()   // repoId -> defaultBranch
        val dialog = MaterialAlertDialogBuilder(context)
            .setTitle(R.string.start_task_title)
            .setView(container)
            .setNegativeButton(R.string.cancel, null)
            // 不在这里设 PositiveButton 的默认点击行为：按钮在 show() 时才创建，
            // 必须在 OnShowListener 里取并自定义，否则默认点击即 dismiss（校验失败也会关弹窗）
            .setPositiveButton(R.string.confirm, null)
            .create()

        dialog.setOnShowListener {
            val positive = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            // 校验通过才提交并关闭；校验失败 toast 并保持弹窗打开，让用户修正
            positive.setOnClickListener {
                val title = etTitle.text.toString().trim()
                val requirement = etRequirement.text.toString().trim()
                val repoIds = repoChecks.filter { it.isChecked }.map { it.tag as String }
                // baseRef 取仓库默认分支（清单二：不要写死或留空）
                val baseRef = repoIds.firstOrNull()?.let { repoBranchMap[it] }
                val valid = when {
                    title.isEmpty() -> {
                        Toast.makeText(context, R.string.start_task_name_required, Toast.LENGTH_SHORT).show()
                        false
                    }
                    !quotingDiff && requirement.isEmpty() -> {
                        Toast.makeText(context, R.string.start_task_requirement_required, Toast.LENGTH_SHORT).show()
                        false
                    }
                    !quotingDiff && repoIds.isEmpty() -> {
                        Toast.makeText(context, R.string.start_task_repo_required, Toast.LENGTH_SHORT).show()
                        false
                    }
                    selectedGroupId.isEmpty() -> {
                        Toast.makeText(context, R.string.start_task_group_required, Toast.LENGTH_SHORT).show()
                        false
                    }
                    else -> true
                }
                if (valid) {
                    onSubmit(projectId, selectedGroupId, title, requirement, repoIds, baseRef)
                    dialog.dismiss()
                }
            }
            // 非续作需加载仓库：加载完成前禁用确认；失败 toast 真实原因。
            if (!quotingDiff) {
                positive.isEnabled = false
                scope.launch {
                    githubRepo.getProjectRepositories(projectId)
                        .onSuccess { repos ->
                            if (repos.isEmpty()) {
                                binding.tvRepoLabel.text = context.getString(R.string.start_task_repo_required)
                            } else {
                                repos.forEach { repo ->
                                    val cb = CheckBox(context).apply {
                                        text = repo.displayName
                                        textSize = 14f
                                        tag = repo.id
                                        isChecked = repos.size == 1
                                    }
                                    repoBranchMap[repo.id] = repo.defaultBranch
                                    repoChecks.add(cb)
                                    binding.containerRepos.addView(cb)
                                }
                            }
                            positive.isEnabled = repos.isNotEmpty()
                        }
                        .onFailure { e ->
                            binding.tvRepoLabel.text = context.getString(R.string.start_task_repo_load_failed)
                            Toast.makeText(context, "加载仓库失败：${e.message}", Toast.LENGTH_LONG).show()
                        }
                }
            }
        }
        dialog.show()
    }

    companion object {
        /** 创建任务（§11.3 TaskCreateRequest：requirementGroupId 必填，任务页/群聊 + 菜单通用） */
        fun create(
            taskRepo: TaskRepository,
            scope: CoroutineScope,
            projectId: String,
            groupId: String,
            title: String,
            requirement: String,
            repoIds: List<String>,
            baseRef: String?,
            context: Context
        ) {
            scope.launch {
                taskRepo.createTask(
                    projectId,
                    TaskCreateRequest(
                        requirementGroupId = groupId,
                        title = title,
                        requirement = requirement,
                        repositoryIds = repoIds,
                        baseRef = baseRef
                    ),
                    UUID.randomUUID().toString()
                ).onSuccess {
                    Toast.makeText(context, R.string.start_task_success, Toast.LENGTH_LONG).show()
                }.onFailure { e ->
                    val rid = if (e is com.example.qgent.data.model.ApiException && e.code.startsWith("HTTP_500")) {
                        e.requestId?.let { "\nrequestId: $it" }.orEmpty()
                    } else {
                        ""
                    }
                    Toast.makeText(context, "${context.getString(R.string.start_task_failed)}：${e.message}$rid", Toast.LENGTH_LONG).show()
                }
            }
        }

        /** 从已发送群消息显式触发 Task（§7 trigger-task；messageId 必填） */
        fun triggerFromMessage(
            taskRepo: TaskRepository,
            scope: CoroutineScope,
            projectId: String,
            groupId: String,
            messageId: String,
            title: String,
            requirement: String,
            repoIds: List<String>,
            baseRef: String?,
            context: Context
        ) {
            scope.launch {
                taskRepo.triggerTask(
                    projectId, groupId, messageId,
                    TaskTriggerRequest(
                        title = title,
                        requirement = requirement.ifEmpty { null },
                        repositoryIds = repoIds.ifEmpty { null },
                        baseRef = baseRef
                    ),
                    UUID.randomUUID().toString()
                ).onSuccess {
                    Toast.makeText(context, R.string.start_task_success, Toast.LENGTH_LONG).show()
                }.onFailure { e ->
                    val rid = if (e is com.example.qgent.data.model.ApiException && e.code.startsWith("HTTP_500")) {
                        e.requestId?.let { "\nrequestId: $it" }.orEmpty()
                    } else {
                        ""
                    }
                    Toast.makeText(context, "${context.getString(R.string.start_task_failed)}：${e.message}$rid", Toast.LENGTH_LONG).show()
                }
            }
        }
    }
}
