package io.github.almeida96.toolpermit

import com.fasterxml.jackson.databind.JsonNode
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

internal object Demo {
    fun run(directory: Path) {
        Json.require(!Files.exists(directory, LinkOption.NOFOLLOW_LINKS), "Demo output directory must not exist")
        Files.createDirectories(directory)
        val notes = Files.createDirectory(directory.resolve("notes"))
        val reads = AtomicInteger(); val writes = AtomicInteger()
        val upstream = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val workers = Executors.newVirtualThreadPerTaskExecutor()
        upstream.executor = workers
        upstream.createContext("/") { exchange -> exchange.use {
            val call = Invocation.parse(Json.parse(exchange.requestBody.readNBytes(Server.MAX_REQUEST)))
            if (call.tool == "notes.create") {
                writes.incrementAndGet()
                Files.writeString(notes.resolve("${call.id}.txt"), call.arguments["text"].textValue(), java.nio.file.StandardOpenOption.CREATE_NEW)
            } else reads.incrementAndGet()
            val bytes = Json.obj("ok" to true, "tool" to call.tool).toString().toByteArray(Charsets.UTF_8)
            exchange.responseHeaders.set("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, bytes.size.toLong()); exchange.responseBody.write(bytes)
        } }
        upstream.start()
        try {
            val policyJson = Json.obj("schemaVersion" to 1, "approvalTtlSeconds" to 600, "tools" to listOf(
                Json.obj("name" to "notes.search", "action" to "ALLOW", "endpoint" to "http://127.0.0.1:${upstream.address.port}/search"),
                Json.obj("name" to "notes.create", "action" to "APPROVAL", "endpoint" to "http://127.0.0.1:${upstream.address.port}/create", "constraints" to listOf(Json.obj("type" to "one_of", "path" to "/workspace", "values" to listOf("sandbox")))),
                Json.obj("name" to "notes.delete", "action" to "DENY")))
            Files.writeString(directory.resolve("policy.json"), policyJson.toPrettyString())
            val policy = Policy.parse(policyJson)
            Store(directory.resolve("toolpermit.db")).use { store -> Dispatcher(5000, emptyMap(), policy).use { dispatcher ->
                val token = "local-demo-token-not-a-secret"
                Server(Broker(policy, store, dispatcher), token, 0).use { server -> HttpClient.newHttpClient().use { client ->
                    fun post(id: String, tool: String, arguments: JsonNode): JsonNode {
                        val request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:${server.port}/v1/invocations"))
                            .timeout(Duration.ofSeconds(10)).header("Authorization", "Bearer $token").header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(Json.obj("schemaVersion" to 1, "id" to id, "tool" to tool, "arguments" to arguments).toString())).build()
                        return Json.parse(client.send(request, HttpResponse.BodyHandlers.ofByteArray()).body())
                    }
                    val allowed = post("read-1", "notes.search", Json.obj("query" to "release"))
                    val args = Json.obj("workspace" to "sandbox", "text" to "Release checklist")
                    val pending = post("write-1", "notes.create", args)
                    Json.require(writes.get() == 0 && pending["state"].textValue() == "PENDING", "Demo write was not held for review")
                    Store(directory.resolve("toolpermit.db")).use { operator -> operator.review("write-1", pending["fingerprint"].textValue(), true, System.currentTimeMillis()) }
                    val approved = post("write-1", "notes.create", args)
                    val replayed = post("write-1", "notes.create", args)
                    val blocked = post("delete-1", "notes.delete", Json.obj())
                    val constrained = post("write-2", "notes.create", Json.obj("workspace" to "production"))
                    Json.require(allowed["state"].textValue() == "SUCCEEDED" && approved["state"].textValue() == "SUCCEEDED" &&
                        replayed["state"].textValue() == "SUCCEEDED" && blocked["state"].textValue() == "DENIED" && constrained["state"].textValue() == "DENIED" &&
                        reads.get() == 1 && writes.get() == 1, "Demo produced unexpected results")
                    val summary = Json.obj("allowedReads" to reads.get(), "writesBeforeApproval" to 0, "writesAfterReplay" to writes.get(), "blockedRequests" to 2,
                        "observations" to listOf(allowed, pending, approved, replayed, blocked, constrained))
                    Files.writeString(directory.resolve("summary.json"), summary.toPrettyString())
                    Files.writeString(directory.resolve("audit.json"), store.audit("write-1").toPrettyString())
                    println(Json.obj("demo" to "local HTTP tools", "allowedReads" to 1, "writesBeforeApproval" to 0, "writesAfterReplay" to 1, "blockedRequests" to 2))
                } }
            } }
        } finally { upstream.stop(0); workers.shutdownNow() }
    }
}
