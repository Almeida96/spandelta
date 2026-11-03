package io.github.almeida96.spandelta

import kotlin.test.*
import org.junit.jupiter.api.Test

internal fun span(
    n: Int,
    duration: Long = 100,
    trace: Int = 1,
    parent: Int? = null,
    start: Long = 0,
    op: String = "work",
    error: Boolean = false,
) =
    Span(
        trace.toString(16).padStart(32, '0'),
        n.toString(16).padStart(16, '0'),
        parent?.toString(16)?.padStart(16, '0'),
        "app",
        op,
        2,
        start,
        start + duration,
        if (error) 2 else 1,
    )

class AnalysisTest {
    @Test
    fun checkoutExampleSeparatesPaymentAndInventory() {
        val diff =
            compare(
                "a",
                Otlp.decode(Demo.payload(false), false),
                "b",
                Otlp.decode(Demo.payload(true), false),
                Policy(),
            )
        assertEquals("REGRESSION", diff.verdict)
        assertEquals(mapOf("REGRESSION" to 2, "STABLE" to 1), diff.counts)
        val payments = diff.operations.single { it.key.service == "payments" }
        assertEquals(89.0, payments.baseline!!.p95Ms)
        assertEquals(169.0, payments.candidate!!.p95Ms)
        assertEquals(12.5, payments.errorDeltaPp)
        assertEquals(listOf("P95_LATENCY", "ERROR_RATE"), payments.signals)
        assertNotNull(payments.candidate.errorExample)
    }

    @Test
    fun nearestRankP95IsAnObservedSpanAndTiesAreDeterministic() {
        val elapsed = (1..20).map { span(it, it * 1_000_000L) }
        val change = compare("a", elapsed, "b", elapsed.reversed(), Policy()).operations.single()
        assertEquals(10.0, change.candidate!!.p50Ms)
        assertEquals(19.0, change.candidate.p95Ms)
        assertEquals(elapsed[18].spanId, change.candidate.p95Example.spanId)
        assertEquals("STABLE", change.state)
    }

    @Test
    fun lowVolumeDoesNotPassEvenWithLargeDelta() {
        val diff = compare("a", listOf(span(1)), "b", listOf(span(1, 9_000_000)), Policy())
        assertEquals("INCONCLUSIVE", diff.verdict)
        assertEquals("LOW_SAMPLE", diff.operations.single().state)
    }

    @Test
    fun missingOperationIsInconclusiveAndKindSeparatesGroups() {
        val before = listOf(span(1), span(2).copy(kind = 3))
        val after = listOf(span(1), span(2, op = "new"))
        val diff = compare("a", before, "b", after, Policy(minSamples = 1))
        assertEquals("INCONCLUSIVE", diff.verdict)
        assertEquals(setOf("STABLE", "MISSING", "NEW"), diff.operations.map { it.state }.toSet())
    }

    @Test
    fun identicalTimingsWithHigherTrafficPass() {
        val diff =
            compare("a", listOf(span(1)), "b", (1..50).map { span(it) }, Policy(minSamples = 1))
        assertEquals("PASS", diff.verdict)
    }

    @Test
    fun zeroDurationBaselineHasDefinedGateButNoPercentage() {
        val change =
            compare(
                    "a",
                    listOf(span(1, 0)),
                    "b",
                    listOf(span(1, 12_000_000)),
                    Policy(minSamples = 1),
                )
                .operations
                .single()
        assertNull(change.deltaPct)
        assertEquals("REGRESSION", change.state)
    }

    @Test
    fun latencyRequiresBothAbsoluteAndRelativeThresholds() {
        val diff =
            compare(
                "a",
                listOf(span(1, 1_000_000)),
                "b",
                listOf(span(1, 2_000_000)),
                Policy(minSamples = 1),
            )
        assertEquals("PASS", diff.verdict)
    }

    @Test
    fun errorsCanFailWithoutLatencyChange() {
        val diff =
            compare(
                "a",
                listOf(span(1)),
                "b",
                listOf(span(1, error = true)),
                Policy(minSamples = 1),
            )
        assertEquals(listOf("ERROR_RATE"), diff.operations.single().signals)
    }

    @Test
    fun concurrentChildrenAreClippedAndSubtractedOnlyOnce() {
        val trace =
            traceView(
                "test",
                listOf(
                    span(1, 100_000_000),
                    span(2, 60_000_000, parent = 1, start = 10_000_000),
                    span(3, 70_000_000, parent = 1, start = 50_000_000),
                ),
            )
        assertEquals(10.0, trace.spans.first().uncoveredMs)
        assertEquals(120.0, trace.elapsedMs)
        assertEquals(listOf("OUTSIDE_PARENT"), trace.warnings)
    }

    @Test
    fun disconnectedAndCyclicTracesRemainInspectable() {
        val trace =
            traceView(
                "test",
                listOf(span(1, parent = 2), span(2, parent = 1), span(3, parent = 55)),
            )
        assertEquals(listOf("MISSING_PARENT", "PARENT_CYCLE", "ROOT_COUNT"), trace.warnings)
        assertEquals(3, trace.spans.size)
    }
}
