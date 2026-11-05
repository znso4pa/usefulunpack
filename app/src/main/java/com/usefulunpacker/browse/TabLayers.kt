package com.usefulunpacker

import android.view.View

/**
 * 图层开关的**唯一入口** —— 「切层」只在这里发生。
 *
 * ## 为什么要有这个函数
 *
 * 这一族 bug（hub 盖着列表、预览和列表同时可见、壁纸被挡）反复出现的根因是
 * **可见性不是派生状态**（不变式 8）：`homeVisible` / `previewActive` 两个状态，加上
 * `homeRoot` / `panel` / `previewRoot` / `pathBar` 四个 visibility，各写各的。
 * `showHome` / `enterBrowserAt` 会配平，但任何新路径都能绕过它们直接 `setVisibility`
 * —— 「主页搜完不跳转」就是 `navTab` 只换列表、完全不碰这四个视图。
 *
 * 现在：**状态只由 showHome / enterBrowserAt / navTab / syncPreview 改，视图只由
 * [applyLayer] 改**。状态与可见性之间只有这一处映射，漏配平在结构上不可能发生。
 * 自查：`grep -rn "homeRoot?.visibility\|panel?.visibility\|previewRoot.visibility\|pathBar?.visibility"`
 * 应当只命中本文件。
 *
 * ## 层序与映射
 *
 * `folder_view.xml` 的声明顺序就是 z 序（后声明 = 更上层）：
 * `pathBar → panel(列表) → bottomBar → fabExtract → previewRoot → homeRoot`
 *
 * | 层     | homeRoot | panel   | previewRoot | pathBar |
 * |--------|----------|---------|-------------|---------|
 * | 主界面 | VISIBLE  | GONE    | GONE        | GONE    |
 * | 浏览器 | GONE     | VISIBLE | GONE        | VISIBLE |
 * | 预览   | GONE     | VISIBLE | VISIBLE     | VISIBLE |
 *
 * ⚠️ 预览态**不隐藏 panel**：宽屏是左右双栏（`applyWidePreviewPane`），列表在左、
 * 预览在右；竖屏时 previewRoot 自己盖住列表。把 panel 藏掉会打断宽屏双栏。
 *
 * ⚠️ **主界面优先**：`homeVisible` 为真时即使 `previewActive` 还留着也不让预览透出来
 * （hub 是「盖在上面」的一层，重建视图时预览状态还要能恢复，所以不能顺手清状态）。
 */
internal fun MainActivity.applyLayer(tab: TabState) {
    if (!tab.viewsBound) return
    val home = tab.homeVisible
    val preview = !home && tab.previewActive
    tab.homeRoot?.visibility = vis(home)
    tab.panel?.visibility = vis(!home)
    tab.previewRoot.visibility = vis(preview)
    tab.pathBar?.visibility = vis(!home)
    // 选择态 UI（底栏 / FAB / 批量条）只在浏览器层有意义：进 hub 或预览时收掉，
    // 否则回到浏览器会带着上一次的选中残留。
    if (home || preview) {
        tab.bottomBar.visibility = View.GONE
        tab.fabExtract.visibility = View.GONE
        tab.batchBar?.visibility = View.GONE
    }
}

private fun vis(show: Boolean): Int = if (show) View.VISIBLE else View.GONE
