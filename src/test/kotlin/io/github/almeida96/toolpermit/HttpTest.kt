package io.github.almeida96.toolpermit

import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.net.InetSocketAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

class HttpTest {
    @TempDir lateinit var dir: Path
    private val token = "test-only-bearer-token"

    private fun request(port: Int, path: String = "/v1/invocations", body: String? = call().json().toString(), bearer: String? = token, origin: String? = null): HttpResponse<String> {
        val builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port$path")).timeout(Duration.ofSeconds(10)).header("Content-Type", "application/json")
        bearer?.let { builder.header("Authorization", "Bearer $it") }
        origin?.let { builder.header("Origin", it) }
        if (body == null) builder.GET() else builder.POST(HttpRequest.BodyPublishers.ofString(body))
        return HttpClient.newHttpClient().use { it.send(builder.build(), HttpResponse.BodyHandlers.ofString()) }
    }
    @Test fun `authentication method and browser origin checks run before dispatch`() {
        Store(dir.resolve("auth.db")).use { store -> Server(Broker(policy("ALLOW"), store, { _, _ -> fail("Unauthorized request executed") }), token, 0).use { server ->
            assertEquals(401, request(server.port, bearer = null).statusCode())
            assertEquals(401, request(server.port, bearer = "wrong").statusCode())
            assertEquals(403, request(server.port, origin = "https://example.com").statusCode())
            assertEquals(405, request(server.port, body = null).statusCode())
            assertEquals(404, request(server.port, path = "/v1/approve").statusCode())
            assertEquals(400, request(server.port, path = "/v1/invocations?approve=true").statusCode())
        } }
    }
    @Test fun `HTTP submission approval resubmission and conflict exercise the public contract`() {
        val calls = AtomicInteger(); val path = dir.resolve("http.db")
        Store(path).use { store -> Server(Broker(policy(), store, { _, _ -> calls.incrementAndGet(); Outcome(State.SUCCEEDED, "DONE", node("{\"ok\":true}")) }), token, 0).use { server ->
            val pending = request(server.port)
            assertEquals(202, pending.statusCode()); assertEquals("PENDING", node(pending.body())["state"].textValue())
            Store(path).use { it.review("request-1", call().fingerprint, true, System.currentTimeMillis()) }
            assertEquals("APPROVED", node(request(server.port, "/v1/invocations/request-1", null).body())["state"].textValue())
            assertEquals(200, request(server.port).statusCode())
            assertEquals(200, request(server.port).statusCode()); assertEquals(1, calls.get())
            assertEquals(409, request(server.port, body = call(args = "{}").json().toString()).statusCode())
            assertEquals(404, request(server.port, "/v1/invocations/missing", null).statusCode())
        } }
    }
    @Test fun `oversized and malformed requests do not create invocations`() {
        Store(dir.resolve("invalid.db")).use { store -> Server(Broker(policy(), store, { _, _ -> fail("Invalid request executed") }), token, 0).use { server ->
            assertEquals(413, request(server.port, body = "x".repeat(Server.MAX_REQUEST + 1)).statusCode())
            assertEquals(400, request(server.port, body = "{\"secret\":\"PRIVATE_REQUEST\"}").statusCode())
            assertFalse(request(server.port, body = "PRIVATE_REQUEST").body().contains("PRIVATE_REQUEST"))
            assertTrue(store.pending().isEmpty())
        } }
    }
    @Test fun `concurrent duplicate HTTP requests dispatch at most once`() {
        val started = CountDownLatch(1); val release = CountDownLatch(1); val count = AtomicInteger()
        Store(dir.resolve("race.db")).use { store -> Server(Broker(policy("ALLOW"), store, { _, _ ->
            count.incrementAndGet(); started.countDown(); check(release.await(5, TimeUnit.SECONDS)); Outcome(State.SUCCEEDED, "DONE", node("{}"))
        }), token, 0).use { server ->
            Executors.newVirtualThreadPerTaskExecutor().use { pool ->
                val first = pool.submit<HttpResponse<String>> { request(server.port) }
                try {
                    assertTrue(started.await(5, TimeUnit.SECONDS))
                    val duplicate = request(server.port)
                    assertEquals(202, duplicate.statusCode()); assertEquals("DISPATCHING", node(duplicate.body())["state"].textValue())
                } finally { release.countDown() }
                assertEquals(200, first.get(5, TimeUnit.SECONDS).statusCode()); assertEquals(1, count.get())
            }
        } }
    }

