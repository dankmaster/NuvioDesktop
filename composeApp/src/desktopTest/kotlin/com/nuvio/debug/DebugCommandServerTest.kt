package com.nuvio.debug

import java.net.Socket
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.serialization.json.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DebugCommandServerTest {
    @Test
    fun onlyAuthenticatedBoundedJsonCommandsReachTheHandlerAndShutdownRemovesTheToken() {
        val directory = Files.createTempDirectory("nuvio-debug-test").toFile()
        val sessionFile = directory.resolve("session.json")
        val calls = AtomicInteger()
        try {
            DebugCommandServer(sessionFile) { calls.incrementAndGet(); debugOk() }.use {
                val session = Json.parseToJsonElement(sessionFile.readText()).jsonObject
                fun send(line: String): JsonObject = Socket("127.0.0.1", session.getValue("port").jsonPrimitive.int).use { socket ->
                    socket.soTimeout = 2000
                    socket.getOutputStream().write((line + "\n").toByteArray())
                    Json.parseToJsonElement(socket.getInputStream().bufferedReader().readLine()).jsonObject
                }
                assertEquals("unauthorized", send("{\"command\":\"status\",\"token\":\"wrong\"}").getValue("error").jsonPrimitive.content)
                assertEquals("invalid-request", send("[]").getValue("error").jsonPrimitive.content)
                assertEquals("invalid-request", send(" ".repeat(65_537)).getValue("error").jsonPrimitive.content)
                assertEquals(0, calls.get())
                val request = buildJsonObject { put("token", session.getValue("token")); put("command", "status") }
                assertTrue(send(request.toString()).getValue("ok").jsonPrimitive.boolean)
                assertEquals(1, calls.get())
            }
            assertFalse(sessionFile.exists())
        } finally {
            directory.deleteRecursively()
        }
    }
}
