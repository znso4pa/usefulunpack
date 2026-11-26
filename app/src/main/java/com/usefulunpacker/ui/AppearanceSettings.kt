package com.usefulunpacker

import android.net.Uri
import android.view.View
import android.widget.*
internal fun MainActivity.showUISettings() {
    val act = this
        // Background image toggle row
        val hasBg = prefs.getString("bg_image_uri", null) != null
        val tvBgInfo = TextView(this).apply {
            text = if (hasBg) getString(R.string.bg_set) else getString(R.string.bg_not_set)
            setTextColor(C["tertiary_light"]!!); setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, resources.getDimension(R.dimen.text_md))
        }
        val btnPickBg = com.usefulunpacker.UuButton(this).apply {
            text = if (hasBg) getString(R.string.bg_change) else getString(R.string.bg_pick)
        }
        val btnClearBg = com.usefulunpacker.UuButton(this).apply {
            actionStyle = ActionStyle.DANGER
            text = getString(R.string.bg_clear)
            visibility = if (hasBg) View.VISIBLE else View.GONE
        }
        val bgRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(resources.getDimensionPixelSize(R.dimen.space_lg), 0, resources.getDimensionPixelSize(R.dimen.space_lg), resources.getDimensionPixelSize(R.dimen.space_sm))
            addView(tvBgInfo, LinearLayout.LayoutParams(0, WRAP, 1f))
            addView(btnClearBg)
            addView(btnPickBg, LinearLayout.LayoutParams(WRAP, WRAP).apply { setMargins(8, 0, 0, 0) })
        }

        val curAlpha = prefs.getInt("bg_image_alpha", 20)
        val tvAlpha = TextView(act).apply {
            text = getString(R.string.bg_alpha, curAlpha); setTextColor(C["tertiary"]!!); setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, resources.getDimension(R.dimen.text_sm))
            setPadding(resources.getDimensionPixelSize(R.dimen.space_xl), resources.getDimensionPixelSize(R.dimen.space_xs), resources.getDimensionPixelSize(R.dimen.space_lg), 0)
        }
        val seekAlpha = com.usefulunpacker.UuSlider(act).apply {
            max = 100; progress = curAlpha
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) { tvAlpha.text = getString(R.string.bg_alpha, p) }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        }
        val body = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(act).apply {
                text = getString(R.string.settings_bg_image); setTextColor(C["tertiary_light"]!!); setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, resources.getDimension(R.dimen.text_sm))
                setPadding(resources.getDimensionPixelSize(R.dimen.space_lg), resources.getDimensionPixelSize(R.dimen.space_md), resources.getDimensionPixelSize(R.dimen.space_lg), 0)
            })
            addView(bgRow)
            addView(tvAlpha)
            addView(seekAlpha)
        }

        btnPickBg.setOnClickListener { bgImageLauncher?.launch("image/*") }
        btnClearBg.setOnClickListener {
            prefs.edit().remove("bg_image_uri").apply()
            // Drop the master so a later rotation cannot resurrect the wallpaper,
            // and detach the listener that would re-fit it.
            bgRootListener?.let { findViewById<View>(R.id.root)?.removeOnLayoutChangeListener(it) }
            bgRootListener = null
            bgSourceBitmap?.recycle()
            bgSourceBitmap = null
            bgLastW = -1; bgLastH = -1
            // root 在 activity_main.xml 里的底是 bg_file_list（不是 bg_surface）
            findViewById<View>(R.id.root)?.setBackgroundResource(R.color.bg_file_list)
            restoreBackdropInTree(findViewById(R.id.root) ?: return@setOnClickListener)
            // hub 页面那层也要跟着回到实底（虽然和 homeRoot 的 XML 底同色，不会看出差别，
            // 但让两层保持一致，别留一个「透明但被同色挡着」的隐性状态）。
            refreshHubPageBg()
            window?.statusBarColor = 0xBB000000.toInt()
            tvBgInfo.text = getString(R.string.bg_not_set)
            btnClearBg.visibility = View.GONE
        }

        com.usefulunpacker.UuDialogBuilder(this)
            .setTitle(getString(R.string.settings_ui))
            .setView(com.usefulunpacker.UuScrollView(this).apply { addView(body) })
            .setPositiveButton(getString(R.string.action_confirm)) { _, _ ->
                prefs.edit().putInt("bg_image_alpha", seekAlpha.progress).apply()
                prefs.getString("bg_image_uri", null)?.let { applyBackgroundImage(Uri.parse(it)) }
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show().also { keepTabBarTappable(it) }
    }
