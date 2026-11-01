package io.github.almeida96.toolpermit

import com.fasterxml.jackson.databind.JsonNode
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet

internal enum class State { READY, PENDING, APPROVED, REJECTED, DENIED, EXPIRED, STALE, DISPATCHING, SUCCEEDED, UNKNOWN }
internal data class Record(
    val call: Invocation, val policyFingerprint: String, val state: State, val reason: String,
    val createdAt: Long, val expiresAt: Long, val result: JsonNode?
) {
    fun publicJson(): JsonNode = Json.obj("schemaVersion" to 1, "id" to call.id, "tool" to call.tool,
        "fingerprint" to call.fingerprint, "policyFingerprint" to policyFingerprint, "state" to state.name,
        "reason" to reason, "createdAt" to createdAt, "expiresAt" to expiresAt.takeIf { it > 0 }, "result" to result)
    fun reviewJson(): JsonNode = (publicJson() as com.fasterxml.jackson.databind.node.ObjectNode).also { it.set<JsonNode>("arguments", call.arguments) }
}

internal class Store(path: Path) : AutoCloseable {
    private val connection: Connection
    init {
        Files.createDirectories(path.toAbsolutePath().parent)
        connection = DriverManager.getConnection("jdbc:sqlite:" + path.toAbsolutePath())
        try {
            connection.createStatement().use {
                it.execute("PRAGMA busy_timeout=5000")
                it.execute("PRAGMA foreign_keys=ON")
                val version = it.executeQuery("PRAGMA user_version").use { row -> row.next(); row.getInt(1) }
                Json.require(version in 0..1, "Unsupported database version")
                val application = it.executeQuery("PRAGMA application_id").use { row -> row.next(); row.getInt(1) }
                val tables = it.executeQuery("SELECT count(*) FROM sqlite_master WHERE type='table'").use { row -> row.next(); row.getInt(1) }
                Json.require(application == APPLICATION_ID || (application == 0 && version == 0 && tables == 0), "Database belongs to another application")
                it.execute("PRAGMA journal_mode=WAL")
            }
            transaction {
                connection.createStatement().use {
                    it.execute("""CREATE TABLE IF NOT EXISTS invocations (
                        id TEXT PRIMARY KEY, fingerprint TEXT NOT NULL, policy_fingerprint TEXT NOT NULL,
                        tool TEXT NOT NULL, request_json TEXT NOT NULL, state TEXT NOT NULL, reason TEXT NOT NULL,
                        created_at INTEGER NOT NULL, expires_at INTEGER NOT NULL, result_json TEXT
                    )""")
                    it.execute("""CREATE TABLE IF NOT EXISTS events (
                        seq INTEGER PRIMARY KEY AUTOINCREMENT, invocation_id TEXT NOT NULL REFERENCES invocations(id),
                        state TEXT NOT NULL, reason TEXT NOT NULL, actor TEXT NOT NULL, at INTEGER NOT NULL
                    )""")
                    it.execute("CREATE INDEX IF NOT EXISTS events_invocation ON events(invocation_id, seq)")
                    it.execute("PRAGMA user_version=1")
                    it.execute("PRAGMA application_id=$APPLICATION_ID")
                }
            }
        } catch (e: Exception) { connection.close(); throw e }
    }

    private fun <T> transaction(block: () -> T): T {
        connection.createStatement().use { it.execute("BEGIN IMMEDIATE") }
        try {
            val result = block()
            connection.createStatement().use { it.execute("COMMIT") }
            return result
        } catch (e: Throwable) {
            try { connection.createStatement().use { it.execute("ROLLBACK") } } catch (rollback: Exception) { e.addSuppressed(rollback) }
            throw e
        }
    }

    @Synchronized fun register(call: Invocation, policy: Policy, now: Long): Record = transaction {
        find(call.id)?.let {
            if (it.call.fingerprint != call.fingerprint) throw Conflict("Request ID already belongs to different arguments or tool")
            return@transaction refresh(it, policy.fingerprint, now)
        }
        val decision = policy.decide(call)
        val state = when (decision.action) { Action.ALLOW -> State.READY; Action.APPROVAL -> State.PENDING; Action.DENY -> State.DENIED }
        val expires = if (state == State.PENDING) Math.addExact(now, policy.approvalTtlSeconds * 1000) else 0
        connection.prepareStatement("INSERT INTO invocations VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, NULL)").use {
            it.setString(1, call.id); it.setString(2, call.fingerprint); it.setString(3, policy.fingerprint)
            it.setString(4, call.tool); it.setString(5, call.json().toString()); it.setString(6, state.name)
            it.setString(7, decision.reason); it.setLong(8, now); it.setLong(9, expires); it.executeUpdate()
        }
        event(call.id, state, decision.reason, "agent", now)
        find(call.id)!!
    }

    @Synchronized fun get(id: String, now: Long, policy: String? = null): Record? = transaction { find(id)?.let { refresh(it, policy, now) } }

