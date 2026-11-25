package com.usefulunpacker

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
import java.io.IOException
import java.net.URL
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal const val AGENT_KEY_LIMIT = 1024
private const val PROVIDER = "AndroidKeyStore"
private const val KEY_ALIAS = "usefulunpack.agent.credentials.v1"
private const val CIPHER = "AES/GCM/NoPadding"
private const val NONCE_SIZE = 12
private const val TAG_SIZE = 16
private const val FORMAT: Byte = 1

/** Each record is bound to the exact endpoint as authenticated data. No key is written in plaintext. */
internal class AgentCredentials(
    private val directory: File,
    private val key: (create: Boolean) -> SecretKey = ::agentStorageKey,
) {
    fun read(endpoint: URL): String? = synchronized(lock) {
        val identity = endpoint.toExternalForm().toByteArray(Charsets.UTF_8)
        val file = fileFor(identity)
        if (!file.exists()) return null
        val record = file.inputStream().use { input ->
            val bytes = ByteArray(1 + NONCE_SIZE + AGENT_KEY_LIMIT + TAG_SIZE + 1)
            var count = 0
            while (count < bytes.size) {
                val size = input.read(bytes, count, bytes.size - count)
                if (size < 0) break
                count += size
            }
            if (count <= 1 + NONCE_SIZE + TAG_SIZE || count == bytes.size || bytes[0] != FORMAT) {
                throw IOException("Invalid Agent credential record")
            }
            bytes.copyOf(count)
        }
        val cipher = Cipher.getInstance(CIPHER)
        cipher.init(Cipher.DECRYPT_MODE, key(false), GCMParameterSpec(TAG_SIZE * 8, record, 1, NONCE_SIZE))
        cipher.updateAAD(identity)
        val plaintext = cipher.doFinal(record, 1 + NONCE_SIZE, record.size - 1 - NONCE_SIZE)
        try { String(plaintext, Charsets.UTF_8).also { require(validAgentKey(it)) } }
        finally { plaintext.fill(0) }
    }

    fun save(endpoint: URL, value: String) = synchronized(lock) {
        require(validAgentKey(value))
        val identity = endpoint.toExternalForm().toByteArray(Charsets.UTF_8)
        val file = fileFor(identity)
        val temp = File(directory, "${file.name}.tmp")
        if (value.isEmpty()) {
            Files.deleteIfExists(temp.toPath())
            Files.deleteIfExists(file.toPath())
            return
        }
        if (!directory.isDirectory && !directory.mkdirs()) throw IOException("Cannot create credential directory")
        val cipher = Cipher.getInstance(CIPHER)
        cipher.init(Cipher.ENCRYPT_MODE, key(true))
        cipher.updateAAD(identity)
        val nonce = cipher.iv
        check(nonce.size == NONCE_SIZE)
        val plaintext = value.toByteArray(Charsets.UTF_8)
        val ciphertext = try { cipher.doFinal(plaintext) } finally { plaintext.fill(0) }
        try {
            temp.outputStream().use {
                it.write(FORMAT.toInt())
                it.write(nonce)
                it.write(ciphertext)
            }
            Files.move(temp.toPath(), file.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
        } finally {
            Files.deleteIfExists(temp.toPath())
        }
        Unit
    }

    private fun fileFor(identity: ByteArray): File {
        val digest = MessageDigest.getInstance("SHA-256").digest(identity)
        return File(directory, digest.joinToString("") { "%02x".format(it) } + ".key")
    }

    private companion object { val lock = Any() }
}

internal fun validAgentKey(key: String) = key.length <= AGENT_KEY_LIMIT && key.all { it in '!'..'~' }

private fun agentStorageKey(create: Boolean): SecretKey {
    val store = KeyStore.getInstance(PROVIDER).apply { load(null) }
    (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
    if (!create) throw IOException("Agent credential encryption key is unavailable")
    return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER).run {
        init(KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build())
        generateKey()
    }
}
