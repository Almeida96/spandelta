package io.github.almeida96.spandelta

import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale

internal data class Investigation(
    val comparison: Comparison,
    val traces: List<TraceView>,
    val notes: List<String>,
)

internal fun writeReport(path: Path, review: Investigation) {
    val parent = path.toAbsolutePath().parent
    Files.createDirectories(parent)
    val temporary = Files.createTempFile(parent, ".spandelta-", ".tmp")
    try {
        Files.writeString(temporary, html(review))
        Files.move(temporary, path.toAbsolutePath())
    } finally {
        Files.deleteIfExists(temporary)
    }
}

internal fun html(review: Investigation): String = buildString {
    val diff = review.comparison
    val kindNames = listOf("unspecified", "internal", "server", "client", "producer", "consumer")
    append(
        """<!doctype html><html lang="en"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
        <meta http-equiv="Content-Security-Policy" content="default-src 'none'; style-src 'unsafe-inline'; base-uri 'none'">
        <title>SpanDelta · Release comparison</title><style>
        *{box-sizing:border-box}body{background:#f4f4ef;color:#18312e;font:15px/1.55 system-ui,sans-serif;margin:0}
        main{max-width:1180px;margin:auto;padding:40px 24px}header,section{border:1px solid #d2d8ce;background:white;margin-bottom:24px;padding:24px;border-radius:12px}
        header{background:#143731;color:white;border:0}h1{font-size:clamp(24px,5vw,42px);margin:.3em 0}h2{font-size:20px;margin:0 0 .5em}
        small,.muted{color:#526961}.brand{letter-spacing:.13em;font-size:12px;font-weight:700;color:#c4e3ce}header p{color:#d6e6dd}
        .tag{font:700 12px system-ui;border-radius:5px;padding:4px 7px;background:#e5ece6;color:#254e42;display:inline-block}
        .REGRESSION{color:#912d22;background:#f8ded5}.LOW_SAMPLE,.INCONCLUSIVE,.MISSING{background:#ffedc8;color:#75510a}
        .scroll{overflow-x:auto}table{width:100%;border-collapse:collapse;text-align:left;font-size:14px}td,th{padding:9px 10px;vertical-align:top;border-bottom:1px solid #dae0d7}
        th{color:#385c4e;background:#f2f5ef}td{overflow-wrap:anywhere}code{font-size:12px}details{margin:12px 0;border-top:1px solid #dde1d9;padding-top:10px}
        summary{cursor:pointer;font-weight:650}.track{background:#edf0eb;height:11px;min-width:100px;border-radius:4px;position:relative}.bar{position:absolute;background:#347c68;min-width:1px;height:11px;border-radius:4px}.bad{background:#b65338}
        a{color:#236855;text-underline-offset:2px}details:target{outline:2px solid #5d967d;border-radius:6px;padding:10px}footer{color:#526961;font-size:13px} @media(max-width:600px){main{padding:16px 8px}header,section{padding:14px}}
        </style><main><header><div class="brand">SPANDELTA / RELEASE COMPARISON</div>"""
    )
    append("<h1>${escape(diff.baseline)} &rarr; ${escape(diff.candidate)}</h1>")
    append("<span class='tag ${diff.verdict}'>${diff.verdict}</span>")
    append(
        "<p>${diff.operations.size} operations compared · ${diff.counts["REGRESSION"] ?: 0} flagged · ${diff.counts["LOW_SAMPLE"] ?: 0} below sample minimum</p></header>"
    )
    append(
        "<section><h2>Operations</h2><div class='scroll'><table><tr><th>Service / operation</th><th>Result</th><th>Spans<br>before / after</th><th>p95 ms<br>before / after</th><th>Error %<br>before / after</th></tr>"
    )
    for (change in diff.operations.take(200)) {
        append(
            "<tr><td><strong>${escape(change.key.service)}</strong><br>${escape(change.key.operation)} <small>· ${kindNames[change.key.kind]}</small></td>"
        )
        append(
            "<td><span class='tag ${change.state}'>${change.state}</span><br><small>${change.signals.joinToString(" + ")}</small>"
        )
        val refs =
            listOf(
                "p95 trace" to change.candidate?.p95Example,
                "error trace" to change.candidate?.errorExample,
            )
        for ((label, ref) in refs) {
            if (ref != null && review.traces.any { it.traceId == ref.traceId }) {
                append("<br><a href='#trace-${ref.traceId}' title='Span ${ref.spanId}'>$label</a>")
            }
        }
        append("</td>")
        append(
            "<td>${change.baseline?.count ?: "–"} / ${change.candidate?.count ?: "–"}</td><td>${fmt(change.baseline?.p95Ms)} / ${fmt(change.candidate?.p95Ms)}</td>"
        )
        append(
            "<td>${fmt(change.baseline?.errorPct)} / ${fmt(change.candidate?.errorPct)}</td></tr>"
        )
    }
    append("</table></div>")
    if (diff.operations.size > 200)
        append(
            "<p>Showing 200 of ${diff.operations.size} operations. The JSON comparison includes all rows.</p>"
        )
    append("</section>")
    append(
        "<section><h2>Decision policy</h2><p>At least ${diff.policy.minSamples} spans per side. A p95 increase must reach both ${fmt(diff.policy.minDeltaMs)} ms and ${fmt(diff.policy.minDeltaPct)}%. An error-rate increase must reach ${fmt(diff.policy.errorDeltaPp)} percentage points.</p>"
    )
    append(
        "<p class='muted'>Percentiles use the nearest observed rank. Counts describe captured spans. Sampling and traffic mix can differ between datasets.</p></section>"
    )
    append("<section><h2>Candidate trace evidence</h2>")
    if (review.traces.isEmpty()) append("<p>No candidate exemplar is available.</p>")
    for (trace in review.traces) {
        append(
            "<details open id='trace-${trace.traceId}'><summary><code>${trace.traceId}</code> · ${fmt(trace.elapsedMs)} ms</summary>"
        )
        if (trace.warnings.isNotEmpty())
            append("<p>Trace warnings: ${trace.warnings.joinToString(", ")}</p>")
        append(
            "<div class='scroll'><table><tr><th>Span</th><th>Relative time</th><th>Elapsed ms</th><th>Uncovered ms</th></tr>"
        )
        for (span in trace.spans.take(80)) {
            val scale = trace.elapsedMs.takeIf { it > 0 } ?: 1.0
            append(
                "<tr><td><strong>${escape(span.service)}</strong><br>${escape(span.operation)}<br><code>${span.spanId}</code></td>"
            )
            append(
                "<td><div class='track'><i class='bar ${if (span.error) "bad" else ""}' style='left:${fmt(100 * span.offsetMs / scale)}%;width:${fmt(100 * span.durationMs / scale)}%'></i></div></td>"
            )
            append("<td>${fmt(span.durationMs)}</td><td>${fmt(span.uncoveredMs)}</td></tr>")
        }
        append(
            "</table></div><p class='muted'>Showing ${minOf(80, trace.spans.size)} of ${trace.spans.size} spans. Uncovered time excludes overlapping direct-child intervals once.</p></details>"
        )
    }
    review.notes.forEach { append("<p>${escape(it)}</p>") }
    append(
        "</section><footer>SpanDelta ${VERSION} · Offline report · Operation names originate from telemetry.</footer></main></html>"
    )
}

private fun fmt(number: Double?) = number?.let { String.format(Locale.ROOT, "%.2f", it) } ?: "–"

private fun escape(text: String) =
    text
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&#39;")
