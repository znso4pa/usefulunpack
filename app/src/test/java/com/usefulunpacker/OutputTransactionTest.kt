package com.usefulunpacker

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException

class OutputTransactionTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun encoderFalsePreservesRecoveryAndDoesNotPublishPartialBytes() {
        val destination = temp.newFile().apply { writeText("last complete recovery") }
        try {
            writeOutputAtomically(destination) { it.writeText("partial"); false }
            fail("accepted encoder failure")
        } catch (_: IOException) {}
        assertEquals("last complete recovery", destination.readText())
        assertEquals(listOf(destination.name), temp.root.list()!!.toList())
    }

    @Test fun thrownWriteLeavesNoNewDestinationOrTemporaryFile() {
        val destination = temp.root.resolve("output.png")
        try {
            writeOutputAtomically(destination) { it.writeText("partial"); throw IOException("injected") }
            fail("accepted write failure")
        } catch (_: IOException) {}
        assertFalse(destination.exists())
        assertEquals(0, temp.root.list()!!.size)
    }

    @Test fun completedEncodingReplacesTheRecoveryByteForByte() {
        val destination = temp.newFile().apply { writeText("old recovery") }
        val bytes = ByteArray(1024) { (it * 37).toByte() }
        writeOutputAtomically(destination) { it.writeBytes(bytes); true }
        assertArrayEquals(bytes, destination.readBytes())
        assertEquals(listOf(destination.name), temp.root.list()!!.toList())
    }
}
