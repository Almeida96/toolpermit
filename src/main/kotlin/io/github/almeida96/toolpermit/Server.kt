package io.github.almeida96.toolpermit

import com.fasterxml.jackson.databind.JsonNode
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

internal class Server(private val broker: Broker, bearer: String, port: Int, concurrency: Int = 8, private val readTimeoutMs: Long = 5000) : AutoCloseable {
    private val token = Dispatcher.token(bearer).toByteArray(Charsets.UTF_8)
    private val slots = Semaphore(concurrency)
    private val workers = Executors.newVirtualThreadPerTaskExecutor()
    private val readers = Executors.newVirtualThreadPerTaskExecutor()
    private val server: HttpServer
    init {
        Json.require(port in 0..65535 && concurrency in 1..32, "Invalid port or concurrency")
        Json.require(readTimeoutMs in 100..30000, "Invalid request read timeout")
        server = HttpServer.create(InetSocketAddress("127.0.0.1", port), 32)
        server.executor = workers
        server.createContext("/") { exchange -> handle(exchange) }
        server.start()
    }
    val port: Int get() = server.address.port

    private fun handle(exchange: HttpExchange) {
        exchange.use {
            if (!slots.tryAcquire()) { send(exchange, 503, Json.obj("error" to "BUSY")); return }
            try {
                if (exchange.requestHeaders.containsKey("Origin")) { send(exchange, 403, Json.obj("error" to "BROWSER_ORIGIN_REJECTED")); return }
                val supplied = exchange.requestHeaders.getFirst("Authorization")?.takeIf { it.startsWith("Bearer ") }?.substring(7)?.toByteArray(Charsets.UTF_8)
                if (supplied == null || !MessageDigest.isEqual(token, supplied)) { send(exchange, 401, Json.obj("error" to "UNAUTHORIZED")); return }
                val host = exchange.requestHeaders.getFirst("Host")
                if (host !in setOf("127.0.0.1:$port", "localhost:$port")) { send(exchange, 400, Json.obj("error" to "INVALID_HOST")); return }
                if (exchange.requestURI.rawQuery != null) { send(exchange, 400, Json.obj("error" to "QUERY_NOT_SUPPORTED")); return }
                val path = exchange.requestURI.rawPath
                when {
                    path == "/health" -> {
                        if (exchange.requestMethod != "GET") method(exchange, "GET")
                        else send(exchange, 200, Json.obj("status" to "ok", "version" to VERSION))
                    }
                    path == "/v1/invocations" -> {
                        if (exchange.requestMethod != "POST") { method(exchange, "POST"); return }
                        if (!exchange.requestHeaders.getFirst("Content-Type").orEmpty().substringBefore(';').trim().equals("application/json", true)) {
                            send(exchange, 415, Json.obj("error" to "EXPECTED_JSON")); return
                        }
                        val declared = exchange.requestHeaders.getFirst("Content-Length")?.toLongOrNull()
                        if (declared != null && declared > MAX_REQUEST) { send(exchange, 413, Json.obj("error" to "REQUEST_TOO_LARGE")); return }
                        val read = readers.submit<ByteArray> { exchange.requestBody.readNBytes(MAX_REQUEST + 1) }
                        val bytes = try { read.get(readTimeoutMs, TimeUnit.MILLISECONDS) }
                        catch (_: TimeoutException) { send(exchange, 408, Json.obj("error" to "BODY_TIMEOUT")); return }
                        finally { if (!read.isDone) read.cancel(true) }
                        if (bytes.size > MAX_REQUEST) { send(exchange, 413, Json.obj("error" to "REQUEST_TOO_LARGE")); return }
                        val result = broker.submit(Invocation.parse(Json.parse(bytes)))
                        send(exchange, if (result.state in setOf(State.PENDING, State.APPROVED, State.DISPATCHING)) 202 else 200, result.publicJson())
                    }
                    path.matches(Regex("/v1/invocations/[A-Za-z0-9][A-Za-z0-9._-]{0,79}")) -> {
                        if (exchange.requestMethod != "GET") { method(exchange, "GET"); return }
                        val result = broker.status(path.substringAfterLast('/'))
                        if (result == null) send(exchange, 404, Json.obj("error" to "NOT_FOUND"))
                        else send(exchange, 200, result.publicJson())
                    }
                    else -> send(exchange, 404, Json.obj("error" to "NOT_FOUND"))
                }
            } catch (_: Conflict) { send(exchange, 409, Json.obj("error" to "ID_CONFLICT")) }
            catch (_: InvalidInput) { send(exchange, 400, Json.obj("error" to "INVALID_INPUT")) }
            catch (_: Exception) { try { send(exchange, 500, Json.obj("error" to "INTERNAL_ERROR")) } catch (_: Exception) { /* Disconnected clients cannot receive a response. */ } }
            finally { slots.release() }
        }
    }
    private fun method(exchange: HttpExchange, allowed: String) { exchange.responseHeaders.set("Allow", allowed); send(exchange, 405, Json.obj("error" to "METHOD_NOT_ALLOWED")) }
    private fun send(exchange: HttpExchange, status: Int, body: JsonNode) {
        val bytes = Json.mapper.writeValueAsBytes(body)
        exchange.responseHeaders.set("Content-Type", "application/json; charset=utf-8")
        exchange.responseHeaders.set("Cache-Control", "no-store")
        exchange.responseHeaders.set("X-Content-Type-Options", "nosniff")
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.write(bytes)
        exchange.responseBody.flush()
    }
    override fun close() { server.stop(0); workers.shutdownNow(); readers.shutdownNow() }
    companion object { const val MAX_REQUEST = 65536 }
}
