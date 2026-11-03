Compare baseline and candidate releases using captured OpenTelemetry traces. The initial release includes OTLP/HTTP ingestion, SQLite datasets, percentile and error-rate gates, trace inspection, offline HTML reports, and read-only MCP tools.

Download **checkout-regression.html** to inspect the synthetic checkout comparison immediately. Download **spandelta-0.1.0.zip** for the CLI, runtime libraries, documentation, and agent-client example. Verify both files against **SHA256SUMS**.

Extract the ZIP, set JAVA_HOME to a Java 21 JDK, and run:

```sh
bin/spandelta demo --out checkout-demo
```

On Windows use `bin\spandelta.bat`. Open `checkout-demo/report.html` to inspect the result. The synthetic dataset contains 40 traces per release, with observed increases in checkout and payment latency.

The comparison describes captured traffic. Sampling changes and different workloads affect the result. The MCP server provides measured evidence for an external agent and can be exercised without a language model.
