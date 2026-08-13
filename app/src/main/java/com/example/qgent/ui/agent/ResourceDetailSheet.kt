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
import androidx.fragment.app.activityViewModels
import com.example.qgent.QgentApp
import com.example.qgent.R
import com.example.qgent.databinding.SheetResourceDetailBinding
import com.example.qgent.viewmodel.MainViewModel

class ResourceDetailSheet(
    private val name: String,
    private val description: String,
    private val isPending: Boolean,
    private val approveText: String = "已通过",
    private val rejectText: String = "已驳回"
) : DialogFragment() {

    private var _binding: SheetResourceDetailBinding? = null
    private val binding get() = _binding!!
    private val mainViewModel: MainViewModel by activityViewModels {
        (requireActivity().application as QgentApp).container.mainViewModelFactory
    }

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

        if (isPending) {
            binding.tvResourceType.isVisible = true
            binding.tvResourceType.text = "待审核"

            // 仅 owner 可操作审核
            if (mainViewModel.isProjectAdmin) {
                binding.reviewActions.isVisible = true
                binding.btnApprove.setOnClickListener { doApprove() }
                binding.btnReject.setOnClickListener { doReject() }
            }
        }
    }

    private fun doApprove() {
        Toast.makeText(requireContext(), approveText, Toast.LENGTH_SHORT).show()
        dismiss()
    }

    private fun doReject() {
        val input = EditText(requireContext())
        input.hint = "请输入驳回原因"
        input.setPadding(48, 32, 48, 32)

        AlertDialog.Builder(requireContext())
            .setTitle("驳回草稿")
            .setView(input)
            .setPositiveButton("确认") { _, _ ->
                val reason = input.text.toString().trim()
                Toast.makeText(
                    requireContext(),
                    if (reason.isEmpty()) rejectText else "已驳回：$reason",
                    Toast.LENGTH_SHORT
                ).show()
                dismiss()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        const val TAG = "ResourceDetailSheet"
    }
}
