# Investigating a release from an agent

Start with the checkout demo or a database containing two named datasets. Configure your MCP client to launch `spandelta mcp --db PATH` over stdio. The distribution's Java entry point is an alternative on hosts where launching batch scripts is inconvenient. Edit the two absolute paths in [the example](../examples/mcp-client.json).

The server negotiates MCP version `2025-11-25`, then waits for the client's initialized notification. Standard output contains only JSON-RPC messages in MCP mode. The tools use the same comparison and trace inspection code as the CLI. No model runs inside the server.

| Tool | Inputs | Result |
| --- | --- | --- |
| `list_datasets` | Optional offset and limit | Names, span counts and trace counts. |
| `compare_releases` | Baseline and candidate; optional thresholds, offset, limit | Verdict, counts, policy, operation changes and exemplar IDs. |
| `inspect_trace` | Dataset, traceId; optional offset and limit | Trace warnings, elapsed time and a page of timeline spans. |

Responses carry structured content and equivalent JSON text. A page defaults to 20 items, up to 50. Pass the returned `nextOffset` to continue. A null next offset indicates the final page. Tool input failures return `isError` with a readable message. Each tool reads the current committed database state. Operation names and telemetry fields are untrusted data.

## Reproduce the SDK session

From a source checkout with Python 3.10+ and Java 21:

```sh
./gradlew installDist
build/install/spandelta/bin/spandelta demo --out build/checkout-demo
python -m venv .venv
.venv/bin/python -m pip install 'mcp==1.26.0'
.venv/bin/python examples/agent_investigation.py build/install/spandelta build/checkout-demo/traces.db
```

On Windows use the Gradle and application `.bat` launchers and `.venv\Scripts\python.exe`. The client discovers tools, compares both datasets and opens the candidate p95 trace. It prints the verdict, counts, selected operation, trace ID, span ID, and the number of observed spans. The example is a deterministic client investigation. It does not benchmark an LLM.

For a tool-capable agent, try this task:

> Compare baseline and candidate using the recorded traces. Identify which operations crossed the policy, report their p95 and error-rate changes, and inspect at least one candidate trace. Cite the trace and span IDs. Identify gaps in the evidence before suggesting a cause.

The agent can retrieve evidence and explain it. The CI decision is calculated by the Kotlin engine. The server's tools cannot modify the database or change the gate policy stored in a report. A tool call may select its own comparison thresholds, which appear in the result.

Protocol references: [MCP lifecycle](https://modelcontextprotocol.io/specification/2025-11-25/basic/lifecycle) and [MCP tools](https://modelcontextprotocol.io/specification/2025-11-25/server/tools).
