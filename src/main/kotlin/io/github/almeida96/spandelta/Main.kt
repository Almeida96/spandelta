package io.github.almeida96.spandelta

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess

private val help =
    """
    SpanDelta 0.1.0 | Compare distributed traces across releases

    demo     --out NEW_DIRECTORY
    receive  --db FILE --dataset NAME --token-env ENV [--port 4318]
    import   --db FILE --dataset NAME --file OTLP_FILE [--format json|protobuf]
    datasets --db FILE
    compare  --db FILE --baseline NAME --candidate NAME [--html NEW_FILE] [--gate]
             [--min-samples 20] [--min-delta-ms 10] [--min-delta-pct 20] [--error-delta-pp 2]
    inspect  --db FILE --dataset NAME --trace TRACE_ID
    mcp      --db FILE

    Commands print JSON except help, version, and MCP stdio. The receiver prints its URL on stderr.
    With --gate, exit 2 indicates regression and 3 indicates inconclusive coverage. Errors exit 1.
    """
        .trimIndent()

private class Options(tokens: List<String>, allowed: Set<String>) {
    private val values = mutableMapOf<String, String>()

    init {
        var i = 0
        while (i < tokens.size) {
            val key = tokens[i++]
            require(key in allowed && key !in values) { "Unknown or duplicate option." }
            val value =
                if (key == "--gate") "true"
                else {
                    require(
                        i < tokens.size && !tokens[i].startsWith("--") && tokens[i].isNotBlank()
                    ) {
                        "Option requires a value."
                    }
                    tokens[i++]
                }
            values[key] = value
        }
    }

    fun requireValue(key: String) = values[key] ?: throw IllegalArgumentException("Missing $key.")

    fun path(key: String) = Path.of(requireValue(key))

    fun optional(key: String) = values[key]

    fun int(key: String, default: Int) =
        optional(key)?.let {
            it.toIntOrNull() ?: throw IllegalArgumentException("Expected integer $key.")
        } ?: default

    fun number(key: String, default: Double) =
        optional(key)?.let {
            it.toDoubleOrNull() ?: throw IllegalArgumentException("Expected number $key.")
        } ?: default
}

private val options =
    mapOf(
        "demo" to setOf("--out"),
        "receive" to setOf("--db", "--dataset", "--token-env", "--port"),
        "import" to setOf("--db", "--dataset", "--file", "--format"),
        "datasets" to setOf("--db"),
        "inspect" to setOf("--db", "--dataset", "--trace"),
        "compare" to
            setOf(
                "--db",
                "--baseline",
                "--candidate",
                "--html",
                "--gate",
                "--min-samples",
                "--min-delta-ms",
                "--min-delta-pct",
                "--error-delta-pp",
            ),
        "mcp" to setOf("--db"),
    )

fun main(args: Array<String>) {
    val code =
        try {
            execute(args)
        } catch (e: IllegalArgumentException) {
            System.err.println(e.message ?: "Invalid input.")
            1
        } catch (e: java.nio.file.FileAlreadyExistsException) {
            System.err.println("Output path already exists.")
            1
        } catch (e: Exception) {
            System.err.println(
                "Operation failed. Check file access, database availability and port configuration."
            )
            1
        }
    exitProcess(code)
}

internal fun execute(args: Array<String>): Int {
    if (args.isEmpty() || args.contentEquals(arrayOf("--help"))) {
        println(help)
        return 0
    }
    if (args.contentEquals(arrayOf("--version"))) {
        println(VERSION)
        return 0
    }
    val command = args.first()
    val flags =
        Options(
            args.drop(1),
            options[command] ?: throw IllegalArgumentException("Unknown command. Use --help."),
        )
    fun output(value: Any) {
        System.out.write(json.writeValueAsBytes(value))
        System.out.write(10)
        System.out.flush()
    }
    when (command) {
        "demo" -> output(Demo.run(flags.path("--out")))
        "import" -> {
            val name = datasetName(flags.requireValue("--dataset"))
            val file = flags.path("--file")
            val format =
                flags.optional("--format")
                    ?: when (file.fileName.toString().substringAfterLast('.').lowercase()) {
                        "json" -> "json"
                        "pb",
                        "protobuf" -> "protobuf"
                        else ->
                            throw IllegalArgumentException(
                                "Use --format json or --format protobuf."
                            )
                    }
            require(format in setOf("json", "protobuf")) { "Unsupported format." }
            val spans =
                Files.newInputStream(file).use {
                    Otlp.decode(boundedRead(it), format == "protobuf")
                }
            Ledger(flags.path("--db")).use { output(it.import(name, spans)) }
        }
        "receive" -> {
            val env = flags.requireValue("--token-env")
            require(env.matches(Regex("[a-zA-Z_][a-zA-Z0-9_]*"))) {
                "Invalid token environment name."
            }
            val token =
                System.getenv(env)
                    ?: throw IllegalArgumentException(
                        "Receiver token environment variable is unset."
                    )
            val stop = CountDownLatch(1)
            val closed = CountDownLatch(1)
            val hook = Thread {
                stop.countDown()
                closed.await(10, TimeUnit.SECONDS)
            }
            try {
                Ledger(flags.path("--db")).use { db ->
                    Receiver(db, flags.requireValue("--dataset"), token, flags.int("--port", 4318))
                        .use { receiver ->
                            Runtime.getRuntime().addShutdownHook(hook)
                            System.err.println(
                                "Receiving OTLP traces at http://127.0.0.1:${receiver.port}/v1/traces"
                            )
                            stop.await()
                        }
                }
            } finally {
                closed.countDown()
                try {
                    Runtime.getRuntime().removeShutdownHook(hook)
                } catch (_: IllegalStateException) {
                    /* JVM shutdown in progress. */
                }
            }
        }
        else ->
            Ledger(flags.path("--db"), readOnly = true).use { db ->
                when (command) {
                    "mcp" -> Mcp(db).serve(System.`in`, System.out)
                    "datasets" -> output(db.datasets())
                    "inspect" ->
                        output(
                            db.inspect(
                                flags.requireValue("--dataset"),
                                flags.requireValue("--trace"),
                            )
                        )
                    "compare" -> {
                        val baseline = flags.requireValue("--baseline")
                        val candidate = flags.requireValue("--candidate")
                        val policy =
                            Policy(
                                flags.int("--min-samples", 20),
                                flags.number("--min-delta-ms", 10.0),
                                flags.number("--min-delta-pct", 20.0),
                                flags.number("--error-delta-pp", 2.0),
                            )
                        val report = flags.optional("--html")
                        val diff =
                            if (report == null) db.comparison(baseline, candidate, policy)
                            else {
                                val review = db.investigate(baseline, candidate, policy)
                                writeReport(Path.of(report), review)
                                review.comparison
                            }
                        output(diff)
                        if (flags.optional("--gate") != null)
                            return when (diff.verdict) {
                                "REGRESSION" -> 2
                                "INCONCLUSIVE" -> 3
                                else -> 0
                            }
                    }
                }
            }
    }
    return 0
}
