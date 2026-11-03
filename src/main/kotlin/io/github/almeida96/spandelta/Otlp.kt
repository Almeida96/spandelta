package io.github.almeida96.spandelta

import com.fasterxml.jackson.databind.JsonNode
import io.opentelemetry.proto.collector.trace.v1.ExportTraceServiceRequest
import java.util.HexFormat
import java.util.Locale

internal object Otlp {
    private const val UNKNOWN = "unknown_service"

    fun decode(bytes: ByteArray, protobuf: Boolean): List<Span> {
        require(bytes.size <= MAX_BYTES) { "Payload exceeds 4 MiB." }
        // Wire decoders can include the input in exceptions. Expose only the validation error.
        return try {
            if (protobuf) binary(bytes) else fromJson(bytes)
        } catch (e: java.io.IOException) {
            throw IllegalArgumentException("Invalid OTLP trace payload.")
        }
    }

    private fun binary(bytes: ByteArray): List<Span> = buildList {
        val request = ExportTraceServiceRequest.parseFrom(bytes)
        for (resource in request.resourceSpansList) {
            val service =
                resource.resource.attributesList
                    .firstOrNull { it.key == "service.name" }
                    ?.value
                    ?.stringValue
                    ?.takeIf { it.isNotBlank() } ?: UNKNOWN
            for (scope in resource.scopeSpansList) for (s in scope.spansList) {
                require(size < MAX_BATCH) { "Batch exceeds 10,000 spans." }
                val hex = HexFormat.of()
                add(
                    Span(
                        hex.formatHex(s.traceId.toByteArray()),
                        hex.formatHex(s.spanId.toByteArray()),
                        hex.formatHex(s.parentSpanId.toByteArray()).rootOrId(),
                        service,
                        s.name,
                        s.kindValue,
                        s.startTimeUnixNano,
                        s.endTimeUnixNano,
                        s.status.codeValue,
                    )
                )
            }
        }
    }

    private fun fromJson(bytes: ByteArray): List<Span> = buildList {
        val root = json.readTree(bytes)
        require(root != null && root.isObject) { "Expected an OTLP JSON object." }
        for (resource in root.childrenOf("resourceSpans")) {
            require(!resource.has("resource") || resource.path("resource").isObject) {
                "Expected a resource object."
            }
            val service =
                resource
                    .path("resource")
                    .childrenOf("attributes")
                    .firstOrNull { it.path("key").asText() == "service.name" }
                    ?.path("value")
                    ?.path("stringValue")
                    ?.takeIf { it.isTextual }
                    ?.asText()
                    ?.takeIf { it.isNotBlank() } ?: UNKNOWN
            for (scope in resource.childrenOf("scopeSpans")) for (span in
                scope.childrenOf("spans")) {
                require(size < MAX_BATCH) { "Batch exceeds 10,000 spans." }
                require(!span.has("status") || span.path("status").isObject) {
                    "Expected a span status object."
                }
                add(
                    Span(
                        span.string("traceId").lowercase(Locale.ROOT),
                        span.string("spanId").lowercase(Locale.ROOT),
                        span.path("parentSpanId").let {
                            if (it.isMissingNode) null
                            else span.string("parentSpanId").lowercase(Locale.ROOT).rootOrId()
                        },
                        service,
                        span.string("name"),
                        span.numericEnum("kind"),
                        span.timestamp("startTimeUnixNano"),
                        span.timestamp("endTimeUnixNano"),
                        span.path("status").numericEnum("code"),
                    )
                )
            }
        }
    }

    private fun String.rootOrId(): String? = takeUnless { isEmpty() || this == "0000000000000000" }

    private fun JsonNode.childrenOf(name: String): Iterable<JsonNode> {
        val value = get(name) ?: return emptyList()
        require(value.isArray && value.all { it.isObject }) {
            "Expected an array of objects in $name."
        }
        return value
    }

    private fun JsonNode.string(name: String): String {
        val value = path(name)
        require(value.isTextual) { "Expected text in $name." }
        return value.asText()
    }

    private fun JsonNode.numericEnum(name: String): Int {
        val value = get(name) ?: return 0
        require(value.isIntegralNumber && value.canConvertToInt()) {
            "Expected a numeric enum in $name."
        }
        return value.intValue()
    }

    private fun JsonNode.timestamp(name: String): Long {
        val value = path(name)
        require(value.isIntegralNumber || value.isTextual) { "Missing or invalid $name." }
        return value.asText().toLongOrNull() ?: throw IllegalArgumentException("Invalid $name.")
    }
}
