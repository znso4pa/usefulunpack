package com.usefulunpacker

import java.io.File

/**
 * uu 命令集用到的**全部**用户可见文案。
 *
 * 单独一个文件的原因：命令层要能在没有 Activity 的情况下跑（见 [UuCommands.Ctx]），
 * 所以它不能直接调 `getString`。这里统一走传入的 [str] 取串函数，
 * **不使用任何全局可变状态** —— 早先版本用了一个全局 `getter` 单例，
 * 那是错的：它跨 Activity 实例泄漏，也让单测无法确定当前绑定的是谁。
 *
 * 所有条目都必须是字符串资源（项目约定：emoji 只存在于 strings.xml，
 * Kotlin 里绝不拼接）。`values-zh-rTW` 曾长期落后 7 条 `<string>`，
 * 就是靠「四语言 key 集合比对」才发现的，新增文案务必一次补齐四个 locale。
 *
 * @param str `(resId, args) -> 本地化文案`；由终端层（唯一有 Activity 的地方）注入。
 */
internal typealias StrFn = (Int, Array<Any>) -> String

internal object UuText {

    // ─── 分发 / 帮助 ──────────────────────────────────────────────────────

    fun unknownCommand(str: StrFn, name: String) = str(R.string.cli_unknown_command, arrayOf(name))
    fun helpIntro(str: StrFn) = str(R.string.cli_help_intro, emptyArray())
    fun helpExamples(str: StrFn) = str(R.string.cli_help_examples, emptyArray())

    fun needFile(str: StrFn, cmd: String) = str(R.string.cli_need_file, arrayOf(cmd))
    fun pickFileTitle(str: StrFn, cmd: String) = str(R.string.cli_pick_file_title, arrayOf(cmd))
    fun pickFolderTitle(str: StrFn, cmd: String) = str(R.string.cli_pick_folder_title, arrayOf(cmd))
    fun pickKeep(str: StrFn) = str(R.string.cli_pick_keep, emptyArray())
    fun pickKeepSub(str: StrFn, dir: String) = str(R.string.cli_pick_keep_sub, arrayOf(dir))
    fun pickSwitch(str: StrFn) = str(R.string.cli_pick_switch, emptyArray())
    fun pickSwitchSub(str: StrFn) = str(R.string.cli_pick_switch_sub, emptyArray())
    fun notFound(str: StrFn, path: String) = str(R.string.cli_not_found, arrayOf(path))
    fun failed(str: StrFn, reason: String) = str(R.string.cli_failed, arrayOf(reason))
    fun notYet(str: StrFn, cmd: String) = str(R.string.cli_not_yet, arrayOf(cmd))

    // ─── 解析 ────────────────────────────────────────────────────────────

    fun unclosedQuote(str: StrFn, quote: Char) =
        str(R.string.cli_unclosed_quote, arrayOf(quote.toString()))

    // ─── uu l ────────────────────────────────────────────────────────────

    fun listHeader(str: StrFn, name: String, count: Int, total: String) =
        str(R.string.cli_list_header, arrayOf(name, count.toString(), total))
    fun listDirMark(str: StrFn) = str(R.string.cli_list_dir_mark, emptyArray())
    fun listEncMark(str: StrFn) = str(R.string.cli_list_enc_mark, emptyArray())
    fun listFailed(str: StrFn, name: String) = str(R.string.cli_list_failed, arrayOf(name))
    fun listFailedWhy(str: StrFn, name: String, why: String) = str(R.string.cli_list_failed_why, arrayOf(name, why))
    fun listEmpty(str: StrFn, name: String) = str(R.string.cli_list_empty, arrayOf(name))
    fun listTruncated(str: StrFn, more: Int) = str(R.string.cli_list_truncated, arrayOf(more.toString()))

    // ─── uu scan / FD ─────────────────────────────────────────────────────

    fun scanFailed(str: StrFn, name: String) = str(R.string.cli_scan_failed, arrayOf(name))
    fun scanNone(str: StrFn, name: String) = str(R.string.cli_scan_none, arrayOf(name))
    fun scanHeader(str: StrFn, name: String, hits: Int) =
        str(R.string.cli_scan_header, arrayOf(name, hits.toString()))
    fun scanEntries(str: StrFn, n: Int) = str(R.string.cli_scan_entries, arrayOf(n.toString()))
    fun scanFdHint(str: StrFn, n: Int) = str(R.string.cli_scan_fd_hint, arrayOf(n.toString()))
    fun fdNotArchive(str: StrFn, fd: String, label: String) =
        str(R.string.cli_fd_not_archive, arrayOf(fd, label))
    fun fdStale(str: StrFn, fd: String) = str(R.string.cli_fd_stale, arrayOf(fd))
    fun noSuchFd(str: StrFn, name: String) = str(R.string.cli_no_such_fd, arrayOf(name))
    fun needsActivity(str: StrFn) = str(R.string.cli_needs_activity, emptyArray())

    // ─── uu x ────────────────────────────────────────────────────────────

    fun extractOk(str: StrFn, name: String, total: Int, success: Int, errors: Int) =
        str(R.string.cli_extract_ok, arrayOf(name, total.toString(), success.toString(), errors.toString()))
    fun extractCancelled(str: StrFn) = str(R.string.cli_extract_cancelled, emptyArray())
    fun extractPwdCancelled(str: StrFn) = str(R.string.cli_extract_pwd_cancelled, emptyArray())
    fun extractBadFormat(str: StrFn, known: String) =
        str(R.string.cli_extract_bad_format, arrayOf(known))

    // ─── uu c ────────────────────────────────────────────────────────────

    /** 输出扩展名反向映射到多个封包 key 时（目前只有 .pfs）用。 */
    fun packAmbiguousExt(str: StrFn, ext: String, keys: String) =
        str(R.string.cli_pack_ambiguous_ext, arrayOf(ext, keys))
    fun packNoKeyForExt(str: StrFn, ext: String) =
        str(R.string.cli_pack_no_key_for_ext, arrayOf(ext))
    fun packUnknownKey(str: StrFn, key: String) = str(R.string.cli_pack_unknown_key, arrayOf(key))
    fun packSplitUnsupported(str: StrFn, fmt: String) =
        str(R.string.cli_pack_split_unsupported, arrayOf(fmt))
    fun packOk(str: StrFn, name: String) = str(R.string.cli_pack_ok, arrayOf(name))
    fun packFailed(str: StrFn, name: String) = str(R.string.cli_pack_failed, arrayOf(name))

    // ─── 路径 ────────────────────────────────────────────────────────────

    /** 相对会话 cwd 解析；绝对路径原样返回。 */
    fun resolve(cwd: File, p: String): File =
        if (p.startsWith("/")) File(p) else File(cwd, p)
}
