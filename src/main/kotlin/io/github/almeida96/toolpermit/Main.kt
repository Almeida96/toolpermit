package io.github.almeida96.toolpermit

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import kotlin.system.exitProcess

const val VERSION = "0.1.0"

private val help = """
    ToolPermit $VERSION — approval broker for agent tools

    serve    --policy FILE --db FILE --token-env ENV [--port 8787] [--timeout-ms 10000] [--concurrency 8]
    check    --policy FILE --request FILE
    pending  --db FILE
    inspect  --db FILE --id ID
    approve  --db FILE --id ID --fingerprint SHA256
    reject   --db FILE --id ID --fingerprint SHA256
    audit    --db FILE --id ID
    demo     --out NEW_DIRECTORY

    The service binds to 127.0.0.1. Approvals are local operator commands, never HTTP endpoints.
    Output is JSON. inspect includes raw arguments; audit includes metadata only.
    Exit codes: 0 success, 1 invalid input/operation, 2 denied or approval required,
                3 expired/stale/non-approvable review. Use --version or --help.
""".trimIndent()

fun main(args: Array<String>) {
    System.setOut(java.io.PrintStream(java.io.FileOutputStream(java.io.FileDescriptor.out), true, Charsets.UTF_8))
    System.setErr(java.io.PrintStream(java.io.FileOutputStream(java.io.FileDescriptor.err), true, Charsets.UTF_8))
    val status = try { execute(args.toList(), System.getenv()) }
    catch (e: InvalidInput) { System.err.println(e.message); 1 }
    catch (e: Conflict) { System.err.println(e.message); 1 }
    catch (_: Exception) { System.err.println("Command failed. Check the configuration and local files."); 1 }
    exitProcess(status)
}

internal fun execute(args: List<String>, env: Map<String, String>): Int {
    if (args.isEmpty() || args == listOf("--help")) { println(help); return 0 }
    if (args == listOf("--version")) { println(VERSION); return 0 }
    val allowed = mapOf(
        "serve" to setOf("policy", "db", "token-env", "port", "timeout-ms", "concurrency"),
        "check" to setOf("policy", "request"), "pending" to setOf("db"),
        "inspect" to setOf("db", "id"), "approve" to setOf("db", "id", "fingerprint"),
        "reject" to setOf("db", "id", "fingerprint"), "audit" to setOf("db", "id"), "demo" to setOf("out"))
    val command = args[0]
    Json.require(command in allowed && args.size % 2 == 1, "Invalid command or option pairs")
    val values = linkedMapOf<String, String>()
    args.drop(1).chunked(2).forEach { (key, value) ->
        Json.require(key.startsWith("--") && key.drop(2) in allowed.getValue(command), "Unknown option")
        Json.require(value.isNotBlank() && values.put(key.drop(2), value) == null, "Blank or duplicate option")
    }
    fun required(name: String): String = values[name] ?: throw InvalidInput("Required option is missing")
    fun path(name: String): Path = Path.of(required(name))
    if (command == "demo") { Demo.run(path("out")); return 0 }
    if (command == "check") {
        val policy = Policy.load(path("policy")); val call = Invocation.parse(Json.read(path("request"), Server.MAX_REQUEST))
        val decision = policy.decide(call)
        println(Json.obj("action" to decision.action.name, "reason" to decision.reason, "fingerprint" to call.fingerprint, "policyFingerprint" to policy.fingerprint))
        return if (decision.action == Action.ALLOW) 0 else 2
    }
    if (command == "serve") {
        val policy = Policy.load(path("policy"))
        val tokenName = required("token-env").also(Policy::environmentName)
        val token = Dispatcher.token(env[tokenName])
        val port = values["port"]?.toInt() ?: 8787
        val timeout = values["timeout-ms"]?.toLong() ?: 10000
        val concurrency = values["concurrency"]?.toInt() ?: 8
        Dispatcher(timeout, env, policy).use { dispatcher -> Store(path("db")).use { store ->
            Server(Broker(policy, store, dispatcher), token, port, concurrency).use { server ->
                println(Json.obj("listening" to "http://127.0.0.1:${server.port}", "version" to VERSION, "policyFingerprint" to policy.fingerprint))
                val stop = CountDownLatch(1)
                val hook = Thread { stop.countDown() }
                Runtime.getRuntime().addShutdownHook(hook)
                try { stop.await() } finally { try { Runtime.getRuntime().removeShutdownHook(hook) } catch (_: IllegalStateException) { } }
            }
        } }
        return 0
    }
    val db = path("db")
    Json.require(Files.isRegularFile(db), "Database does not exist; start the service first")
    Store(db).use { store ->
        if (command == "pending") {
            val rows = store.pending().mapNotNull { store.get(it.call.id, System.currentTimeMillis()) }.filter { it.state in setOf(State.PENDING, State.APPROVED) }
            println(Json.obj("items" to rows.map { it.publicJson() }, "limit" to 100)); return 0
        }
        val id = required("id").also { Json.id(Json.mapper.nodeFactory.textNode(it)) }
        when (command) {
            "inspect" -> println((store.get(id, System.currentTimeMillis()) ?: throw InvalidInput("Invocation does not exist")).reviewJson())
            "audit" -> println(store.audit(id))
            else -> {
                val fingerprint = required("fingerprint").also { Json.require(it.matches(Regex("[a-f0-9]{64}")), "Expected a SHA-256 fingerprint") }
                val result = store.review(id, fingerprint, command == "approve", System.currentTimeMillis())
                println(result.publicJson())
                return if (result.state == (if (command == "approve") State.APPROVED else State.REJECTED)) 0 else 3
            }
        }
    }
    return 0
}
