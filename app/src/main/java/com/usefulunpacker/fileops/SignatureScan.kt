package com.usefulunpacker

import android.app.AlertDialog
import android.app.ProgressDialog
import android.graphics.Typeface
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import kotlin.concurrent.thread

/** One detected signature: file offset + a technical format label, plus
 *  optional parsed metadata (ZIP EOCD validation gives size + file count). */
data class ScanHit(val offset: Long, val label: String, val size: Long? = null, val fileCount: Int? = null)

/**
 * Parses the scan-core JNI result: `[{"o":offset,"l":"label","s":size,"c":count}]`.
 */
fun parseScanHits(json: String?): List<ScanHit> {
    if (json.isNullOrEmpty()) return emptyList()
    return try {
        val arr = org.json.JSONArray(json)
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.getJSONObject(i)
            ScanHit(
                offset = o.optLong("o", 0),
                label = o.optString("l", ""),
                size = if (o.isNull("s")) null else o.optLong("s", 0),
                fileCount = if (o.isNull("c")) null else o.optInt("c", 0)
            )
        }
    } catch (_: Exception) { emptyList() }
}

/** hit label → archive format key (null = not an archive → save the segment only). */
private val ARCHIVE_LABELS = mapOf(
    "7-zip archive" to "7z",
    "ZIP archive" to "zip",
    "RAR archive" to "rar",
    "RAR archive v5" to "rar",
    "gzip compressed data" to "gz",
    "bzip2 compressed data" to "bz2",
    "XZ compressed data" to "xz",
    "Zstandard compressed data" to "zst",
    "LZ4 compressed data" to "lz4",
    "LZMA compressed data" to "lzma",
    "XP3 archive" to "xp3",
    "ISO 9660 disc image" to "iso",
    "POSIX tar archive" to "tar",
)

/** Formats whose native readers require the archive at byte 0 (must carve first). */
private val NEEDS_CARVE = setOf("7z", "gz", "bz2", "xz", "zst", "lzma", "lz4", "xp3", "tar")

/** rars scans only the first 8 MiB for an embedded RAR signature. */
private const val RAR_SCAN_LIMIT = 8L * 1024 * 1024

/** non-archive hit label → file extension when saving the segment. */
private val EXT_FOR_LABEL = mapOf(
    "PNG image" to "png",
    "JPEG image" to "jpg",
    "GIF image" to "gif",
    "TIFF image (little-endian)" to "tif",
    "TIFF image (big-endian)" to "tif",
    "PDF document" to "pdf",
    "ELF executable" to "elf",
    "RIFF (AVI/WAV/WebP)" to "webp",
    "ISO 9660 disc image" to "iso",
    "MPEG program stream" to "mpg",
)

/** Archive hit label → file extension for a raw dd carve (so the carved file
 *  keeps a recognizable extension instead of a generic .bin). */
private val ARCHIVE_EXT_FOR_LABEL = mapOf(
    "7-zip archive" to "7z",
    "ZIP archive" to "zip",
    "RAR archive" to "rar",
    "RAR archive v5" to "rar",
    "gzip compressed data" to "gz",
    "bzip2 compressed data" to "bz2",
    "XZ compressed data" to "xz",
    "Zstandard compressed data" to "zst",
    "LZ4 compressed data" to "lz4",
    "LZMA compressed data" to "lzma",
    "XP3 archive" to "xp3",
    "ISO 9660 disc image" to "iso",
    "POSIX tar archive" to "tar",
)

/** Copies [src] from [offset] for [length] bytes (null = to the end of the
 *  file) into [dest] (dd-like). Length is honored so an archive embedded in
 *  the middle of a host file is carved WITHOUT the trailing data — the exact
 *  archive size is known from the scan hit (zip EOCD / rar EOF marker / 7z
 *  header / zstd / lz4 / iso). */
