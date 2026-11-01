package io.github.almeida96.toolpermit

import com.fasterxml.jackson.databind.JsonNode
import java.io.ByteArrayOutputStream
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.ByteBuffer
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.Flow
import java.util.concurrent.TimeUnit

internal data class Outcome(val state: State, val reason: String, val result: JsonNode? = null)
internal fun interface Executor { fun execute(call: Invocation, tool: Tool): Outcome }

internal class Dispatcher(private val timeoutMs: Long, private val environment: Map<String, String>, policy: Policy) : Executor, AutoCloseable {
    private val client: HttpClient
    init {
        Json.require(timeoutMs in 100..120000, "Timeout must be 100–120000 ms")
        policy.tools.values.mapNotNull { it.tokenEnv }.forEach { token(environment[it]) }
        client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).connectTimeout(Duration.ofMillis(timeoutMs)).build()
    }
    override fun execute(call: Invocation, tool: Tool): Outcome {
        val builder = HttpRequest.newBuilder(tool.endpoint!!).timeout(Duration.ofMillis(timeoutMs))
            .header("Content-Type", "application/json").header("Accept", "application/json")
            .header("Idempotency-Key", call.id)
            .POST(HttpRequest.BodyPublishers.ofString(call.json().toString(), Charsets.UTF_8))
        tool.tokenEnv?.let { builder.header("Authorization", "Bearer " + token(environment[it])) }
        val future = client.sendAsync(builder.build()) { info -> BoundedBody(info.headers().firstValueAsLong("Content-Length").orElse(-1)) }
        try {
            val response = future.get(timeoutMs, TimeUnit.MILLISECONDS)
            // Any non-success response may still follow a side effect. Never replay it automatically.
            if (response.statusCode() !in 200..299) return Outcome(State.UNKNOWN, "UPSTREAM_HTTP_STATUS")
            if (!response.headers().firstValue("Content-Type").orElse("").substringBefore(';').trim().equals("application/json", true)) return Outcome(State.UNKNOWN, "UPSTREAM_INVALID_RESPONSE")
            val body = try { Json.parse(response.body()) } catch (_: InvalidInput) { return Outcome(State.UNKNOWN, "UPSTREAM_INVALID_RESPONSE") }
            return Outcome(State.SUCCEEDED, "UPSTREAM_COMPLETED", body)
        } catch (_: InterruptedException) {
            future.cancel(true); Thread.currentThread().interrupt()
            return Outcome(State.UNKNOWN, "DISPATCH_INTERRUPTED")
        } catch (_: Exception) {
            future.cancel(true)
            return Outcome(State.UNKNOWN, "UPSTREAM_UNCERTAIN")
        }
    }
    override fun close() { client.shutdownNow() }
    companion object {
        fun token(value: String?): String {
            Json.require(value != null && value.length in 16..8192 && value.all { it.code in 33..126 }, "Token environment variable must contain 16–8192 printable non-space characters")
            return value!!
        }
    }

    private class BoundedBody(private val announced: Long) : HttpResponse.BodySubscriber<ByteArray> {
        private val result = CompletableFuture<ByteArray>()
        private val bytes = ByteArrayOutputStream()
        private lateinit var subscription: Flow.Subscription
        override fun getBody(): CompletionStage<ByteArray> = result
        override fun onSubscribe(value: Flow.Subscription) {
            subscription = value
            if (announced > LIMIT) fail() else value.request(1)
        }
        override fun onNext(items: List<ByteBuffer>) {
            if (result.isDone) { subscription.cancel(); return }
            for (item in items) {
                if (bytes.size().toLong() + item.remaining() > LIMIT) { fail(); return }
                val part = ByteArray(item.remaining()); item.get(part); bytes.writeBytes(part)
            }
            subscription.request(1)
        }
        private fun fail() { subscription.cancel(); result.completeExceptionally(IllegalStateException("Response too large")) }
        override fun onError(error: Throwable) { result.completeExceptionally(error) }
        override fun onComplete() { result.complete(bytes.toByteArray()) }
        companion object { const val LIMIT = 1024 * 1024 }
    }
}
