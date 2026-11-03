# Collection and storage

SpanDelta accepts trace exports over HTTP at `/v1/traces` on IPv4 loopback. Use `Content-Type: application/x-protobuf` for binary Protobuf or `application/json` for OTLP JSON. Gzip transport uses `Content-Encoding: gzip`. The receiver requires an access token from a named environment variable, with 16–4,096 printable non-space characters.

## Send a batch

With a receiver running, the JSON files from the demo can be replayed with curl.

```sh
curl --fail-with-body http://127.0.0.1:4318/v1/traces \
  -H "Authorization: Bearer $SPANDELTA_TOKEN" \
  -H 'Content-Type: application/json' \
  --data-binary @checkout-demo/baseline.json
```

PowerShell can generate a token and start the same receiver.

```powershell
$bytes = New-Object byte[] 32
$rng = [Security.Cryptography.RandomNumberGenerator]::Create()
$rng.GetBytes($bytes)
$rng.Dispose()
$env:SPANDELTA_TOKEN = [Convert]::ToBase64String($bytes)
.\bin\spandelta.bat receive --db traces.db --dataset canary --token-env SPANDELTA_TOKEN
```

Configure the exporter with the full traces endpoint, the same bearer header and a service name. An example using the official Java exporter can be found in [WireTest](https://github.com/Almeida96/spandelta/blob/main/src/test/kotlin/io/github/almeida96/spandelta/WireTest.kt). It propagates a real trace context between two services and sends gzip-compressed Protobuf. See the [OTLP specification](https://opentelemetry.io/docs/specs/otlp/) for transport details.

To try a public upstream fixture, download [the OpenTelemetry trace export for v1.11.1](https://github.com/open-telemetry/opentelemetry-proto/blob/v1.11.1/examples/trace.json), save the raw JSON as `trace.json`, then run `spandelta import --db public.db --dataset upstream --file trace.json`. The fixture imports one span.

## Retained fields and retries

The primary key is dataset, trace ID and span ID. SpanDelta retains the parent ID, service name, operation, kind, start/end nanoseconds, and status code. Missing service names use `unknown_service`. Arbitrary resource attributes, span attributes, events, links, baggage and status messages are omitted. Service and operation names can still contain private data. Choose what you send and restrict access to the database and exported reports.

Repeated spans with identical retained fields count as duplicates. A difference in a retained field under the same key rolls back the entire batch. All rows commit together. Empty successful OTLP requests receive an empty success response and create no dataset. Unsupported fields are ignored. JSON IDs are hexadecimal and enum values are numeric. Timestamps must fit a nonnegative signed 64-bit integer and end at or after start.

## Limits and operations

| Limit | Value |
| --- | --- |
| On-wire and decompressed body | 4 MiB each |
| Spans per export batch | 10,000 |
| Spans per dataset | 50,000 |
| Inspected spans per trace | 2,000 |
| Receiver concurrency / body deadline | 8 requests / 8 seconds |
| Service and operation label length | 256 UTF-16 code units |
| SQLite write wait | 5 seconds |

Use a local disk for the database. SQLite WAL allows readers to inspect committed data while batches arrive. Each comparison loads both cohorts into memory. For repeatable reports, use distinct names per build and finish collection first. Datasets accumulate until the operator archives the database. To back up, stop all processes using it and copy the directory including any remaining WAL sidecars.

The receiver has one writer token. The MCP server and CLI inspection commands open SQLite in read-only mode. Filesystem access determines who can read the data. This release supports local OTLP/HTTP traces. It does not implement gRPC, metrics, logs, streaming tail sampling, or a multi-user telemetry backend.

## HTTP failures

Malformed input or a conflicting span returns 400 and rejects the whole batch. Authentication failures return 401. Browser-origin requests return 403, size violations return 413, and unsupported formats return 415. A saturated or temporarily unavailable receiver returns 503. Error bodies use OTLP's JSON or Protobuf status shape. Inspect configuration before retrying a permanently invalid batch.