fun carveToFile(src: File, offset: Long, length: Long?, dest: File, onProgress: (Long) -> Unit) {
    FileInputStream(src).use { input ->
        // skip() is not guaranteed to advance the full distance — loop it.
        var toSkip = offset
        while (toSkip > 0) {
            val n = input.skip(toSkip)
            if (n <= 0) break
            toSkip -= n
        }
        FileOutputStream(dest).use { out ->
            val buf = ByteArray(1 shl 20)
            var remaining = length
            while (!Thread.currentThread().isInterrupted) {
                val want = (remaining?.coerceAtMost(buf.size.toLong()) ?: buf.size.toLong()).toInt()
                if (want <= 0) break
                val n = input.read(buf, 0, want)
                if (n <= 0) break
                out.write(buf, 0, n)
                onProgress(n.toLong())
                remaining = remaining?.let { it - n }
            }
        }
    }
}

/** Signature / magic-pattern counts reported by the Rust scan-core (kept in
 *  sync with validators.rs: 22 signatures, 68 magic patterns — bzip2 has 9
 *  variants, gif 2, jpeg 3, lzma 36 (4 props × 9 dict prefixes), iso 1,
 *  everything else 1). */
private const val SCAN_SIG_COUNT = 22
private const val SCAN_PATTERN_COUNT = 68

/** binwalk-style scan dialog (Rust scan-core + byte-level progress bar). */
internal fun MainActivity.showSignatureScan(f: File) {
    if (!f.isFile) { toast(getString(R.string.scan_dir_error)); return }
    val total = f.length()
    val pd = ProgressDialog(this).apply {
        setTitle(getString(R.string.action_scan))
        setMessage(getString(R.string.scan_progress))
        setProgressStyle(ProgressDialog.STYLE_HORIZONTAL)
        max = 1000
        progress = 0
        setCancelable(true)
        setOnCancelListener { ScanCore.scanCancel() }
        show()
    }
    // Poll the JNI progress statics every 100ms and update the bar.
    val poller = thread {
        while (!Thread.currentThread().isInterrupted) {
            try { Thread.sleep(100) } catch (_: InterruptedException) { break }
            val bytes = ScanCore.scanProgressBytes()
            val tot = ScanCore.scanProgressTotal()
            runOnUiThread {
                if (!pd.isShowing) return@runOnUiThread
                val pct = if (tot > 0) (bytes * 1000 / tot).toInt().coerceIn(0, 1000) else 0
                pd.progress = pct
                pd.setMessage(getString(R.string.scan_progress_bytes, fmt(bytes), fmt(tot)))
            }
        }
    }
    thread {
        val start = System.currentTimeMillis()
        val json = try { ScanCore.scanFile(f.absolutePath) } catch (_: Exception) { null }
        val elapsed = System.currentTimeMillis() - start
        val hits = parseScanHits(json)
        poller.interrupt()
        runOnUiThread {
            if (!pd.isShowing) return@runOnUiThread
            pd.dismiss()
            showScanResultDialog(f, hits, elapsed)
        }
    }
}

private fun MainActivity.showScanResultDialog(f: File, hits: List<ScanHit>, elapsed: Long) {
    val sep = "-".repeat(72)
    val header = buildString {
        appendLine(f.name)
        appendLine(sep)
        appendLine("${getString(R.string.scan_decimal)}  ${getString(R.string.scan_hex)}  ${getString(R.string.scan_description)}")
        appendLine(sep)
    }
    val footer = buildString {
        appendLine(sep)
        append(getString(R.string.scan_footer, 1, SCAN_SIG_COUNT, SCAN_PATTERN_COUNT, elapsed))
    }

    val tvHeader = TextView(this).apply {
        text = header
        setTypeface(Typeface.MONOSPACE)
        textSize = 12f
        setTextColor(C["primary"]!!)
        setPadding(20, 16, 20, 0)
    }
    val tvFooter = TextView(this).apply {
        text = footer
        setTypeface(Typeface.MONOSPACE)
        textSize = 12f
        setTextColor(C["primary"]!!)
        setPadding(20, 0, 20, 16)
    }

    val adapter = object : BaseAdapter() {
        override fun getCount() = hits.size
        override fun getItem(pos: Int) = hits.getOrNull(pos)
        override fun getItemId(pos: Int) = pos.toLong()
        override fun getView(pos: Int, v: View?, p: ViewGroup?): View {
            val row = v ?: layoutInflater.inflate(R.layout.item_scan_hit, p, false)
            val hit = hits.getOrNull(pos) ?: return row
            val desc = buildString {
                append(hit.label)
                if (hit.fileCount != null) append(getString(R.string.scan_zip_info, hit.fileCount))
                if (hit.size != null) append(", ${fmt(hit.size)}")
            }
            row.findViewById<TextView>(R.id.scan_hit_text).text =
                "%9d  0x%x  %s".format(hit.offset, hit.offset, desc)
            row.findViewById<Button>(R.id.btnExtract).setOnClickListener { extractHit(f, hit) }
            row.findViewById<Button>(R.id.btnCarve).setOnClickListener { carveHit(f, hit) }
            return row
        }
    }
    val list = ListView(this).apply {
        this.adapter = adapter
        divider = android.graphics.drawable.ColorDrawable(C["surface_dark"]!!)
        dividerHeight = 1
        enableFastScroll()
    }
    if (hits.isEmpty()) {
        tvFooter.text = getString(R.string.scan_no_results) + "\n" + tvFooter.text
    }

    val root = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        addView(tvHeader)
        addView(list, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        addView(tvFooter)
    }

    val dlg = AlertDialog.Builder(this)
        .setTitle(getString(R.string.action_scan))
        .setView(root)
        .setNegativeButton(getString(R.string.action_close), null)
        .create()
    val metrics = this.resources.displayMetrics
    dlg.window?.setLayout((metrics.widthPixels * 0.94).toInt(), (metrics.heightPixels * 0.85).toInt())
    dlg.show()
}