    @Synchronized fun review(id: String, fingerprint: String, approve: Boolean, now: Long): Record = transaction {
        val original = find(id) ?: throw InvalidInput("Invocation does not exist")
        if (original.call.fingerprint != fingerprint) throw Conflict("Fingerprint does not match the reviewed invocation")
        val current = refresh(original, null, now)
        if (current.state !in setOf(State.PENDING, State.APPROVED)) return@transaction current
        if (approve && current.state == State.APPROVED) return@transaction current
        transition(id, if (approve) State.APPROVED else State.REJECTED, if (approve) "OPERATOR_APPROVED" else "OPERATOR_REJECTED", "operator", now)
        find(id)!!
    }

    @Synchronized fun claim(id: String, policy: String, now: Long): Boolean = transaction {
        val current = refresh(find(id) ?: throw InvalidInput("Invocation does not exist"), policy, now)
        if (current.state !in setOf(State.READY, State.APPROVED)) return@transaction false
        transition(id, State.DISPATCHING, "DISPATCH_STARTED", "broker", now)
        true
    }

    @Synchronized fun finish(id: String, state: State, reason: String, result: JsonNode?, now: Long): Record = transaction {
        Json.require(state in setOf(State.SUCCEEDED, State.UNKNOWN), "Invalid completion state")
        if (find(id)?.state != State.DISPATCHING) throw Conflict("Invocation is not dispatching")
        transition(id, state, reason, "broker", now, result)
        find(id)!!
    }

    @Synchronized fun pending(limit: Int = 100): List<Record> {
        Json.require(limit in 1..200, "Limit must be 1–200")
        return connection.prepareStatement("SELECT * FROM invocations WHERE state IN ('PENDING', 'APPROVED') ORDER BY created_at, id LIMIT ?").use {
            it.setInt(1, limit)
            it.executeQuery().use { rows -> buildList { while (rows.next()) add(record(rows)) } }
        }
    }

    @Synchronized fun audit(id: String): JsonNode {
        val row = find(id) ?: throw InvalidInput("Invocation does not exist")
        val events = Json.mapper.createArrayNode()
        connection.prepareStatement("SELECT * FROM events WHERE invocation_id=? ORDER BY seq").use {
            it.setString(1, id)
            it.executeQuery().use { rows -> while (rows.next()) events.add(Json.obj("sequence" to rows.getLong("seq"), "state" to rows.getString("state"),
                "reason" to rows.getString("reason"), "actor" to rows.getString("actor"), "at" to rows.getLong("at"))) }
        }
        return Json.obj("schemaVersion" to 1, "id" to id, "tool" to row.call.tool, "fingerprint" to row.call.fingerprint,
            "policyFingerprint" to row.policyFingerprint, "events" to events)
    }

    private fun refresh(row: Record, policy: String?, now: Long): Record {
        if (row.state !in setOf(State.PENDING, State.APPROVED, State.READY)) return row
        if (policy != null && row.policyFingerprint != policy) transition(row.call.id, State.STALE, "POLICY_CHANGED", "broker", now)
        else if (row.expiresAt > 0 && now >= row.expiresAt) transition(row.call.id, State.EXPIRED, "APPROVAL_EXPIRED", "broker", now)
        else return row
        return find(row.call.id)!!
    }

    private fun find(id: String): Record? = connection.prepareStatement("SELECT * FROM invocations WHERE id=?").use {
        it.setString(1, id); it.executeQuery().use { rows -> if (rows.next()) record(rows) else null }
    }
    private fun record(rows: ResultSet): Record = Record(Invocation.parse(Json.parse(rows.getString("request_json").toByteArray(Charsets.UTF_8))),
        rows.getString("policy_fingerprint"), State.valueOf(rows.getString("state")), rows.getString("reason"),
        rows.getLong("created_at"), rows.getLong("expires_at"), rows.getString("result_json")?.let { Json.parse(it.toByteArray(Charsets.UTF_8)) })

    private fun transition(id: String, state: State, reason: String, actor: String, now: Long, result: JsonNode? = null) {
        connection.prepareStatement("UPDATE invocations SET state=?, reason=?, result_json=? WHERE id=?").use {
            it.setString(1, state.name); it.setString(2, reason); it.setString(3, result?.toString()); it.setString(4, id); it.executeUpdate()
        }
        event(id, state, reason, actor, now)
    }
    private fun event(id: String, state: State, reason: String, actor: String, now: Long) {
        connection.prepareStatement("INSERT INTO events (invocation_id,state,reason,actor,at) VALUES (?,?,?,?,?)").use {
            it.setString(1, id); it.setString(2, state.name); it.setString(3, reason); it.setString(4, actor); it.setLong(5, now); it.executeUpdate()
        }
    }
    @Synchronized override fun close() { connection.close() }
    companion object { private const val APPLICATION_ID = 0x54504d54 }
}
