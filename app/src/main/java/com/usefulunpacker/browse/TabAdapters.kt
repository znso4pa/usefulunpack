package com.usefulunpacker

import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.Fragment

/**
 * Adapter for the horizontal tab strip. Each tab shows "窗口 N" plus a close
 * button (hidden when only one tab remains). Tapping a tab activates it.
 */
internal class TabStripAdapter(private val act: MainActivity) :
    androidx.recyclerview.widget.RecyclerView.Adapter<TabStripAdapter.VH>() {
    class VH(val root: LinearLayout, val badge: TextView) : androidx.recyclerview.widget.RecyclerView.ViewHolder(root)

    override fun getItemCount(): Int = act.tabs.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val root = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val pd = act.resources.getDimensionPixelSize(R.dimen.space_md)
            // 占满 tabList 高度：垂直方向去掉内边距并设为 MATCH_PARENT
            setPadding(pd, 0, (pd * 2 / 3), 0)
            setBackgroundColor(0x00000000)
            layoutParams = androidx.recyclerview.widget.RecyclerView.LayoutParams(
                androidx.recyclerview.widget.RecyclerView.LayoutParams.WRAP_CONTENT,
                androidx.recyclerview.widget.RecyclerView.LayoutParams.MATCH_PARENT
            )
        }
        // 标签图标，颜色在 onBind 中按激活状态统一着色
        val icon = ImageView(act).apply {
            setImageResource(R.drawable.ic_tab_pc)
            scaleType = ImageView.ScaleType.FIT_CENTER
            val sz = (20 * act.resources.displayMetrics.density).toInt()
            layoutParams = LinearLayout.LayoutParams(sz, sz).apply {
                rightMargin = act.resources.getDimensionPixelSize(R.dimen.space_xs)
            }
        }
        // 窗口标题文字：小字号（text_xs），紧挨图标。WRAP_CONTENT 宽度配合
        // maxWidth：超宽名字（历史数据等）在 96dp 处截断省略，不会把其他
        // 标签挤出屏幕。
        val label = TextView(act).apply {
            textSize = act.resources.getDimension(R.dimen.text_xs) / act.resources.displayMetrics.scaledDensity
            setPadding(0, 0, act.resources.getDimensionPixelSize(R.dimen.space_sm), 0)
            maxLines = 1
            maxWidth = (96 * act.resources.displayMetrics.density).toInt()
            ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
        }
        val close = UuIconButton(act).apply {
            setImageResource(R.drawable.ic_clear)
            contentDescription = act.getString(R.string.action_close)
        }

        // 选中项数量角标：小圆角背景 + 数字，默认隐藏
        val badge = TextView(act).apply {
            textSize = 9f
            setTextColor(0xFFFFFFFF.toInt())
            setPadding(
                (4 * act.resources.displayMetrics.density).toInt(),
                0,
                (4 * act.resources.displayMetrics.density).toInt(),
                0
            )
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(C["accent"]!!)
                cornerRadius = 8 * act.resources.displayMetrics.density
            }
            gravity = Gravity.CENTER
            visibility = View.GONE
        }
        val closeSz = (28 * act.resources.displayMetrics.density).toInt()
        root.addView(icon)
        root.addView(label, LinearLayout.LayoutParams(WRAP, WRAP))
        root.addView(badge, LinearLayout.LayoutParams(WRAP, WRAP).apply { marginStart = (2 * act.resources.displayMetrics.density).toInt() })
        root.addView(close, LinearLayout.LayoutParams(closeSz, closeSz))
        return VH(root, badge)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val tabs = act.tabs
        if (position >= tabs.size) {
            // Guard: unbind the close/root listeners so a stale holder can't
            // index tabs[position] out of bounds after a tab is removed.
            holder.root.setOnClickListener(null)
            holder.root.getChildAt(3).setOnClickListener(null)
            return
        }
        val icon = holder.root.getChildAt(0) as ImageView
        val label = holder.root.getChildAt(1) as TextView
        val close = holder.root.getChildAt(3) as UuIconButton
        val isActive = position == act.activeTabIndex
        val thisTab = tabs[position]
        // 图标颜色与其他图标统一：激活 accent，未激活 tertiary
        icon.setColorFilter(if (isActive) C["accent"]!! else C["tertiary"]!!)
        label.text = act.tabTitle(thisTab)
        label.setTextColor(if (isActive) C["accent"]!! else C["tertiary"]!!)
        // Active tab gets a RAISED (lighter) flat background — no stroke, no corner radius.
        holder.root.background = if (isActive) {
            android.graphics.drawable.ColorDrawable(C["surface_raised"]!!)
        } else {
            android.graphics.drawable.ColorDrawable(0x00000000)
        }
        close.visibility = if (tabs.size > 1) View.VISIBLE else View.GONE
        // 选中项角标：仅当该 tab 有选中项时显示数字
        val selCount = thisTab.multiSelected.size
        holder.badge.text = selCount.toString()
        holder.badge.visibility = if (selCount > 0) View.VISIBLE else View.GONE
        holder.root.setOnClickListener {
            act.activeTabIndex = position
            act.viewPager.currentItem = position
            act.tabAdapter.notifyDataSetChanged()
            act.updateTitle()
            act.updatePasteButton()
            act.syncMultiBar(act.activeTab)
        }
        holder.root.setOnLongClickListener { act.showTabRenameDialog(thisTab); true }
        close.setOnClickListener { act.closeTab(thisTab) }
    }
}

/** ViewPager2 adapter hosting one FolderFragment per tab slot (up to MAX_TABS). */
internal class TabPagerAdapter(private val act: MainActivity) :
    androidx.viewpager2.adapter.FragmentStateAdapter(act) {
    override fun getItemCount(): Int = act.tabs.size

    override fun createFragment(position: Int): Fragment {
        return FolderFragment.newInstance(position)
    }
}
