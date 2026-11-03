package io.github.almeida96.spandelta

import java.nio.file.Files
import java.nio.file.Path

internal object Demo {
    fun payload(candidate: Boolean): ByteArray {
        val services = linkedMapOf<String, MutableList<Map<String, Any>>>()
        repeat(40) { index ->
            val trace = (index + 1 + if (candidate) 100 else 0).toString(16).padStart(32, '0')
            val at = 1_790_000_000_000_000_000L + index * 1_000_000_000L
            val jitter = index % 7
            fun add(
                service: String,
                operation: String,
                id: Int,
                offset: Int,
                duration: Int,
                error: Boolean,
                root: Boolean = false,
            ) {
                val fields =
                    mutableMapOf<String, Any>(
                        "traceId" to trace,
                        "spanId" to id.toString(16).padStart(16, '0'),
                        "name" to operation,
                        "kind" to 2,
                        "startTimeUnixNano" to (at + offset * 1_000_000L).toString(),
                        "endTimeUnixNano" to (at + (offset + duration) * 1_000_000L).toString(),
                        "status" to mapOf("code" to if (error) 2 else 1),
                    )
                if (!root) fields["parentSpanId"] = "0000000000000001"
                services.getOrPut(service) { mutableListOf() }.add(fields)
            }
            val error = candidate && index % 8 == 0
            add(
                "checkout",
                "POST /checkout",
                1,
                0,
                (if (candidate) 225 else 145) + jitter,
                error,
                root = true,
            )
            add("inventory", "reserve", 2, 8, 42 + jitter, false)
            add("payments", "authorize", 3, 24, (if (candidate) 163 else 83) + jitter, error)
        }
        return json.writeValueAsBytes(
            mapOf(
                "resourceSpans" to
                    services.map { (name, spans) ->
                        mapOf(
                            "resource" to
                                mapOf(
                                    "attributes" to
                                        listOf(
                                            mapOf(
                                                "key" to "service.name",
                                                "value" to mapOf("stringValue" to name),
                                            )
                                        )
                                ),
                            "scopeSpans" to
                                listOf(
                                    mapOf(
                                        "scope" to mapOf("name" to "checkout-example"),
                                        "spans" to spans,
                                    )
                                ),
                        )
                    }
            )
        )
    }

    fun run(out: Path): Map<String, Any> {
        Files.createDirectories(out.toAbsolutePath().parent)
        require(!Files.exists(out)) { "Demo output must be a new directory." }
        Files.createDirectory(out)
        Ledger(out.resolve("traces.db")).use { db ->
            for ((name, candidate) in listOf("baseline" to false, "candidate" to true)) {
                val bytes = payload(candidate)
                Files.write(out.resolve("$name.json"), bytes)
                db.import(name, Otlp.decode(bytes, false))
            }
            val review = db.investigate("baseline", "candidate", Policy())
            check(
                review.comparison.counts["REGRESSION"] == 2 &&
                    review.comparison.counts["STABLE"] == 1
            )
            writeReport(out.resolve("report.html"), review)
            Files.writeString(
                out.resolve("comparison.json"),
                json.writerWithDefaultPrettyPrinter().writeValueAsString(review.comparison),
            )
            return mapOf(
                "verdict" to review.comparison.verdict,
                "datasets" to db.datasets(),
                "report" to out.resolve("report.html").toString(),
            )
        }
    }
}
