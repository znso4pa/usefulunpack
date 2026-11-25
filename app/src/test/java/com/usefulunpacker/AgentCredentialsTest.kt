package com.usefulunpacker

import java.nio.file.Files
import java.security.GeneralSecurityException
import javax.crypto.KeyGenerator
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AgentCredentialsTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun encryptedCredentialsPersistOnlyAtTheirSelectedEndpoint() {
        val directory = temp.newFolder()
        val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        val vault = AgentCredentials(directory) { key }
        val first = checkNotNull(agentEndpoint("https://model.example/one"))
        val second = checkNotNull(agentEndpoint("https://model.example/two"))
        val sample = "test-credential-for-first-endpoint"
        vault.save(first, sample)
        assertNull(vault.read(second))
        val firstFile = directory.listFiles()!!.single()
        val snapshot = firstFile.readBytes()
        assertFalse(String(snapshot, Charsets.UTF_8).contains(sample))
        assertEquals(sample, AgentCredentials(directory) { key }.read(first))

        vault.save(first, sample)
        assertFalse(snapshot.contentEquals(firstFile.readBytes()))
        vault.save(second, "second-test-key")
        assertEquals("second-test-key", vault.read(second))
        val secondFile = directory.listFiles()!!.single { it != firstFile }
        Files.copy(firstFile.toPath(), secondFile.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        assertThrows(GeneralSecurityException::class.java) { vault.read(second) }

        vault.save(first, "")
        assertNull(vault.read(first))
        assertFalse(firstFile.exists())
    }
}
