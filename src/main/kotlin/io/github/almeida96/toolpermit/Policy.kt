package io.github.almeida96.toolpermit

import com.fasterxml.jackson.databind.JsonNode
import java.net.URI
import java.nio.file.Path

internal enum class Action { ALLOW, APPROVAL, DENY }
internal data class Tool(val name: String, val action: Action, val endpoint: URI?, val tokenEnv: String?, val constraints: List<JsonNode>)
internal data class Decision(val action: Action, val reason: String, val tool: Tool?)

internal data class Policy(val fingerprint: String, val approvalTtlSeconds: Long, val tools: Map<String, Tool>) {
    fun decide(call: Invocation): Decision {
        val tool = tools[call.tool] ?: return Decision(Action.DENY, "UNKNOWN_TOOL", null)
        if (tool.action == Action.DENY) return Decision(Action.DENY, "POLICY_DENY", tool)
        for (constraint in tool.constraints) {
            val value = call.arguments.at(constraint["path"].textValue())
            val pass = !value.isMissingNode && when (constraint["type"].textValue()) {
                "one_of" -> constraint["values"].any { Json.canonical(it) == Json.canonical(value) }
                "max_number" -> value.isNumber && value.decimalValue() <= constraint["value"].decimalValue()
                "min_number" -> value.isNumber && value.decimalValue() >= constraint["value"].decimalValue()
                else -> false
            }
            if (!pass) return Decision(Action.DENY, "ARGUMENT_CONSTRAINT", tool)
        }
        return Decision(tool.action, if (tool.action == Action.APPROVAL) "REQUIRES_APPROVAL" else "POLICY_ALLOW", tool)
    }

    companion object {
        fun load(path: Path): Policy = parse(Json.read(path, 1024 * 1024))
        fun parse(root: JsonNode): Policy {
            Json.fields(root, setOf("schemaVersion", "approvalTtlSeconds", "tools"))
            Json.integer(root["schemaVersion"], 1, 1)
            val ttl = Json.integer(root["approvalTtlSeconds"], 1, 86400)
            val rows = root["tools"]
            Json.require(rows.isArray && rows.size() in 1..100, "Policy must contain 1–100 tools")
            val tools = linkedMapOf<String, Tool>()
            for (row in rows) {
                Json.fields(row, setOf("name", "action"), setOf("endpoint", "tokenEnv", "constraints"))
                val name = toolName(row["name"])
                val action = Action.entries.find { it.name == Json.text(row["action"]) } ?: throw InvalidInput("Invalid policy action")
                val endpoint = if (row.has("endpoint")) endpoint(Json.text(row["endpoint"], 2048)) else null
                Json.require((action == Action.DENY && endpoint == null && !row.has("tokenEnv")) || (action != Action.DENY && endpoint != null), "Executable tools require an endpoint; denied tools cannot have one")
                val env = if (row.has("tokenEnv")) Json.text(row["tokenEnv"]).also(::environmentName) else null
                val checks = row["constraints"] ?: Json.mapper.createArrayNode()
                Json.require(checks.isArray && checks.size() <= 20, "Expected at most 20 argument constraints")
                checks.forEach(::validateConstraint)
                Json.require(tools.put(name, Tool(name, action, endpoint, env, checks.toList())) == null, "Duplicate tool name")
            }
            return Policy(Json.digest(root), ttl, tools)
        }
        fun environmentName(value: String) { Json.require(value.matches(Regex("[A-Za-z_][A-Za-z0-9_]{0,127}")), "Invalid environment variable name") }
        fun toolName(node: JsonNode?): String = Json.text(node).also {
            Json.require(it.matches(Regex("[A-Za-z0-9][A-Za-z0-9._:/-]{0,127}")), "Invalid tool name")
        }
        fun endpoint(value: String): URI {
            val uri = try { URI.create(value) } catch (_: Exception) { throw InvalidInput("Invalid endpoint URI") }
            val local = uri.host?.lowercase() in setOf("localhost", "127.0.0.1", "[::1]", "::1")
            Json.require(uri.host != null && uri.userInfo == null && uri.fragment == null && uri.query == null &&
                (uri.scheme.equals("https", true) || (uri.scheme.equals("http", true) && local)), "Use HTTPS or loopback HTTP without URI credentials, query, or fragment")
            return uri
        }
        private fun validateConstraint(node: JsonNode) {
            val type = Json.text(node["type"])
            Json.fields(node, setOf("type", "path", if (type == "one_of") "values" else "value"))
            val path = node["path"]
            Json.require(path.isTextual && path.textValue().length <= 512 && path.textValue().matches(Regex("(?:/(?:[^~]|~[01])*)*")), "Invalid JSON Pointer")
            when (type) {
                "one_of" -> Json.require(node["values"].isArray && node["values"].size() in 1..100, "Expected 1–100 allowed values")
                "min_number", "max_number" -> Json.require(node["value"].isNumber, "Numeric constraint requires a number")
                else -> throw InvalidInput("Unknown constraint type")
            }
        }
    }
}

internal data class Invocation(val id: String, val tool: String, val arguments: JsonNode, val fingerprint: String) {
    fun json(): JsonNode = Json.obj("schemaVersion" to 1, "id" to id, "tool" to tool, "arguments" to arguments)
    companion object {
        fun parse(root: JsonNode): Invocation {
            Json.fields(root, setOf("schemaVersion", "id", "tool", "arguments"))
            Json.integer(root["schemaVersion"], 1, 1)
            val id = Json.id(root["id"])
            val tool = Policy.toolName(root["tool"])
            Json.require(root["arguments"].isObject, "Tool arguments must be an object")
            return Invocation(id, tool, root["arguments"], Json.digest(root))
        }
    }
}
