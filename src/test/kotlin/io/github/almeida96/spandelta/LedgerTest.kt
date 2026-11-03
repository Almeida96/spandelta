package io.github.almeida96.spandelta

import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import kotlin.test.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class LedgerTest {
    @TempDir lateinit var dir: Path

    @Test
    fun conflictRollsBackEarlierRowsAndOriginalEvidenceStaysIntact() {
        Ledger(dir.resolve("test.db")).use { db ->
            val first = span(1)
            assertEquals(Receipt(1, 0), db.import("release", listOf(first)))
            assertEquals(Receipt(0, 1), db.import("release", listOf(first)))
            assertFailsWith<SpanConflict> {
                db.import("release", listOf(span(2), first.copy(operation = "changed")))
            }
            assertEquals(1, db.datasets().single().spans)
            assertEquals(
                first.operation,
                db.inspect("release", first.traceId).spans.single().operation,
            )
        }
    }

    @Test
    fun simultaneousRetriesInsertOnlyOneCopy() {
        val path = dir.resolve("concurrent.db")
        Ledger(path).use { first ->
            Ledger(path).use { second ->
                val gate = CyclicBarrier(2)
                Executors.newFixedThreadPool(2).use { pool ->
                    val results =
                        listOf(first, second)
                            .map { db ->
                                pool.submit<Receipt> {
                                    gate.await()
                                    db.import("candidate", listOf(span(1)))
                                }
                            }
                            .map { it.get() }
                    assertEquals(1, results.sumOf { it.inserted })
                    assertEquals(1, results.sumOf { it.duplicates })
                }
            }
        }
    }

    @Test
    fun readOnlySessionRequiresExistingDatabaseAndNeverMutatesData() {
        val path = dir.resolve("new.db")
        assertFailsWith<IllegalArgumentException> { Ledger(path, readOnly = true) }
        assertFalse(Files.exists(path))
        Ledger(path).use { it.import("demo", listOf(span(1))) }
        Ledger(path, readOnly = true).use { db ->
            assertEquals(1, db.datasets().size)
            assertFailsWith<IllegalStateException> { db.import("demo", listOf(span(2))) }
        }
    }

    @Test
    fun unrelatedDatabaseIsLeftIntact() {
        val path = dir.resolve("other.db")
        DriverManager.getConnection("jdbc:sqlite:$path").use { connection ->
            connection.createStatement().use { it.execute("CREATE TABLE keep(value TEXT)") }
        }
        assertFailsWith<IllegalArgumentException> { Ledger(path) }
        DriverManager.getConnection("jdbc:sqlite:$path").use { connection ->
            connection.createStatement().use { s ->
                s.executeQuery("SELECT name FROM sqlite_master WHERE type='table'").use { r ->
                    assertTrue(r.next())
                    assertEquals("keep", r.getString(1))
                    assertFalse(r.next())
                }
            }
        }
    }

    @Test
    fun missingCohortsAndComparisonsToSelfFailExplicitly() {
        Ledger(dir.resolve("test.db")).use { db ->
            db.import("a", listOf(span(1)))
            assertFailsWith<IllegalArgumentException> { db.comparison("a", "none", Policy()) }
            assertFailsWith<IllegalArgumentException> { db.comparison("a", "a", Policy()) }
        }
    }

    @Test
    fun htmlEscapesTelemetryAndPreservesExistingFiles() {
        val unsafe = "query <script>alert(1)</script> & account"
        Ledger(dir.resolve("html.db")).use { db ->
            listOf("a", "b").forEach { db.import(it, listOf(span(1, op = unsafe))) }
            val review = db.investigate("a", "b", Policy(minSamples = 1))
            val file = dir.resolve("report.html")
            writeReport(file, review)
            val body = Files.readString(file)
            assertTrue("&lt;script&gt;" in body)
            assertFalse("<script>" in body)
            assertTrue("Candidate trace evidence" in body)
            assertFailsWith<java.nio.file.FileAlreadyExistsException> { writeReport(file, review) }
            assertEquals(body, Files.readString(file))
        }
    }
}
