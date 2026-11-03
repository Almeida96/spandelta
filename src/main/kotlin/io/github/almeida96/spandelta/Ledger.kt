package io.github.almeida96.spandelta

import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.sql.PreparedStatement
import java.sql.ResultSet
import org.sqlite.SQLiteConfig

internal data class Receipt(val inserted: Int, val duplicates: Int)

internal data class Dataset(val name: String, val spans: Int, val traces: Int)

internal class Ledger(path: Path, private val readOnly: Boolean = false) : AutoCloseable {
    private val connection: Connection

    init {
        if (readOnly) require(Files.isRegularFile(path)) { "Database does not exist." }
        else Files.createDirectories(path.toAbsolutePath().parent)
        val config =
            SQLiteConfig().apply {
                setReadOnly(readOnly)
                setBusyTimeout(5000)
            }
        connection =
            DriverManager.getConnection(
                "jdbc:sqlite:" + path.toAbsolutePath(),
                config.toProperties(),
            )
        try {
            if (!readOnly)
                transaction(write = true) {
                    if (
                        scalar("PRAGMA application_id") == 0 &&
                            scalar("PRAGMA user_version") == 0 &&
                            scalar(
                                "SELECT count(*) FROM sqlite_master WHERE name NOT LIKE 'sqlite_%'"
                            ) == 0
                    ) {
                        execute(
                            """CREATE TABLE spans (
                        dataset TEXT NOT NULL, trace_id TEXT NOT NULL, span_id TEXT NOT NULL,
                        parent_id TEXT, service TEXT NOT NULL, operation TEXT NOT NULL, kind INTEGER NOT NULL,
                        start_ns INTEGER NOT NULL, end_ns INTEGER NOT NULL, status INTEGER NOT NULL,
                        PRIMARY KEY(dataset, trace_id, span_id)) WITHOUT ROWID"""
                        )
                        execute("PRAGMA application_id=1397769284")
                        execute("PRAGMA user_version=1")
                    }
                    verifySchema()
                }
            else verifySchema()
            if (!readOnly) execute("PRAGMA journal_mode=WAL")
        } catch (e: Exception) {
            connection.close()
            throw e
        }
    }

    private fun verifySchema() {
        require(
            scalar("PRAGMA application_id") == 1397769284 && scalar("PRAGMA user_version") == 1
        ) {
            "Expected a SpanDelta database with schema version 1."
        }
    }

    private fun execute(sql: String) {
        connection.createStatement().use { it.execute(sql) }
    }

    private fun scalar(sql: String): Int =
        connection.createStatement().use { statement ->
            statement.executeQuery(sql).use {
                it.next()
                it.getInt(1)
            }
        }

    private fun <T> transaction(write: Boolean = false, work: () -> T): T {
        execute(if (write) "BEGIN IMMEDIATE" else "BEGIN")
        try {
            val value = work()
            execute("COMMIT")
            return value
        } catch (e: Exception) {
            runCatching { execute("ROLLBACK") }.onFailure(e::addSuppressed)
            throw e
        }
    }

    @Synchronized
    fun import(name: String, spans: List<Span>): Receipt {
        datasetName(name)
        check(!readOnly)
        require(spans.size <= MAX_BATCH) { "Batch exceeds 10,000 spans." }
        return transaction(write = true) {
            val count =
                connection.prepareStatement("SELECT count(*) FROM spans WHERE dataset=?").use {
                    it.setString(1, name)
                    it.executeQuery().use { rows ->
                        rows.next()
                        rows.getInt(1)
                    }
                }
            var inserted = 0
            connection
                .prepareStatement(
                    "INSERT INTO spans VALUES (?,?,?,?,?,?,?,?,?,?) ON CONFLICT(dataset,trace_id,span_id) DO NOTHING"
                )
                .use { put ->
                    connection
                        .prepareStatement(
                            "SELECT * FROM spans WHERE dataset=? AND trace_id=? AND span_id=?"
                        )
                        .use { existing ->
                            for (span in spans) {
                                put.bind(name, span)
                                if (put.executeUpdate() == 1) {
                                    inserted++
                                    require(count + inserted <= MAX_DATASET) {
                                        "Dataset exceeds 50,000 spans. Start another dataset."
                                    }
                                } else {
                                    existing.setString(1, name)
                                    existing.setString(2, span.traceId)
                                    existing.setString(3, span.spanId)
                                    existing.executeQuery().use { rows ->
                                        check(rows.next())
                                        if (rows.span() != span) throw SpanConflict()
                                    }
                                }
                            }
                        }
                }
            Receipt(inserted, spans.size - inserted)
        }
    }

