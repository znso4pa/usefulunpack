package com.usefulunpacker

import java.io.BufferedInputStream
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** Single-message HTTP/1.1 connections. Bound to the IPv4 loopback before accepting any traffic. */
internal class McpListener(
    private val protocol: McpProtocol,
    private val tasks: McpTasks,
    val directory: java.io.File,
) : AutoCloseable {
    private val server = ServerSocket().apply {
        try { bind(InetSocketAddress(InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)), 0), 8) }
        catch (e: IOException) { close(); throw e }
    }
    val running: Boolean get() = !server.isClosed
    private val port = server.localPort
    val url = "http://127.0.0.1:$port/mcp"
    val token: String = ByteArray(32).also(SecureRandom()::nextBytes).joinToString("") { "%02x".format(it) }
    private val authorization = "Bearer $token".toByteArray(Charsets.US_ASCII)
    private val sockets = mutableSetOf<Socket>()
    private val deadlines = ScheduledThreadPoolExecutor(1).apply { removeOnCancelPolicy = true }
    private val workers = ThreadPoolExecutor(3, 3, 0, TimeUnit.MILLISECONDS, ArrayBlockingQueue(4))

    init { featureThread(trace = null) { accept() } }

    private fun accept() {
        while (!server.isClosed) {
            val client = try { server.accept() }
            catch (e: IOException) {
                if (!server.isClosed) {
                    AppDiagnostics.begin("mcp_listener").fail(FailureCode.IO, "Local MCP listener stopped", e)
                    close()
                }
                return
            }
            synchronized(sockets) {
                if (server.isClosed) {
                    client.close()
                    return
                }
                sockets.add(client)
                try {
                    workers.execute {
                        val timeout = synchronized(sockets) {
                            if (server.isClosed) return@execute
                            deadlines.schedule({ client.close() }, 20, TimeUnit.SECONDS)
                        }
                        try { client.use { serve(it) } }
                        catch (_: SocketTimeoutException) { /* An idle client timed out. */ }
                        catch (_: IOException) { /* Client disconnected, including listener shutdown. */ }
                        finally {
                            timeout.cancel(false)
                            synchronized(sockets) { sockets.remove(client) }
                        }
                    }
                } catch (_: RejectedExecutionException) {
                    sockets.remove(client)
                    client.close()
                }
            }
        }
    }

    private fun serve(socket: Socket) {
        val reader = BufferedInputStream(socket.getInputStream())
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        fun read(): Int {
            val remaining = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime())
            if (remaining <= 0) throw SocketTimeoutException()
            socket.soTimeout = remaining.toInt()
            return reader.read()
        }
        fun line(): String {
            val chars = StringBuilder()
            while (chars.length < 4096) {
                val byte = read()
                if (byte == 13 && read() == 10) return chars.toString()
                if (byte !in 32..126 && byte != 9) throw InvalidHttp(400)
                chars.append(byte.toChar())
            }
            throw InvalidHttp(431)
        }
        val response = try {
            val request = line().split(' ')
            if (request.size != 3 || request[2] != "HTTP/1.1") throw InvalidHttp(400)
            val headers = HashMap<String, String>()
            while (true) {
                val header = line()
                if (header.isEmpty()) break
                if (headers.size >= 32) throw InvalidHttp(431)
                val colon = header.indexOf(':')
                if (colon <= 0) throw InvalidHttp(400)
                val key = header.substring(0, colon).lowercase(java.util.Locale.ROOT)
                if (!key.matches(Regex("[a-z0-9-]+")) || headers.put(key, header.substring(colon + 1).trim()) != null) {
                    throw InvalidHttp(400)
                }
            }
            val denied = when {
                headers["host"] !in setOf("127.0.0.1:$port", "localhost:$port") -> 403
                "origin" in headers -> 403 // Native clients use the pairing token and omit Origin.
                !MessageDigest.isEqual(authorization, headers["authorization"].orEmpty().toByteArray(Charsets.US_ASCII)) -> 401
                else -> null
            }
            if (request[1] != "/mcp") throw InvalidHttp(denied ?: 404)
            if (request[0] != "POST") throw InvalidHttp(denied ?: 405)
            if ("transfer-encoding" in headers || "expect" in headers) throw InvalidHttp(400)
            if (headers["content-type"]?.substringBefore(';')?.trim() != "application/json") throw InvalidHttp(415)
            val version = headers["mcp-protocol-version"]
            if (version != null && version !in MCP_PROTOCOL_VERSIONS) throw InvalidHttp(400)
            val size = headers["content-length"]?.toIntOrNull()?.takeIf { it >= 0 } ?: throw InvalidHttp(400)
            if (size > MCP_REQUEST_LIMIT) throw InvalidHttp(413)
            val body = ByteArray(size)
            // Switch to bounded bulk reads after the headers.
            var offset = 0
            while (offset < size) {
                val remaining = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime())
                if (remaining <= 0) throw SocketTimeoutException()
                socket.soTimeout = remaining.toInt()
                val n = reader.read(body, offset, size - offset)
                if (n < 0) throw InvalidHttp(400)
                offset += n
            }
            // Finish reading a bounded request before replying, so closing the
            // socket cannot reset a denial response while the client sends its body.
            if (denied != null) throw InvalidHttp(denied)
            protocol.handle(body)
        } catch (e: InvalidHttp) {
            McpResponse(e.status)
        }
        val payload = response.body?.toString()?.toByteArray(Charsets.UTF_8) ?: ByteArray(0)
        socket.getOutputStream().apply {
            val reason = when (response.status) {
                200 -> "OK"
                202 -> "Accepted"
                400 -> "Bad Request"
                401 -> "Unauthorized"
                403 -> "Forbidden"
                404 -> "Not Found"
                405 -> "Method Not Allowed"
                413 -> "Content Too Large"
                415 -> "Unsupported Media Type"
                else -> "Request Header Fields Too Large"
            }
            val headers = buildString {
                append("HTTP/1.1 ${response.status} $reason\r\nConnection: close\r\nCache-Control: no-store\r\n")
                if (response.status == 401) append("WWW-Authenticate: Bearer\r\n")
                if (response.status == 405) append("Allow: POST\r\n")
                if (response.body != null) append("Content-Type: application/json; charset=utf-8\r\n")
                append("Content-Length: ${payload.size}\r\n\r\n")
            }
            write(headers.toByteArray(Charsets.US_ASCII))
            write(payload)
            flush()
        }
        socket.shutdownOutput()
    }

    override fun close() {
        try {
            synchronized(sockets) {
                if (server.isClosed) return
                try {
                    server.close()
                    sockets.forEach { socket -> observedAttempt("mcp_socket_close") { socket.close() } }
                } finally {
                    sockets.clear()
                    workers.shutdownNow()
                    deadlines.shutdownNow()
                }
            }
        } finally {
            tasks.close()
        }
    }

    private class InvalidHttp(val status: Int) : Exception()
}
