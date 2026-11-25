package com.usefulunpacker

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException

class AgentSessionStoreTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun changingModelAddressDoesNotCarryConversationContextOrPermissionsAcross() {
        val saved = AgentSession(
            workspace = "/example/workspace", permission = AgentPermission.FULL_LOCAL, context = "private context",
            baseUrl = "https://model.example/one", model = "first",
            turns = listOf(AgentExchange("question", "answer")),
        )
        val next = saved.withModelSettings("https://model.example/two", "second")
        assertNotEquals(saved.id, next.id)
        assertNull(next.workspace)
        assertEquals(AgentPermission.READ_ONLY, next.permission)
        assertEquals("", next.context)
        assertTrue(next.turns.isEmpty())
        val same = saved.withModelSettings("https://model.example/one/", "second")
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
            assertTrue(store.start(session.id))
            assertThrows(IOException::class.java) { store.delete(session, logs::deleteAgentSession) }
            store.finish(session.id)
            store.delete(session, logs::deleteAgentSession)
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
