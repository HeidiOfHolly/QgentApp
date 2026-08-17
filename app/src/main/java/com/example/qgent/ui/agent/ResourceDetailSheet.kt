package com.example.qgent.ui.agent

import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.view.isVisible
import androidx.fragment.app.DialogFragment
import com.example.qgent.R
import com.example.qgent.databinding.SheetResourceDetailBinding

/**
 * Memory/Skill 详情弹窗：展示内容（类型标签 + 标题 + 全文）。
 * 待审核条目且传入 [onApprove]/[onReject] 回调时，卡片右上角显示 通过/拒绝 按钮；
 * 已共享条目且传入 [onDelete] 回调时，底部显示 删除 按钮；
 * 均未传入时只读展示。
 */
class ResourceDetailSheet(
    private val name: String,
    private val description: String,
    private val isPending: Boolean,
    private val onApprove: (() -> Unit)? = null,
    private val onReject: (() -> Unit)? = null,
    private val onDelete: (() -> Unit)? = null
) : DialogFragment() {

    private var _binding: SheetResourceDetailBinding? = null
    private val binding get() = _binding!!

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val dialog = super.onCreateDialog(savedInstanceState)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        return dialog
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = SheetResourceDetailBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.setLayout(
            (resources.displayMetrics.widthPixels * 0.85).toInt(),
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.tvDetailName.text = name
        binding.tvDetailDesc.text = description

        binding.tvResourceType.isVisible = true
        binding.tvResourceType.text = if (isPending) "待审核" else "已共享"

        // 待审核 + 提供审核回调 → 右上角显示 通过/拒绝
        if (isPending && onApprove != null && onReject != null) {
            binding.reviewActions.isVisible = true
            binding.btnApprove.setOnClickListener {
                onApprove()
                dismiss()
            }
            binding.btnReject.setOnClickListener {
                // 驳回前询问原因（可留空）
                val input = EditText(requireContext())
                input.hint = "请输入驳回原因（可选）"
                input.setPadding(48, 32, 48, 32)
                AlertDialog.Builder(requireContext())
                    .setTitle("驳回")
                    .setView(input)
                    .setPositiveButton("确认驳回") { _, _ ->
                        val reason = input.text.toString().trim()
                        Toast.makeText(
                            requireContext(),
                            if (reason.isEmpty()) "已驳回" else "已驳回：$reason",
                            Toast.LENGTH_SHORT
                        ).show()
                        onReject()
                        dismiss()
                    }
                    .setNegativeButton("取消", null)
                    .show()
            }
        } else {
            binding.reviewActions.isVisible = false
        }

        // 已共享资源且提供删除回调 → 底部显示 删除 按钮（删除前二次确认）
        binding.btnDelete.isVisible = onDelete != null
        binding.btnDelete.setOnClickListener {
            AlertDialog.Builder(requireContext())
                .setTitle(R.string.resource_delete)
                .setMessage(R.string.resource_delete_confirm)
                .setPositiveButton(R.string.resource_delete) { _, _ ->
                    onDelete?.invoke()
                    dismiss()
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        const val TAG = "ResourceDetailSheet"
    }
}
