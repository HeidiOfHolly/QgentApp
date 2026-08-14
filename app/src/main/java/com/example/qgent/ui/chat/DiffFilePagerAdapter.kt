package com.example.qgent.ui.chat

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.qgent.databinding.ItemDiffPageBinding
import com.example.qgent.model.DiffFile

class DiffFilePagerAdapter(
    private var files: List<DiffFile> = emptyList()
) : RecyclerView.Adapter<DiffFilePagerAdapter.VH>() {

    fun submitList(newFiles: List<DiffFile>) {
        files = newFiles
        notifyDataSetChanged()
    }

    fun fileAt(position: Int): DiffFile? = files.getOrNull(position)

    val count: Int get() = files.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(ItemDiffPageBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(files[position])

    override fun getItemCount(): Int = files.size

    class VH(private val binding: ItemDiffPageBinding) : RecyclerView.ViewHolder(binding.root) {

        fun bind(file: DiffFile) {
            binding.rvDiffLines.layoutManager = LinearLayoutManager(binding.root.context)
            binding.rvDiffLines.adapter = DiffLineAdapter(file.lines)
        }
    }
}
