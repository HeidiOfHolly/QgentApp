package com.example.qgent.ui.chat

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.example.qgent.R
import com.example.qgent.databinding.ItemDiffLineBinding
import com.example.qgent.model.DiffLine
import com.example.qgent.model.DiffLineType

class DiffLineAdapter(
    private var lines: List<DiffLine>
) : RecyclerView.Adapter<DiffLineAdapter.VH>() {

    fun submitList(newLines: List<DiffLine>) {
        lines = newLines
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(ItemDiffLineBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(lines[position])

    override fun getItemCount(): Int = lines.size

    class VH(private val binding: ItemDiffLineBinding) : RecyclerView.ViewHolder(binding.root) {

        fun bind(line: DiffLine) {
            val context = binding.root.context
            binding.tvSign.text = when (line.type) {
                DiffLineType.ADD -> "+"
                DiffLineType.DELETE -> "-"
                DiffLineType.CONTEXT -> " "
            }
            binding.tvSign.setTextColor(ContextCompat.getColor(context, when (line.type) {
                DiffLineType.ADD -> R.color.diff_add_fg
                DiffLineType.DELETE -> R.color.diff_del_fg
                DiffLineType.CONTEXT -> R.color.diff_line_no
            }))
            binding.tvCode.text = line.text
            binding.root.setBackgroundColor(ContextCompat.getColor(context, when (line.type) {
                DiffLineType.ADD -> R.color.diff_add_bg
                DiffLineType.DELETE -> R.color.diff_del_bg
                DiffLineType.CONTEXT -> android.R.color.white
            }))
        }
    }
}