/** "Extract" entry: archive hits only — per-format handling, then a destination dialog. */
internal fun MainActivity.extractHit(f: File, hit: ScanHit) {
    val fmt = ARCHIVE_LABELS[hit.label]
    if (fmt == null) {
        toast(getString(R.string.scan_extract_not_archive))
        return
    }
    // Formats that must be carved out first (native readers need byte-0).
    if (fmt in NEEDS_CARVE) {
        showSeparateDestDialog(f, hit, extract = true, message = getString(R.string.msg_separate_needs_carve))
        return
    }
    // RAR embedded deeper than the 8MiB scan window.
    if (fmt == "rar" && hit.offset > RAR_SCAN_LIMIT) {
        showSeparateDestDialog(f, hit, extract = true, message = getString(R.string.msg_separate_rar_deep))
        return
    }
    // A ZIP embedded at a nonzero offset can't be read in place — the zip
    // reader expects the archive at byte 0, so it must be carved out first.
    if (fmt == "zip" && hit.offset > 0) {
        showSeparateDestDialog(f, hit, extract = true, message = getString(R.string.msg_separate_needs_carve))
        return
    }
    // ISO 9660: the magic sits at offset 32768 but the image starts at 0 —
    // preview/extract the whole file directly.
    // ISO 9660: a standalone image has its CD001 magic at exactly offset
    // 32768 (so the file IS the image) → preview the whole file directly.
    // An embedded image (magic past 32768) must be carved out first.
    if (fmt == "iso" && hit.offset > 32768L) {
        showSeparateDestDialog(f, hit, extract = true, message = getString(R.string.msg_separate_needs_carve))
        return
    }
    if (fmt == "iso") {
        val json = try { IsoCore.isoListEntries(f.path) } catch (_: Exception) { null }
        if (json != null && json != "[]") {
            showPreviewDialog(f, parseEntries(json), fmt, "")
            return
        }
        showSeparateDestDialog(f, hit, extract = true, message = getString(R.string.msg_separate_iso_invalid))
        return
    }
    // ZIP at offset 0 and RAR (≤8MiB) can be previewed/extracted directly.
    val json = try {
        when (fmt) {
            "zip" -> { ZipCore.zipSetEncoding(prefs.getString("zip_encoding", "UTF-8") ?: "UTF-8"); ZipCore.zipListEntries(f.path) }
            else -> RarCore.rarListEntries(f.path)
        }
    } catch (_: Exception) { null }
    if (json != null && json != "[]") {
        showPreviewDialog(f, parseEntries(json), fmt, "")
        return
    }
    val needsPw = fmt == "rar" && runCatching { RarCore.rarNeedsPassword(f.path) }.getOrDefault(false)
    val msg = when {
        needsPw -> getString(R.string.msg_separate_rar_encrypted)
        fmt == "rar" -> getString(R.string.msg_separate_rar_invalid)
        else -> getString(R.string.msg_separate_zip_invalid)
    }
    showSeparateDestDialog(f, hit, extract = true, message = msg)
}

