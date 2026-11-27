package com.usefulunpacker

import com.usefulunpacker.UuCommands.Ctx
import com.usefulunpacker.UuCommands.Result
import java.util.concurrent.atomic.AtomicBoolean

internal val INSPECTION_VALUE_FLAGS = listOf("--after", "--limit")
internal val LOCALIZATION_VALUE_FLAGS = INSPECTION_VALUE_FLAGS + listOf("--profile", "--source-encoding", "--translation-encoding", "--target-encoding")

/** Conventional exits: 0 no findings, 1 differences/review, 2 incomplete/input error. */
internal fun UuCommands.inspect(args: List<String>, ctx: Ctx, localization: Boolean): Result {
    val name = if (localization) "check" else "dircmp"
    val parsed = splitFlags(args, if (localization) LOCALIZATION_VALUE_FLAGS else INSPECTION_VALUE_FLAGS, listOf("-j"))
    if (parsed.missingValue != null || parsed.pos.size != 2 || parsed.pos.any { it.startsWith('-') }) return Result(usageFor(name), 2)
    val offset = parsed.values["--after"]?.toIntOrNull() ?: if ("--after" !in parsed.values) 0 else -1
    val limit = parsed.values["--limit"]?.toIntOrNull() ?: if ("--limit" !in parsed.values) 20 else -1
    if (offset !in 0..(if (localization) 1000 else 2000) || limit !in 1..50) return Result(usageFor(name), 2)
    val cancelled = AtomicBoolean()
    ctx.registerCancel?.invoke { cancelled.set(true) }
    fun active(): Boolean { ctx.checkCancelled?.invoke(); return cancelled.get() }
    return try {
        val a = UuText.resolve(ctx.cwd, parsed.pos[0])
        val b = UuText.resolve(ctx.cwd, parsed.pos[1])
        if (localization) {
            val report = LocalizationChecker.check(a, b, parsed.values["--source-encoding"] ?: "auto",
                parsed.values["--translation-encoding"] ?: "auto", parsed.values["--target-encoding"] ?: "translation",
                parsed.values["--profile"] ?: "encoding", ::active)
            val incomplete = report.profile != "encoding" && (!report.aligned || report.skippedLines > 0) || report.limited
            val exit = if (incomplete) 2 else if (report.totalIssues > 0) 1 else 0
            val text = if ("-j" in parsed.bools) report.json(offset, limit).toString() else buildString {
                append(ctx.text(R.string.localization_result_counts, report.errors, report.totalIssues - report.errors)).append('\n')
                append(ctx.text(R.string.localization_coverage, report.checkedLines, report.placeholderLines)).append('\n')
                if (incomplete) append(ctx.text(R.string.localization_partial_hint)).append('\n')
                for (row in report.issues.drop(offset).take(limit)) append("${row.rule.name}\t${row.line ?: 0}\n")
            }.trimEnd()
            Result(text, exit)
        } else {
            val report = DirectoryComparator.compare(a, b, ::active) { visited, _ -> ctx.progress?.invoke(ctx.text(R.string.directory_progress, visited)) }
            Result(if ("-j" in parsed.bools) report.json(offset, limit).toString() else buildString {
                append(report.status).append('\n')
                append(directorySummary(report, ctx.str)).append('\n')
                for (row in report.entries.drop(offset).take(limit)) append("${row.state.name}\t${visibleAgentText(row.path)}\t${row.reason}\n")
            }.trimEnd(), if (!report.complete) 2 else if (report.differences > 0) 1 else 0)
        }
    } catch (e: java.util.concurrent.CancellationException) { throw e }
    catch (e: LocalizationCheckException) { Result("LOCALIZATION_${e.reason}", 2) }
    catch (_: java.io.IOException) { Result(ctx.text(R.string.directory_failed), 2) }
}

internal fun directorySummary(report: DirectoryComparisonReport, str: StrFn): String = str(R.string.directory_summary, arrayOf(
    report.counts[DirectoryChange.ADDED] ?: 0, report.counts[DirectoryChange.REMOVED] ?: 0,
    report.counts[DirectoryChange.MODIFIED] ?: 0, report.counts[DirectoryChange.TYPE_CHANGED] ?: 0,
    (report.counts[DirectoryChange.UNREADABLE] ?: 0) + (report.counts[DirectoryChange.SKIPPED] ?: 0), report.unchanged))
