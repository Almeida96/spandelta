package io.github.almeida96.spandelta

import java.io.ByteArrayOutputStream
import java.nio.file.Path
import kotlin.test.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class McpTest {
    @TempDir lateinit var dir: Path

    private fun request(id: Int, method: String, params: Any = emptyMap<String, Any>()) =
        json.writeValueAsString(
            mapOf("jsonrpc" to "2.0", "id" to id, "method" to method, "params" to params)
        )

    private val initialize =
        request(
            1,
            "initialize",
            mapOf(
                "clientInfo" to mapOf("name" to "fixture", "version" to "1"),
                "protocolVersion" to "2025-11-25",
                "capabilities" to emptyMap<String, Any>(),
            ),
        )
    private val ready = """{"jsonrpc":"2.0","method":"notifications/initialized"}"""

    private fun session(vararg messages: String): List<com.fasterxml.jackson.databind.JsonNode> {
        val db = dir.resolve("mcp.db")
        Ledger(db).use { store ->
            for ((name, candidate) in listOf("a" to false, "b" to true)) store.import(
                name,
                Otlp.decode(Demo.payload(candidate), false),
            )
        }
        return Ledger(db, readOnly = true).use { store ->
            val out = ByteArrayOutputStream()
            Mcp(store).serve(messages.joinToString("\n", postfix = "\n").byteInputStream(), out)
            out.toString(Charsets.UTF_8)
                .lineSequence()
                .filter { it.isNotBlank() }
                .map(json::readTree)
                .toList()
        }
    }

    @Test
    fun sessionRequiresInitializationAndIgnoresNotifications() {
        val replies = session(request(0, "tools/list"), initialize, ready, request(2, "tools/list"))
        assertEquals(3, replies.size)
        assertEquals(-32002, replies[0].path("error").path("code").asInt())
        assertEquals("2025-11-25", replies[1].path("result").path("protocolVersion").asText())
        val tools = replies.last().path("result").path("tools")
        assertEquals(3, tools.size())
        assertTrue(tools.all { it.path("annotations").path("readOnlyHint").asBoolean() })
    }

    @Test
    fun comparisonAndTraceEvidenceArePagedAndReadOnly() {
        val compare =
            mapOf(
                "name" to "compare_releases",
                "arguments" to mapOf("baseline" to "a", "candidate" to "b", "limit" to 1),
            )
        val traceId = Otlp.decode(Demo.payload(true), false).first().traceId
        val inspect =
            mapOf(
                "name" to "inspect_trace",
                "arguments" to
                    mapOf("dataset" to "b", "traceId" to traceId, "offset" to 1, "limit" to 1),
            )
        val replies =
            session(
                initialize,
                ready,
                request(2, "tools/call", compare),
                request(3, "tools/call", inspect),
            )
        val comparison = replies[1].path("result").path("structuredContent")
        assertEquals("REGRESSION", comparison.path("verdict").asText())
        assertEquals(3, comparison.path("total").asInt())
        assertEquals(1, comparison.path("operations").size())
        assertEquals(1, comparison.path("nextOffset").asInt())
        val trace = replies[2].path("result").path("structuredContent")
        assertEquals(traceId, trace.path("traceId").asText())
        assertEquals(1, trace.path("spans").size())
    }

    @Test
    fun malformedMessagesAndToolErrorsDoNotCorruptNextRequest() {
        val invalid = mapOf("name" to "list_datasets", "arguments" to mapOf("delete" to "b"))
        val replies =
            session(
                "x".repeat(132000),
                "{broken",
                initialize,
                ready,
                request(2, "tools/call", invalid),
                request(3, "ping"),
            )
        assertEquals(-32600, replies[0].path("error").path("code").asInt())
        assertEquals(-32700, replies[1].path("error").path("code").asInt())
        assertTrue(replies[3].path("result").path("isError").asBoolean())
        assertTrue(replies.last().path("result").isObject)
    }
}
