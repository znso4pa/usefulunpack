package com.usefulunpacker

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException

class AgentSessionStoreTest {
    @get:Rule val tmp = TemporaryFolder()

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