    private fun upstream(handler: (com.sun.net.httpserver.HttpExchange) -> Unit, test: (String) -> Unit) {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val executor = Executors.newVirtualThreadPerTaskExecutor(); server.executor = executor
        server.createContext("/") { exchange -> exchange.use(handler) }; server.start()
        try { test("http://127.0.0.1:${server.address.port}/run") } finally { server.stop(0); executor.shutdownNow() }
    }
    @Test fun `dispatcher forwards bound request id and configured authentication`() {
        upstream({ exchange ->
            assertEquals("request-1", exchange.requestHeaders.getFirst("Idempotency-Key"))
            assertEquals("Bearer $token", exchange.requestHeaders.getFirst("Authorization"))
            assertEquals(call().fingerprint, Invocation.parse(Json.parse(exchange.requestBody.readAllBytes())).fingerprint)
            val bytes = "{\"created\":true}".toByteArray(); exchange.responseHeaders.set("Content-Type", "application/json"); exchange.sendResponseHeaders(200, bytes.size.toLong()); exchange.responseBody.write(bytes)
        }) { url ->
            val p = Policy.parse(Json.obj("schemaVersion" to 1, "approvalTtlSeconds" to 60, "tools" to listOf(Json.obj("name" to "notes.create", "action" to "ALLOW", "endpoint" to url, "tokenEnv" to "UPSTREAM_TOKEN"))))
            Dispatcher(5000, mapOf("UPSTREAM_TOKEN" to token), p).use { assertEquals(State.SUCCEEDED, it.execute(call(), p.tools.getValue("notes.create")).state) }
        }
    }
    @Test fun `redirects and error bodies are not forwarded or retried`() {
        val count = AtomicInteger()
        upstream({ exchange ->
            count.incrementAndGet(); exchange.responseHeaders.set("Location", "/other"); val bytes = "PRIVATE_FAILURE".toByteArray()
            exchange.sendResponseHeaders(302, bytes.size.toLong()); exchange.responseBody.write(bytes)
        }) { url ->
            val p = policy("ALLOW", url)
            Dispatcher(1000, emptyMap(), p).use { dispatcher ->
                val outcome = dispatcher.execute(call(), p.tools.getValue("notes.create"))
                assertEquals(State.UNKNOWN, outcome.state); assertNull(outcome.result); assertFalse(outcome.toString().contains("PRIVATE_FAILURE"))
            }
            assertEquals(1, count.get())
        }
    }
    @Test fun `response deadline includes a stalled body after headers`() {
        upstream({ exchange -> exchange.responseHeaders.set("Content-Type", "application/json"); exchange.sendResponseHeaders(200, 0); exchange.responseBody.write('{'.code); exchange.responseBody.flush(); Thread.sleep(2000) }) { url ->
            val p = policy("ALLOW", url); val start = System.nanoTime()
            Dispatcher(200, emptyMap(), p).use { assertEquals(State.UNKNOWN, it.execute(call(), p.tools.getValue("notes.create")).state) }
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < 1500)
        }
    }
    @Test fun `oversized chunked responses are bounded and become uncertain`() {
        upstream({ exchange -> exchange.responseHeaders.set("Content-Type", "application/json"); exchange.sendResponseHeaders(200, 0); try { repeat(140) { exchange.responseBody.write(ByteArray(8192) { 32 }) } } catch (_: Exception) { } }) { url ->
            val p = policy("ALLOW", url)
            Dispatcher(5000, emptyMap(), p).use { val outcome = it.execute(call(), p.tools.getValue("notes.create")); assertEquals(State.UNKNOWN, outcome.state); assertNull(outcome.result) }
        }
    }
    @Test fun `invalid upstream JSON never creates a successful outcome`() {
        upstream({ exchange -> exchange.responseHeaders.set("Content-Type", "application/json"); val bytes = "{\"x\":1,\"x\":2}".toByteArray(); exchange.sendResponseHeaders(200, bytes.size.toLong()); exchange.responseBody.write(bytes) }) { url ->
            val p = policy("ALLOW", url)
            Dispatcher(5000, emptyMap(), p).use { assertEquals("UPSTREAM_INVALID_RESPONSE", it.execute(call(), p.tools.getValue("notes.create")).reason) }
        }
    }
    @Test fun `invalid bearer credentials are rejected before serving`() {
        for (value in listOf(null, "short", "secret\r\nInjected: header", " ".repeat(20))) assertFailsWith<InvalidInput> { Dispatcher.token(value) }
    }
    @Test fun `stalled incoming body times out without occupying a worker indefinitely`() {
        Store(dir.resolve("slow-body.db")).use { store -> Server(Broker(policy(), store, { _, _ -> fail("Incomplete request executed") }), token, 0, readTimeoutMs = 200).use { server ->
            java.net.Socket("127.0.0.1", server.port).use { socket ->
                socket.soTimeout = 3000
                socket.getOutputStream().write(("POST /v1/invocations HTTP/1.1\r\nHost: 127.0.0.1:${server.port}\r\nAuthorization: Bearer $token\r\nContent-Type: application/json\r\nContent-Length: 100\r\n\r\n{").toByteArray())
                socket.getOutputStream().flush()
                assertTrue(socket.getInputStream().bufferedReader().readLine().contains("408"))
            }
            assertEquals(200, request(server.port, "/health", null).statusCode())
        } }
    }
    @Test fun `concurrency limit rejects excess work while an invocation is in flight`() {
        val started = CountDownLatch(1); val release = CountDownLatch(1)
        Store(dir.resolve("busy.db")).use { store -> Server(Broker(policy("ALLOW"), store, { _, _ ->
            started.countDown(); check(release.await(5, TimeUnit.SECONDS)); Outcome(State.SUCCEEDED, "DONE", node("{}"))
        }), token, 0, concurrency = 1).use { server -> Executors.newVirtualThreadPerTaskExecutor().use { pool ->
            val first = pool.submit<HttpResponse<String>> { request(server.port) }
            try { assertTrue(started.await(5, TimeUnit.SECONDS)); assertEquals(503, request(server.port, body = call("second").json().toString()).statusCode()) }
            finally { release.countDown() }
            assertEquals(200, first.get(5, TimeUnit.SECONDS).statusCode())
            assertNull(store.get("second", System.currentTimeMillis()))
        } } }
    }
}
