package io.github.almeida96.spandelta

import com.google.protobuf.CodedOutputStream
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.zip.GZIPInputStream

internal class Receiver(
    private val ledger: Ledger,
    private val dataset: String,
    token: String,
    port: Int,
) : AutoCloseable {
    private val secret = ("Bearer " + token).toByteArray()
    private val workers = Executors.newVirtualThreadPerTaskExecutor()
    private val readers = Executors.newVirtualThreadPerTaskExecutor()
    private val slots = Semaphore(8)
    private val server: HttpServer
    val port
        get() = server.address.port

    init {
        datasetName(dataset)
        require(token.length in 16..4096 && token.all { it.code in 33..126 }) {
            "Receiver token must have 16–4096 printable non-space characters."
        }
        require(port in 0..65535) { "Invalid port." }
        server = HttpServer.create(InetSocketAddress("127.0.0.1", port), 32)
        server.executor = workers
        server.createContext("/", ::handle)
        server.start()
    }

    private fun handle(exchange: HttpExchange) {
        exchange.use { x ->
            val protobuf =
                x.requestHeaders
                    .getFirst("Content-Type")
                    ?.substringBefore(';')
                    ?.trim()
                    ?.lowercase() == "application/x-protobuf"
            if (!slots.tryAcquire()) {
                failure(x, 503, 14, "Receiver is busy.", protobuf)
                return
            }
            try {
                if (x.requestURI.rawPath != "/v1/traces" || x.requestURI.rawQuery != null) {
                    failure(x, 404, 5, "Unknown route.", protobuf)
                    return
                }
                if (x.requestMethod != "POST") {
                    x.responseHeaders.set("Allow", "POST")
                    failure(x, 405, 12, "Use POST.", protobuf)
                    return
                }
                if (x.requestHeaders.getFirst("Origin") != null) {
                    failure(x, 403, 7, "Browser origins are not accepted.", protobuf)
                    return
                }
                if (
                    !MessageDigest.isEqual(
                        secret,
                        x.requestHeaders.getFirst("Authorization").orEmpty().toByteArray(),
                    )
                ) {
                    failure(x, 401, 16, "Invalid access token.", protobuf)
                    return
                }
                if (
                    !protobuf &&
                        !x.requestHeaders
                            .getFirst("Content-Type")
                            .orEmpty()
                            .substringBefore(';')
                            .trim()
                            .equals("application/json", true)
                ) {
                    failure(x, 415, 3, "Use OTLP JSON or Protobuf.", false)
                    return
                }
                val encoding =
                    x.requestHeaders.getFirst("Content-Encoding")?.lowercase() ?: "identity"
                if (encoding !in setOf("identity", "gzip")) {
                    failure(x, 415, 3, "Unsupported content encoding.", protobuf)
                    return
                }
                if ((x.requestHeaders.getFirst("Content-Length")?.toLongOrNull() ?: 0) > MAX_BYTES)
                    throw PayloadLimit()
                val read = readers.submit<ByteArray> { boundedRead(x.requestBody) }
                val body =
                    try {
                        read.get(8, TimeUnit.SECONDS)
                    } catch (e: java.util.concurrent.ExecutionException) {
                        throw (e.cause ?: e)
                    } catch (e: TimeoutException) {
                        try {
                            failure(x, 408, 4, "Body deadline exceeded.", protobuf)
                        } finally {
                            read.cancel(true)
                        }
                        return
                    }
                val bytes =
                    if (encoding == "gzip") {
                        try {
                            GZIPInputStream(ByteArrayInputStream(body)).use(::boundedRead)
                        } catch (e: java.io.IOException) {
                            throw IllegalArgumentException("Invalid gzip body.")
                        }
                    } else body
                ledger.import(dataset, Otlp.decode(bytes, protobuf))
                reply(x, 200, if (protobuf) byteArrayOf() else "{}".toByteArray(), protobuf)
            } catch (e: PayloadLimit) {
                failure(x, 413, 8, "Payload exceeds 4 MiB.", protobuf)
            } catch (e: IllegalArgumentException) {
                failure(x, 400, 3, e.message ?: "Invalid batch.", protobuf)
            } catch (e: java.sql.SQLException) {
                failure(x, 503, 14, "Storage temporarily unavailable.", protobuf)
            } catch (e: Exception) {
                failure(x, 500, 13, "Receiver error.", protobuf)
            } finally {
                slots.release()
            }
        }
    }

    private fun failure(x: HttpExchange, http: Int, rpc: Int, message: String, protobuf: Boolean) {
        val body =
            if (protobuf) {
                val bytes = ByteArrayOutputStream()
                val output = CodedOutputStream.newInstance(bytes)
                output.writeInt32(1, rpc)
                output.writeString(2, message)
                output.flush()
                bytes.toByteArray()
            } else json.writeValueAsBytes(mapOf("code" to rpc, "message" to message))
        reply(x, http, body, protobuf)
    }

    private fun reply(x: HttpExchange, code: Int, bytes: ByteArray, protobuf: Boolean) {
        x.responseHeaders.set(
            "Content-Type",
            if (protobuf) "application/x-protobuf" else "application/json",
        )
        x.responseHeaders.set("Cache-Control", "no-store")
        x.sendResponseHeaders(code, if (bytes.isEmpty()) -1 else bytes.size.toLong())
        if (bytes.isNotEmpty()) {
            x.responseBody.write(bytes)
            x.responseBody.flush()
        }
    }

    override fun close() {
        server.stop(0)
        workers.shutdownNow()
        readers.shutdownNow()
    }
}
