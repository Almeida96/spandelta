package io.github.almeida96.spandelta

import com.fasterxml.jackson.core.JsonFactory
import com.fasterxml.jackson.core.StreamReadConstraints
import com.fasterxml.jackson.core.StreamReadFeature
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import java.io.InputStream

internal val json =
    ObjectMapper(
            JsonFactory.builder()
                .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                .streamReadConstraints(
                    StreamReadConstraints.builder()
                        .maxNestingDepth(48)
                        .maxStringLength(1_048_576)
                        .build()
                )
                .build()
        )
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)

internal const val MAX_BYTES = 4 * 1024 * 1024
internal const val MAX_BATCH = 10_000
internal const val MAX_DATASET = 50_000
internal const val VERSION = "0.1.0"
internal val datasetPattern = Regex("[a-zA-Z0-9][a-zA-Z0-9._-]{0,63}")

internal fun datasetName(value: String): String {
    require(datasetPattern.matches(value)) {
        "A dataset name must contain 1–64 letters, digits, periods, underscores or hyphens."
    }
    return value
}

internal fun boundedRead(input: InputStream): ByteArray {
    val bytes = input.readNBytes(MAX_BYTES + 1)
    if (bytes.size > MAX_BYTES) throw PayloadLimit()
    return bytes
}

internal class PayloadLimit : IllegalArgumentException("Payload exceeds 4 MiB.")

internal class SpanConflict :
    IllegalArgumentException("An existing span ID has different retained fields.")

internal data class Op(val service: String, val operation: String, val kind: Int)

internal data class Span(
    val traceId: String,
    val spanId: String,
    val parentId: String?,
    val service: String,
    val operation: String,
    val kind: Int,
    val start: Long,
    val end: Long,
    val status: Int,
) {
    val duration: Long
        get() = end - start

    val key: Op
        get() = Op(service, operation, kind)

    init {
        checkedId(traceId, 32)
        checkedId(spanId, 16)
        parentId?.let { checkedId(it, 16) }
        for (label in listOf(service, operation)) {
            require(label.isNotBlank() && label.length <= 256 && label.none(Char::isISOControl)) {
                "Service and operation names must contain 1–256 printable characters."
            }
        }
        require(kind in 0..5 && status in 0..2) { "Unsupported span kind or status code." }
        require(start >= 0 && end >= start) { "Invalid span timestamps." }
    }
}

internal fun checkedId(id: String, length: Int): String {
    require(
        id.length == length && id.all { it in '0'..'9' || it in 'a'..'f' } && id.any { it != '0' }
    ) {
        "Invalid ${if (length == 32) "trace" else "span"} ID."
    }
    return id
}
