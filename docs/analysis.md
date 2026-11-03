# Reading a release comparison

## Cohorts

Each dataset is a named collection of spans. Import several batches into the same dataset to accumulate observations. A comparison reads both cohorts in one SQLite snapshot. A generated report also reads its example traces inside that transaction. A separate CLI or MCP query sees the latest committed data, so finish collection before comparing if you need a repeatable result.

Operations are grouped by the tuple of service name, operation name and OTLP span kind. A client span called `authorize` and a server span called `authorize` remain separate. Different route names, retries, and sampling policies can change the population. Names should normally identify a route template or operation instead of an individual request.

## Measurements

Each span contributes its elapsed wall-clock duration to one operation. Values are sorted by duration, trace ID and span ID. For a percentile `p`, choose the span at one-based rank `ceil(p × count)`. This gives an observed p50, p95, and a reproducible p95 exemplar. Errors are spans with OTLP status code 2. The error-rate exemplar is the longest observed error span, with deterministic ID ordering for ties.

The default gate requires 20 spans per side. A latency signal requires an increase that reaches both 10 ms and 20% at p95. When baseline p95 is zero, a positive increase is eligible using the absolute threshold, and the relative delta is JSON null. An error signal requires a positive increase of at least 2 percentage points. Changing a threshold to zero still requires a positive increase.

| Operation state | Interpretation |
| --- | --- |
| REGRESSION | Both cohorts have enough samples and at least one threshold is crossed. |
| STABLE | Comparable observations meet neither regression condition. |
| LOW_SAMPLE | The operation appears in both cohorts, with insufficient count on at least one side. |
| MISSING | Only the baseline contains this operation. |
| NEW | Only the candidate contains this operation. |

Any regression takes precedence in the overall verdict. Otherwise missing coverage, low samples, or zero comparable operations produce INCONCLUSIVE. New candidate operations appear separately and permit PASS when all baseline operations have enough evidence and remain stable. Unknown or empty datasets produce an input error.

These thresholds are descriptive rules, not statistical confidence intervals. Counts measure captured spans, not request rates. A different traffic mix, clock offset or sampling policy can explain a change. Inspect the exemplars and capture conditions before attributing a cause.

## Trace timelines

The timeline origin is the earliest captured start. Elapsed time covers the first start to the last end. For each span, uncovered time is its duration minus the union of direct-child intervals clipped to the parent's time range. Two child intervals from 10–70 ms and 50–120 ms under a 0–100 ms parent cover 90 ms in total, leaving 10 ms uncovered.

This quantity includes waiting and uninstrumented work. It cannot measure CPU time. A partial trace may overstate uncovered time. The inspector flags missing parents, unexpected root counts, parent cycles, and children whose clocks place them outside the parent interval. Durations and offsets are reported in milliseconds. Relative timing assumes comparable clocks across services.

A report includes up to 200 operations and three distinct candidate exemplar traces. Each waterfall shows the first 80 spans in start order. The JSON comparison contains every operation. The CLI inspector supports traces up to 2,000 spans. MCP tools paginate their results.
