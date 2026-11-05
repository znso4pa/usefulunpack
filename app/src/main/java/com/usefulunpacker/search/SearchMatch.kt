package com.usefulunpacker

/**
 * 一行文本（或一个文件名）是否命中搜索词。**纯函数**，主机单测覆盖 ——
 * `walkSearch` 那条路是 Android 耦合的，测不了，所以判定逻辑单独拎在这里。
 *
 * 正则分支**直接拿原文匹配**，不先把文本 `lowercase()`。三条理由：
 *
 * 1. **别再分配**。旧实现对每一行都 `line.lowercase()` 一次 —— 10 MB 的文本就是
 *    十万个临时 String，纯属白扔；不敏感本来就能交给编译期的 `IGNORE_CASE`。
 * 2. **不改写被匹配的文本**。降级会改变长度（`'İ'.lowercase()` 是两个字符），
 *    凡是依赖偏移的模式（回溯引用、`\b`、`^`/`$`）都跟着漂。
 * 3. **语义上更诚实**：用户勾了「区分大小写」与否，影响的是**比较方式**，
 *    不该偷偷换掉被比较的东西。
 *
 * ⚠️ 一个曾经的**错误**判断，记在这里免得再被推一遍：当初以为「先降级文本会让
 * `\p{Lu}` 永远匹配不到」。实测（JVM，见 `SearchMatchTest`）**不成立** ——
 * `Regex("\\p{Lu}", IGNORE_CASE)` 对 `"abc"` 同样命中，`[A-Z]` 也是。也就是说旧写法
 * 在这点上没 bug，收益只在上面 1/2/3。别把「以为的 bug」写进提交说明。
 */
internal fun textMatches(
    text: String,
    query: String,
    regex: Regex?,
    caseSensitive: Boolean,
): Boolean = when {
    // 空查询词一律不命中。`String.contains("")` 恒为 true，不挡的话「还没输入就搜」
    // 会把整棵树都当成结果。调用方本来也会拦，这里是让这个函数自己是完整的。
    query.isEmpty() && regex == null -> false
    regex != null -> regex.containsMatchIn(text)
    caseSensitive -> text.contains(query)
    // 大小写不敏感时调用方已经把 `query` 降成小写；这里再 ignoreCase 一次仍然成立。
    // 用 contains(ignoreCase) 而不是「先降级 text 再 contains」，同样是为了不分配副本。
    else -> text.contains(query, ignoreCase = true)
}