/** "Carve" entry: every hit — raw dd of [offset..EOF] into a standalone file,
 *  no parsing, no extraction. */
internal fun MainActivity.carveHit(f: File, hit: ScanHit) {
    showSeparateDestDialog(f, hit, extract = false, message = null)
}

/** Destination choice: separate to a deduped new folder, or to the current directory.
 *  Options live in a custom view body instead of setItems / positive+neutral
 *  buttons: on some EMUI builds those get hidden and only the negative
 *  (cancel) button stays visible. */
private fun MainActivity.showSeparateDestDialog(f: File, hit: ScanHit, extract: Boolean, message: String?) {
    val base = f.nameWithoutExtension
    val newFolder = uniqueFile(currentDir, base)
    fun optionRow(text: String, color: Int, onClick: () -> Unit) = TextView(this).apply {
        this.text = text
        setTextColor(color)
        textSize = 15f
        setPadding(24, 16, 24, 16)
        background = android.graphics.drawable.ColorDrawable(0x00000000)
        setOnClickListener { onClick() }
    }
    val body = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(C["surface"]!!)
        if (message != null) {
            addView(TextView(this@showSeparateDestDialog).apply {
                text = message
                setTextColor(C["secondary"]!!)
                textSize = 13f
                setPadding(24, 16, 24, 12)
            })
        }
        addView(optionRow(getString(R.string.separate_new_folder, newFolder.name), C["accent"]!!) {
            if (extract) separateAndExtract(f, hit, newFolder)
            else separateToFile(f, hit, newFolder)
        })
        addView(View(this@showSeparateDestDialog).apply {
            setBackgroundColor(C["divider_subtle"]!!)
            layoutParams = LinearLayout.LayoutParams(MATCH, 1).apply { setMargins(24, 0, 24, 0) }
        })
        addView(optionRow(getString(R.string.separate_to_current), C["primary"]!!) {
            if (extract) separateAndExtract(f, hit, currentDir)
            else separateToFile(f, hit, currentDir)
        })
    }
    AlertDialog.Builder(this)
        .setTitle(getString(R.string.separate))
        .setView(body)
        .setNegativeButton(getString(R.string.action_cancel), null)
        .show()
}

/** Magic internal offset: bytes BEFORE the magic where the container itself
 *  starts. ISO's CD001 lives at +32768 (system area), tar's "ustar" at +257
 *  (the first header field). For these, the archive start is at
 *  hit.offset - adjust, and the region extends hit.size + adjust bytes. */
private fun magicStartAdjust(label: String): Long = when (label) {
    "ISO 9660 disc image" -> 32768L
    "POSIX tar archive" -> 257L
    else -> 0L
}

/** Carve start offset for a hit. Formats whose magic sits inside the container
 *  (ISO/tar) must start carving before the magic offset. */
private fun carveOffsetOf(hit: ScanHit): Long =
    (hit.offset - magicStartAdjust(hit.label)).coerceAtLeast(0L)

/** Carve length for a hit: the exact archive size when the validator computed
 *  it (zip/rar/7z/zstd/lz4/iso/tar), else null → carve to the end of the file
 *  (gzip/bz2/xz/lzma/xp3). hit.size is the extent FROM the magic offset, so
 *  ISO/tar add their internal offset back. Clamped to the available bytes. */
private fun carveLengthOf(f: File, hit: ScanHit): Long? {
    val avail = (f.length() - carveOffsetOf(hit)).coerceAtLeast(0L)
    val size = hit.size ?: return null
    val full = if (size > 0) size + magicStartAdjust(hit.label) else 0L
    return if (full > 0) full.coerceAtMost(avail) else null
}

