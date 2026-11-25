package com.usefulunpacker

import org.json.JSONArray
import org.json.JSONObject
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.util.Locale

private const val MAX_ROOT_ENTRIES = 512

/** Inspect only known paths and a bounded sample of the directory's direct children. */
internal fun detectGameEngine(folder: Path): JSONObject {
    if (!Files.isDirectory(folder, NOFOLLOW_LINKS)) throw mcpInputError("Choose an existing game directory")
    val strong = linkedMapOf<String, List<String>>()
    val likely = linkedMapOf<String, List<String>>()

    fun match(engine: String, vararg markers: String) {
        val evidence = markers.filter { folder.hasRegularMarker(it) }
        if (evidence.isNotEmpty()) strong[engine] = evidence
    }

    match("RPG Maker MV", "js/rpg_core.js", "www/js/rpg_core.js")
    match("RPG Maker MZ", "js/rmmz_core.js", "www/js/rmmz_core.js")
    match("RPG Maker XP", "Game.rgssad", "Data/Scripts.rxdata", "data/Scripts.rxdata")
    match("RPG Maker VX", "Game.rgss2a", "Data/Scripts.rvdata", "data/Scripts.rvdata")
    match("RPG Maker VX Ace", "Game.rgss3a", "Data/Scripts.rvdata2", "data/Scripts.rvdata2")
    match("TyranoScript", "tyrano/tyrano.js", "www/tyrano/tyrano.js")

    if (Files.isDirectory(folder.resolve("game"), NOFOLLOW_LINKS)) {
        match("Ren'Py", "renpy/__init__.py", "renpy/__init__.pyc", "renpy/__init__.pyo")
    }

    var truncated = false
    if (strong.isEmpty() || folder.hasRegularMarker("UnityPlayer.dll")) {
        val entries = Files.newDirectoryStream(folder).use { stream ->
            val iterator = stream.iterator()
            val sample = ArrayList<Path>()
            while (iterator.hasNext() && sample.size < MAX_ROOT_ENTRIES) sample.add(iterator.next())
            truncated = iterator.hasNext()
            sample
        }
        val sorted = entries.sortedBy { it.fileName.toString() }
        val files = sorted.filter { Files.isRegularFile(it, NOFOLLOW_LINKS) }.map { it.fileName.toString() }
        val names = files.associateBy { it.lowercase(Locale.ROOT) }
        val unityDll = names["unityplayer.dll"] ?: "UnityPlayer.dll".takeIf(folder::hasRegularMarker)
        if (unityDll != null) {
            val dataDir = sorted.firstOrNull {
                it.fileName.toString().endsWith("_Data", ignoreCase = true) && Files.isDirectory(it, NOFOLLOW_LINKS)
            }
            if (dataDir != null) strong["Unity"] = listOf(unityDll, dataDir.fileName.toString())
            else likely["Unity"] = listOf(unityDll)
        }
        files.firstOrNull { it.endsWith(".xp3", ignoreCase = true) }?.let { likely["KiriKiri"] = listOf(it) }
        files.firstOrNull { it.endsWith(".rpa", ignoreCase = true) }?.let { likely["Ren'Py"] = listOf(it) }
        for (name in files) {
            if (!name.endsWith(".pck", ignoreCase = true)) continue
            val executable = names[name.substringBeforeLast('.').lowercase(Locale.ROOT) + ".exe"] ?: continue
            likely["Godot"] = listOf(executable, name)
            break
        }
    }

    val matches = strong.ifEmpty { likely }
    val confidence = if (strong.isNotEmpty()) "high" else "likely"
    val status = when {
        matches.isEmpty() -> "unknown"
        matches.size > 1 -> "ambiguous"
        else -> "matched"
    }
    return JSONObject().put("status", status).put("truncated", truncated).put("matches",
        JSONArray(matches.map { (engine, evidence) ->
            JSONObject().put("engine", engine).put("confidence", confidence)
                .put("evidence", JSONArray(evidence))
        }))
}

private fun Path.hasRegularMarker(path: String): Boolean {
    val segments = path.split('/')
    var parent = this
    for (segment in segments.dropLast(1)) {
        parent = parent.resolve(segment)
        if (!Files.isDirectory(parent, NOFOLLOW_LINKS)) return false
    }
    return Files.isRegularFile(parent.resolve(segments.last()), NOFOLLOW_LINKS)
}
