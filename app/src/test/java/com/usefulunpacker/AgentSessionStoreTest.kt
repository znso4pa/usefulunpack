package com.usefulunpacker

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException

class AgentSessionStoreTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun customNameSurvivesSavingAndBlankRestoresTheOriginalTitle() {
        val directory = tmp.newFolder()
        val store = AgentSessionStore(directory)
        val original = store.save(AgentSession(workspace = "/example/workspace",
            turns = listOf(AgentExchange("Find the engine\nfor this folder", "Ren\u0027Py"))))
        assertEquals("Find the engine", AgentSessionStore(directory).list().single().title)

        val named = store.save(original.withName("  翻译\n项目  "))
        assertEquals("翻译 项目", named.title)
        assertEquals(original.id, named.id)
        assertEquals(original.turns, named.turns)
        assertEquals(original.workspace, named.workspace)
        assertEquals(named, AgentSessionStore(directory).list().single())

        val automatic = store.save(named.withName("  \n "))
        assertNull(automatic.customName)
        assertEquals(original.title, AgentSessionStore(directory).list().single().title)
    }

    @Test fun sessionCanBeNamedBeforeTheFirstMessage() {
        val store = AgentSessionStore(tmp.newFolder())
        val name = "A".repeat(AGENT_SESSION_NAME_LIMIT - 1)
        val named = store.save(AgentSession().withName(name + "😀"))
        assertEquals(name, named.title)
        val running = store.beginTurn(named, "First question")
        try {
            assertEquals(name, running.title)
        } finally {
            store.finish(running.id)
        }
    }

    @Test fun catalogKeepsHealthySessionsVisibleAndRefreshesAfterRenameOrDelete() {
        val directory = tmp.newFolder()
        val store = AgentSessionStore(directory)
        val original = store.save(AgentSession().withName("First title"))
        directory.resolve("${"a".repeat(32)}.gz").writeText("damaged snapshot")
        assertEquals(AgentSessionCatalog(listOf(original.summary), 1), store.catalog())

        val otherStore = AgentSessionStore(directory)
        val renamed = otherStore.save(original.withName("Latest title"))
        assertEquals(listOf(renamed.summary), store.catalog().sessions)
        assertEquals(renamed, store.open(renamed.id))
        otherStore.delete(renamed) {}
        assertEquals(AgentSessionCatalog(emptyList(), 1), store.catalog())
        assertThrows(IOException::class.java) { store.open(renamed.id) }
    }

    @Test fun contextWindowOnlyAdvancesWhenAChunkIsCompacted() {
        val turns = List(13) { AgentExchange("question $it", "answer $it") }
        fun window(count: Int) = AgentSession(turns = turns.take(count) + AgentExchange("pending")).historyWindow()
        assertEquals(turns.take(8), window(8))
        assertEquals(turns.subList(4, 9), window(9))
        assertEquals(turns.subList(4, 12), window(12))
        assertEquals(turns.subList(8, 13), window(13))
    }

    @Test fun changingModelAddressDoesNotCarryConversationContextOrPermissionsAcross() {
        val saved = AgentSession(
            workspace = "/example/workspace", permission = AgentPermission.FULL_LOCAL, context = "private context",
            baseUrl = "https://model.example/one", model = "first",
            turns = listOf(AgentExchange("question", "answer")),
        )
        val next = saved.withModelSettings("https://model.example/two", "second")
        assertTrue(saved.changesModelEndpoint("https://model.example/two"))
        assertNotEquals(saved.id, next.id)
        assertNull(next.workspace)
        assertEquals(AgentPermission.READ_ONLY, next.permission)
        assertEquals("", next.context)
        assertTrue(next.turns.isEmpty())
        val same = saved.withModelSettings("https://model.example/one/", "second")
        assertFalse(saved.changesModelEndpoint("https://model.example/one/"))
        assertEquals(saved.context, same.context)
        assertEquals(saved.workspace, same.workspace)
    }

    @Test fun oldSessionViewCannotDeleteNewerRevision() {
        val store = AgentSessionStore(tmp.newFolder())
        val original = store.save(AgentSession())
        val newer = store.save(original.copy(context = "Later edit"))
        assertThrows(IOException::class.java) { store.delete(original) { error("No log deletion for a stale revision") } }
        assertEquals(listOf(newer), store.list())
    }

    @Test fun beginningTurnPersistsAndReservesTheSessionTogether() {
        val store = AgentSessionStore(tmp.newFolder())
        val original = store.save(AgentSession())
        val active = store.beginTurn(original, "first")
        try {
            assertEquals(listOf(AgentExchange("first")), store.list().single().turns)
            assertThrows(IOException::class.java) { store.beginTurn(active, "second") }
            assertThrows(IOException::class.java) { store.save(active.copy(context = "concurrent edit")) }
        } finally {
            store.finish(active.id)
        }
        assertThrows(IOException::class.java) { store.beginTurn(original, "stale") }
        val retry = store.beginTurn(active, "second")
        try {
            assertEquals(listOf("first", "second"), retry.turns.map { it.question })
        } finally {
            store.finish(retry.id)
        }
    }

    @Test fun deletesCompressedSessionAndOnlyItsDiagnosticFiles() {
        val directory = tmp.newFolder()
        val store = AgentSessionStore(directory)
        val logDir = tmp.newFolder()
        val logs = DiagnosticStore(logDir)
        try {
            val session = store.save(AgentSession(permission = AgentPermission.EDIT_FILES,
                context = "Project instructions", turns = listOf(AgentExchange("List archives", "example.zip"))))
            assertEquals(listOf(session), AgentSessionStore(directory).list())
            assertEquals(listOf("${session.id}.gz"), directory.list()?.toList())

            logs.append("agent-log", session.feature, "STEP_END")
            logs.append("mcp-log", "mcp_read_text", "STEP_END")
            val running = store.beginTurn(session, "pending")
            assertThrows(IOException::class.java) { store.delete(running, logs::deleteAgentSession) }
            store.finish(running.id)
            store.delete(running, logs::deleteAgentSession)
            logs.append("late-agent-log", session.feature, "CHAIN_END")
            logs.flush()

            assertTrue(store.list().isEmpty())
            assertTrue(directory.list().orEmpty().isEmpty())
            assertNull(logs.readEntry("agent-log"))
            assertNull(logs.readEntry("late-agent-log"))
            assertNotNull(logs.readEntry("mcp-log"))
            assertEquals(listOf("mcp-log.log"), logDir.list()?.toList())
            assertThrows(IOException::class.java) { store.save(session) }
        } finally {
            logs.close()
        }
    }
}
