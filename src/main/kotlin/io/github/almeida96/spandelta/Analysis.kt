package io.github.almeida96.spandelta

import kotlin.math.ceil

internal data class Policy(
    val minSamples: Int = 20,
    val minDeltaMs: Double = 10.0,
    val minDeltaPct: Double = 20.0,
    val errorDeltaPp: Double = 2.0,
) {
    init {
        require(minSamples in 1..MAX_DATASET) { "minSamples must be in 1..50,000." }
        require(listOf(minDeltaMs, minDeltaPct, errorDeltaPp).all { it.isFinite() && it >= 0 }) {
            "Thresholds must be finite and nonnegative."
        }
        require(errorDeltaPp <= 100) { "errorDeltaPp must be at most 100." }
    }
}

internal data class Example(val traceId: String, val spanId: String)

internal data class Stats(
    val count: Int,
    val p50Ms: Double,
    val p95Ms: Double,
    val errors: Int,
    val errorPct: Double,
    val p95Example: Example,
    val errorExample: Example?,
)

internal data class Change(
    val key: Op,
    val state: String,
    val baseline: Stats?,
    val candidate: Stats?,
    val deltaMs: Double?,
    val deltaPct: Double?,
    val errorDeltaPp: Double?,
    val signals: List<String>,
)

internal data class Comparison(
    val baseline: String,
    val candidate: String,
    val policy: Policy,
    val verdict: String,
    val counts: Map<String, Int>,
    val operations: List<Change>,
)

private fun summarize(spans: List<Span>): Stats {
    val ordered = spans.sortedWith(compareBy(Span::duration, Span::traceId, Span::spanId))
    fun percentile(fraction: Double) = ordered[ceil(fraction * ordered.size).toInt() - 1]
    val errors = ordered.filter { it.status == 2 }
    fun Span.example() = Example(traceId, spanId)
    val tail = percentile(0.95)
    return Stats(
        spans.size,
        percentile(0.50).duration / 1e6,
        tail.duration / 1e6,
        errors.size,
        errors.size * 100.0 / spans.size,
        tail.example(),
        errors.lastOrNull()?.example(),
    )
}

private val rank = listOf("REGRESSION", "MISSING", "LOW_SAMPLE", "NEW", "STABLE")

internal fun compare(
    baselineName: String,
    baseline: List<Span>,
    candidateName: String,
    candidate: List<Span>,
    policy: Policy,
): Comparison {
    val before = baseline.groupBy(Span::key)
    val after = candidate.groupBy(Span::key)
    val changes =
        (before.keys + after.keys)
            .map { key ->
                val left = before[key]?.let(::summarize)
                val right = after[key]?.let(::summarize)
                val delta = if (left != null && right != null) right.p95Ms - left.p95Ms else null
                val percent =
                    if (delta != null && left!!.p95Ms > 0) delta * 100 / left.p95Ms else null
                val errors =
                    if (left != null && right != null) right.errorPct - left.errorPct else null
                val signals = mutableListOf<String>()
                val state =
                    when {
                        left == null -> "NEW"
                        right == null -> "MISSING"
                        minOf(left.count, right.count) < policy.minSamples -> "LOW_SAMPLE"
                        else -> {
                            if (
                                delta!! > 0 &&
                                    delta >= policy.minDeltaMs &&
                                    (left.p95Ms == 0.0 || percent!! >= policy.minDeltaPct)
                            ) {
                                signals += "P95_LATENCY"
                            }
                            if (errors!! > 0 && errors >= policy.errorDeltaPp)
                                signals += "ERROR_RATE"
                            if (signals.isEmpty()) "STABLE" else "REGRESSION"
                        }
                    }
                Change(key, state, left, right, delta, percent, errors, signals)
            }
            .sortedWith(
                compareBy<Change> { rank.indexOf(it.state) }
                    .thenByDescending { it.deltaMs }
                    .thenBy { it.key.service }
                    .thenBy { it.key.operation }
                    .thenBy { it.key.kind }
            )
    val counts = changes.groupingBy(Change::state).eachCount().toSortedMap()
    val verdict =
        when {
            "REGRESSION" in counts -> "REGRESSION"
            "STABLE" !in counts || "MISSING" in counts || "LOW_SAMPLE" in counts -> "INCONCLUSIVE"
            else -> "PASS"
        }
    return Comparison(baselineName, candidateName, policy, verdict, counts, changes)
}

internal data class TimelineSpan(
    val spanId: String,
    val parentId: String?,
    val service: String,
    val operation: String,
    val kind: Int,
    val offsetMs: Double,
    val durationMs: Double,
    val uncoveredMs: Double,
    val error: Boolean,
)

internal data class TraceView(
    val dataset: String,
    val traceId: String,
    val elapsedMs: Double,
    val roots: Int,
    val warnings: List<String>,
    val spans: List<TimelineSpan>,
)

internal fun traceView(dataset: String, input: List<Span>): TraceView {
    val spans = input.sortedWith(compareBy(Span::start, Span::spanId))
    val byId = spans.associateBy(Span::spanId)
    val children = spans.groupBy(Span::parentId)
    val warnings = sortedSetOf<String>()
    val roots = children[null].orEmpty().size
    if (roots != 1) warnings += "ROOT_COUNT"
    val visited = mutableSetOf<String>()
    for (span in spans) {
        val path = mutableSetOf<String>()
        var cursor: Span? = span
        while (cursor != null && cursor.spanId !in visited) {
            if (!path.add(cursor.spanId)) {
                warnings += "PARENT_CYCLE"
                break
            }
            cursor = cursor.parentId?.let(byId::get)
        }
        visited.addAll(path)
    }
    val start = spans.first().start
    val timeline = spans.map { span ->
        val parent = span.parentId?.let(byId::get)
        if (span.parentId != null && parent == null) warnings += "MISSING_PARENT"
        if (parent != null && (span.start < parent.start || span.end > parent.end))
            warnings += "OUTSIDE_PARENT"
        // Merge child intervals so concurrent work is only subtracted once.
        var covered = 0L
        var cursor = span.start
        for (child in children[span.spanId].orEmpty()) {
            val from = maxOf(span.start, child.start, cursor)
            val to = minOf(span.end, child.end)
            if (to > from) {
                covered += to - from
                cursor = to
            }
        }
        TimelineSpan(
            span.spanId,
            span.parentId,
            span.service,
            span.operation,
            span.kind,
            (span.start - start) / 1e6,
            span.duration / 1e6,
            (span.duration - covered) / 1e6,
            span.status == 2,
        )
    }
    return TraceView(
        dataset,
        spans.first().traceId,
        (spans.maxOf(Span::end) - start) / 1e6,
        roots,
        warnings.toList(),
        timeline,
    )
}
