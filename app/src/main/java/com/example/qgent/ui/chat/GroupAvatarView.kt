package com.example.qgent.ui.chat

import android.content.Context
import android.graphics.Color
import android.util.AttributeSet
import android.view.ViewGroup
import android.widget.ImageView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.model.GlideUrl
import com.bumptech.glide.load.model.LazyHeaders
import com.example.qgent.R
import com.example.qgent.data.SessionStore
import com.example.qgent.data.api.RetrofitClient

/**
 * 群头像（成员拼图，类微信群）：最多 9 个成员头像按网格排列——
 * 1 个铺满、2~4 个 2×2、5~9 个 3×3。
 * 格子统一白底（空位也是白色格子），格子间留 1dp 缝隙透出父背景，
 * 与列表行背景区分出「边框」感；无头像成员回退默认占位图。
 * 头像接口需 Authorization 头（附件鉴权），Glide 加载带 token。
 */
class GroupAvatarView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : ViewGroup(context, attrs) {

    private var avatars: List<String> = emptyList()

    /** 设置成员头像 URL 列表（自动取前 9 个并重建网格） */
    fun setAvatars(urls: List<String>) {
        avatars = urls.filter { it.isNotBlank() }.take(MAX_AVATARS)
        rebuild()
    }

    private fun rebuild() {
        removeAllViews()
        val count = avatars.size.coerceIn(1, MAX_AVATARS) // 至少 1 格
        val gapPx = gapPx()
        (0 until count).forEach { index ->
            val iv = ImageView(context).apply {
                // 格子白底：空位也是白色格子，与列表行背景区分（边框感）
                setBackgroundColor(Color.WHITE)
                setImageDrawable(null)
            }
            addView(iv)
            if (index < avatars.size) {
                Glide.with(iv)
                    .load(authedUrl(avatars[index]))
                    .centerCrop()
                    .placeholder(R.drawable.ic_avatar_default)
                    .error(R.drawable.ic_avatar_default)
                    .into(iv)
            }
        }
        requestLayout()
    }

    private fun authedUrl(url: String): GlideUrl {
        val token = SessionStore.accessToken()
        val headers = LazyHeaders.Builder().apply {
            if (!token.isNullOrEmpty()) addHeader("Authorization", "Bearer $token")
        }.build()
        return GlideUrl(RetrofitClient.resolveMediaUrl(url), headers)
    }

    private fun gapPx(): Int = (1 * resources.displayMetrics.density).toInt()

    private fun gridSize(): Int = when (childCount) {
        1 -> 1
        in 2..4 -> 2
        else -> GRID
    }

    private fun cellSize(): Int {
        val grid = gridSize()
        return when (grid) {
            1 -> width
            2 -> (width - gapPx()) / 2
            else -> (width - gapPx() * (GRID - 1)) / GRID
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val size = resolveSize(DEFAULT_SIZE, widthMeasureSpec)
        setMeasuredDimension(size, size)
        val cell = cellSize()
        (0 until childCount).forEach { index ->
            getChildAt(index).measure(
                MeasureSpec.makeMeasureSpec(cell, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(cell, MeasureSpec.EXACTLY)
            )
        }
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val grid = gridSize()
        val cell = cellSize()
        val gap = gapPx()
        (0 until childCount).forEach { index ->
            val row = index / grid
            val col = index % grid
            val child = getChildAt(index)
            val left = col * (cell + gap)
            val top = row * (cell + gap)
            child.layout(left, top, left + cell, top + cell)
        }
    }

    companion object {
        private const val GRID = 3
        private const val MAX_AVATARS = 9
        private const val DEFAULT_SIZE = 132 // px 兜底，实际由父容器约束
    }
}