/** Carve the archive out of the host file (when needed), then run the normal extract flow. */
private fun MainActivity.separateAndExtract(f: File, hit: ScanHit, destDir: File) {
    val fmt = ARCHIVE_LABELS[hit.label] ?: return
    val needsCarve = fmt in NEEDS_CARVE || (fmt == "rar" && hit.offset > RAR_SCAN_LIMIT) || (fmt == "zip" && hit.offset > 0) || (fmt == "iso" && hit.offset > 32768L)
    if (!needsCarve) {
        extractAll(destDir, f, fmt)
        return
    }
    // Carve to a per-format temp file. The whole carve phase holds the
    // OperationLock and uses a serialized temp dir so two concurrent carves
    // can't delete each other's in-progress file.
    val tempDir = File(cacheDir, "separate")
    val temp = File(tempDir, "${f.nameWithoutExtension}.${fmt}")
    val carveOffset = carveOffsetOf(hit)
    val carveLen = carveLengthOf(f, hit)
    val carveTotal = carveLen ?: (f.length() - carveOffset).coerceAtLeast(0L)
    var carved = 0L
    var carveThread: Thread? = null
    val pd = ProgressDialog(this).apply {
        setTitle(getString(R.string.separate))
        setMessage(getString(R.string.msg_carving))
        setProgressStyle(ProgressDialog.STYLE_HORIZONTAL)
        max = 1000
        progress = 0
        setCancelable(true)
        setOnCancelListener { carveThread?.interrupt() }
        show()
    }
    carveThread = thread {
        if (!OperationLock.acquire()) {
            runOnUiThread { pd.dismiss(); toast(getString(R.string.msg_op_in_progress)) }
            return@thread
        }
        try {
            // Clean stale carved temps, then carve [offset..EOF].
            tempDir.deleteRecursively()
            tempDir.mkdirs()
            carveToFile(f, carveOffset, carveLen, temp) { n ->
                carved += n
                runOnUiThread {
                    if (!pd.isShowing) return@runOnUiThread
                    val pct = if (carveTotal > 0) (carved * 1000 / carveTotal).toInt().coerceIn(0, 1000) else 0
                    pd.progress = pct
                    pd.setMessage(getString(R.string.msg_carving_bytes, fmt(carved), fmt(carveTotal)))
                }
            }
            if (Thread.currentThread().isInterrupted) {
                runOnUiThread { pd.dismiss(); toast(getString(R.string.msg_cancelled)) }
                return@thread
            }
            runOnUiThread {
                pd.dismiss()
                // extractAll acquires its own lock (we released ours on carve).
                extractAll(destDir, temp, fmt)
            }
        } catch (e: Exception) {
            runOnUiThread { pd.dismiss(); toast(getString(R.string.err_extract_io, e.message ?: "")) }
        } finally {
            OperationLock.release()
        }
    }
}

/** dd: carve [offset..EOF] into a standalone file inside [destDir]. */
private fun MainActivity.separateToFile(f: File, hit: ScanHit, destDir: File) {
    val ext = ARCHIVE_EXT_FOR_LABEL[hit.label] ?: EXT_FOR_LABEL[hit.label] ?: "bin"
    destDir.mkdirs()
    val out = uniqueFile(destDir, "${f.nameWithoutExtension}.$ext")
    val carveOffset = carveOffsetOf(hit)
    val carveLen = carveLengthOf(f, hit)
    val carveTotal = carveLen ?: (f.length() - carveOffset).coerceAtLeast(0L)
    var carved = 0L
    val pd = ProgressDialog(this).apply {
        setTitle(getString(R.string.separate))
        setMessage(getString(R.string.msg_carving))
        setProgressStyle(ProgressDialog.STYLE_HORIZONTAL)
        max = 1000
        progress = 0
        setCancelable(false)
        show()
    }
    thread {
        try {
            carveToFile(f, carveOffset, carveLen, out) { n ->
                carved += n
                runOnUiThread {
                    if (!pd.isShowing) return@runOnUiThread
                    val pct = if (carveTotal > 0) (carved * 1000 / carveTotal).toInt().coerceIn(0, 1000) else 0
                    pd.progress = pct
                    pd.setMessage(getString(R.string.msg_carving_bytes, fmt(carved), fmt(carveTotal)))
                }
            }
            runOnUiThread {
                pd.dismiss()
                toast(getString(R.string.msg_separate_done, out.name))
                nav(currentDir)
            }
        } catch (e: Exception) {
            runOnUiThread { pd.dismiss(); toast(getString(R.string.err_extract_io, e.message ?: "")) }
        }
    }
}
