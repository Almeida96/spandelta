package io.github.almeida96.spandelta

import com.fasterxml.jackson.databind.node.ObjectNode
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.trace.SpanKind
import io.opentelemetry.api.trace.StatusCode
import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter
import io.opentelemetry.sdk.resources.Resource
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.test.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class WireTest {
    @TempDir lateinit var dir: Path
    private val token = "fixture-collector-token"

    @Test
    fun officialExporterSendsCompressedProtobufAndContextAcrossServices() {
        Ledger(dir.resolve("wire.db")).use { db ->
            Receiver(db, "sdk", token, 0).use { receiver ->
                fun provider(service: String): SdkTracerProvider {
                    val exporter =
                        OtlpHttpSpanExporter.builder()
                            .setEndpoint("http://127.0.0.1:${receiver.port}/v1/traces")
                            .addHeader("Authorization", "Bearer $token")
                            .setCompression("gzip")
                            .build()
                    val resource =
                        Resource.create(
                            Attributes.of(AttributeKey.stringKey("service.name"), service)
                        )
                    return SdkTracerProvider.builder()
                        .setResource(resource)
                        .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                        .build()
                }
                provider("checkout").use { front ->
                    provider("payment").use { back ->
                        val root =
                            front
                                .get("wire")
                                .spanBuilder("POST /checkout")
                                .setSpanKind(SpanKind.SERVER)
                                .startSpan()
                        root.makeCurrent().use {
                            val child =
                                back
                                    .get("wire")
                                    .spanBuilder("authorize")
                                    .setSpanKind(SpanKind.INTERNAL)
                                    .startSpan()
                            child.setAttribute("credit.card", "private-fixture-value")
                            child.setStatus(StatusCode.ERROR)
                            child.end()
                        }
                        root.end()
                        for (sdk in listOf(front, back)) assertTrue(
                            sdk.forceFlush().join(10, TimeUnit.SECONDS).isSuccess
                        )
                        val trace = db.inspect("sdk", root.spanContext.traceId)
                        assertEquals(1, trace.roots)
                        assertEquals(2, trace.spans.size)
                        val child = trace.spans.single { it.service == "payment" }
                        assertEquals(root.spanContext.spanId, child.parentId)
                        assertTrue(child.error)
                        assertFalse(
                            json.writeValueAsString(trace).contains("private-fixture-value")
                        )
                    }
                }
            }
        }
    }

    @Test
    fun receiverEnforcesAuthenticationAndRollsBackBadRequests() {
        Ledger(dir.resolve("http.db")).use { db ->
            Receiver(db, "api", token, 0).use { receiver ->
                HttpClient.newHttpClient().use { client ->
                    fun post(
                        bytes: ByteArray,
                        secret: String,
                        type: String = "application/json",
                    ): Int {
                        val req =
                            HttpRequest.newBuilder(
                                    URI("http://127.0.0.1:${receiver.port}/v1/traces")
                                )
                                .header("Authorization", "Bearer $secret")
                                .header("Content-Type", type)
                                .POST(HttpRequest.BodyPublishers.ofByteArray(bytes))
                                .build()
                        return client.send(req, HttpResponse.BodyHandlers.ofString()).statusCode()
                    }
                    assertEquals(401, post(Demo.payload(false), "wrong"))
                    assertEquals(415, post(byteArrayOf(), token, "text/plain"))
                    assertEquals(400, post("not-json".toByteArray(), token))
                    assertEquals(200, post(Demo.payload(false), token))
                    assertEquals(200, post(Demo.payload(false), token))
                    assertEquals(Dataset("api", 120, 40), db.datasets().single())
                }
            }
        }
    }

    @Test
    fun malformedRecordsAndUnknownRetentionFields() {
        val doc = json.readTree(Demo.payload(false))
        val first =
            doc.path("resourceSpans")[0].path("scopeSpans")[0].path("spans")[0] as ObjectNode
        first.put("extraUnknown", "ignore")
        first.putArray("attributes").addObject().put("key", "sensitive-value")
        assertEquals(120, Otlp.decode(json.writeValueAsBytes(doc), false).size)
        assertFalse(
            json
                .writeValueAsString(Otlp.decode(json.writeValueAsBytes(doc), false))
                .contains("sensitive-value")
        )
        first.put("traceId", "0".repeat(32))
        assertFailsWith<IllegalArgumentException> {
            Otlp.decode(json.writeValueAsBytes(doc), false)
        }
    }

    @Test
    fun invalidTimestampEnumAndDuplicateJsonKeysAreRejected() {
        for ((field, value) in
            listOf(
                "startTimeUnixNano" to "18446744073709551615",
                "kind" to "SERVER",
                "endTimeUnixNano" to "-1",
            )) {
            val doc = json.readTree(Demo.payload(false))
            val span =
                doc.path("resourceSpans")[0].path("scopeSpans")[0].path("spans")[0] as ObjectNode
            span.put(field, value)
            assertFailsWith<IllegalArgumentException> {
                Otlp.decode(json.writeValueAsBytes(doc), false)
            }
        }
        assertFailsWith<IllegalArgumentException> {
            Otlp.decode("""{"resourceSpans":[],"resourceSpans":[]}""".toByteArray(), false)
        }
    }

    @Test
    fun boundedInputRejectsAnExtraByte() {
        assertFailsWith<PayloadLimit> { boundedRead(ByteArray(MAX_BYTES + 1).inputStream()) }
        assertEquals(MAX_BYTES, boundedRead(ByteArray(MAX_BYTES).inputStream()).size)
    }
}
