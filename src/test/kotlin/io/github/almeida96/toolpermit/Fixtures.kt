package io.github.almeida96.toolpermit

import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

internal fun node(value: String) = Json.parse(value.toByteArray())
internal fun call(id: String = "request-1", tool: String = "notes.create", args: String = "{\"workspace\":\"sandbox\"}") =
    Invocation.parse(Json.obj("schemaVersion" to 1, "id" to id, "tool" to tool, "arguments" to node(args)))
internal fun policy(action: String = "APPROVAL", url: String = "http://127.0.0.1:12345/run", ttl: Int = 600, constraints: String = "[]") =
    Policy.parse(node("""{"schemaVersion":1,"approvalTtlSeconds":$ttl,"tools":[{"name":"notes.create","action":"$action"${if (action != "DENY") ",\"endpoint\":\"$url\"" else ""},"constraints":$constraints}]}"""))
internal class MutableClock(var time: Long = 1000000) : Clock() {
    override fun getZone(): ZoneId = ZoneOffset.UTC
    override fun withZone(zone: ZoneId): Clock = this
    override fun instant(): Instant = Instant.ofEpochMilli(time)
}