    @Synchronized
    fun datasets(): List<Dataset> =
        connection.createStatement().use { statement ->
            statement
                .executeQuery(
                    "SELECT dataset,count(*),count(DISTINCT trace_id) FROM spans GROUP BY dataset ORDER BY dataset"
                )
                .use { rows ->
                    buildList {
                        while (rows.next()) add(
                            Dataset(rows.getString(1), rows.getInt(2), rows.getInt(3))
                        )
                    }
                }
        }

    @Synchronized
    fun comparison(baseline: String, candidate: String, policy: Policy): Comparison {
        return transaction { compareStored(baseline, candidate, policy) }
    }

    private fun compareStored(baseline: String, candidate: String, policy: Policy): Comparison {
        require(baseline != candidate) { "Select two different datasets." }
        return compare(baseline, load(baseline), candidate, load(candidate), policy)
    }

    @Synchronized
    fun investigate(baseline: String, candidate: String, policy: Policy): Investigation =
        transaction {
            val diff = compareStored(baseline, candidate, policy)
            val examples =
                diff.operations
                    .asSequence()
                    .flatMap { change ->
                        listOfNotNull(
                                change.candidate?.errorExample?.takeIf {
                                    "ERROR_RATE" in change.signals
                                },
                                change.candidate?.p95Example,
                            )
                            .asSequence()
                    }
                    .map(Example::traceId)
                    .distinct()
                    .take(3)
            val traces = mutableListOf<TraceView>()
            val notes = mutableListOf<String>()
            for (id in examples) {
                try {
                    traces += inspect(candidate, id)
                } catch (e: IllegalArgumentException) {
                    notes += "Trace $id: " + e.message
                }
            }
            Investigation(diff, traces, notes)
        }

    @Synchronized
    fun inspect(name: String, traceId: String): TraceView {
        datasetName(name)
        checkedId(traceId, 32)
        val spans =
            connection
                .prepareStatement(
                    "SELECT * FROM spans WHERE dataset=? AND trace_id=? ORDER BY start_ns,span_id LIMIT 2001"
                )
                .use { statement ->
                    statement.setString(1, name)
                    statement.setString(2, traceId)
                    statement.executeQuery().use { rows ->
                        buildList { while (rows.next()) add(rows.span()) }
                    }
                }
        require(spans.isNotEmpty()) { "Trace not found in this dataset." }
        require(spans.size <= 2000) { "Trace inspection supports up to 2,000 spans." }
        return traceView(name, spans)
    }

    private fun load(name: String): List<Span> {
        datasetName(name)
        return connection
            .prepareStatement("SELECT * FROM spans WHERE dataset=?")
            .use { statement ->
                statement.setString(1, name)
                statement.executeQuery().use { rows ->
                    buildList { while (rows.next()) add(rows.span()) }
                }
            }
            .also { require(it.isNotEmpty()) { "Dataset '$name' has no spans." } }
    }

    private fun ResultSet.span() =
        Span(
            getString("trace_id"),
            getString("span_id"),
            getString("parent_id"),
            getString("service"),
            getString("operation"),
            getInt("kind"),
            getLong("start_ns"),
            getLong("end_ns"),
            getInt("status"),
        )

    private fun PreparedStatement.bind(dataset: String, span: Span) {
        setString(1, dataset)
        setString(2, span.traceId)
        setString(3, span.spanId)
        setString(4, span.parentId)
        setString(5, span.service)
        setString(6, span.operation)
        setInt(7, span.kind)
        setLong(8, span.start)
        setLong(9, span.end)
        setInt(10, span.status)
    }

    override fun close() = connection.close()
}
