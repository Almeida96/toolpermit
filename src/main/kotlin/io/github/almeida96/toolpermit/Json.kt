package io.github.almeida96.toolpermit

import com.fasterxml.jackson.core.JsonFactory
import com.fasterxml.jackson.core.StreamReadConstraints
import com.fasterxml.jackson.core.StreamReadFeature
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.json.JsonMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

class InvalidInput(message: String) : IllegalArgumentException(message)
class Conflict(message: String) : IllegalStateException(message)

internal object Json {
    val mapper: JsonMapper = JsonMapper.builder(JsonFactory.builder()
        .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(32).maxStringLength(65_536).maxNumberLength(128).build())
        .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS, DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).build()

    fun require(valid: Boolean, message: String) { if (!valid) throw InvalidInput(message) }
    fun parse(bytes: ByteArray): JsonNode {
        try { return mapper.readTree(bytes)?.also { require(!it.isMissingNode, "JSON document is empty"); validateUnicode(it) } ?: throw InvalidInput("JSON document is empty") }
        catch (e: InvalidInput) { throw e }
        catch (_: Exception) { throw InvalidInput("Invalid JSON document") }
    }
    fun read(path: Path, limit: Int): JsonNode = Files.newInputStream(path).use {
        val bytes = it.readNBytes(limit + 1)
        require(bytes.size <= limit, "JSON document exceeds the size limit")
        parse(bytes)
    }
    fun obj(vararg pairs: Pair<String, Any?>): ObjectNode = mapper.createObjectNode().also { node ->
        pairs.forEach { (key, value) -> node.set<JsonNode>(key, mapper.valueToTree(value)) }
    }
    fun fields(node: JsonNode, required: Set<String>, optional: Set<String> = emptySet()) {
        require(node.isObject && required.all(node::has) && node.fieldNames().asSequence().all { it in required || it in optional }, "Missing or unknown JSON field")
    }
    fun text(node: JsonNode?, max: Int = 128): String {
        require(node != null && node.isTextual && node.textValue().length in 1..max, "Invalid string field")
        return node!!.textValue()
    }
    fun id(node: JsonNode?): String = text(node, 80).also { require(it.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,79}")), "Invalid identifier") }
    fun integer(node: JsonNode?, min: Long, max: Long): Long {
        require(node != null && node.isIntegralNumber && node.canConvertToLong(), "Expected an integer")
        return node!!.longValue().also { require(it in min..max, "Integer outside allowed range") }
    }
    fun canonical(node: JsonNode): String = when {
        node.isObject -> node.fieldNames().asSequence().sorted().joinToString(",", "{", "}") { mapper.writeValueAsString(it) + ":" + canonical(node[it]) }
        node.isArray -> node.joinToString(",", "[", "]", transform = ::canonical)
        node.isNumber -> node.decimalValue().stripTrailingZeros().toString()
        else -> node.toString()
    }
    fun digest(node: JsonNode): String {
        // A lone surrogate would become '?' during UTF-8 encoding and could share an approval
        // fingerprint with different arguments. Validate programmatically built nodes as well.
        validateUnicode(node)
        return MessageDigest.getInstance("SHA-256").digest(canonical(node).toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }
    private fun validateUnicode(node: JsonNode) {
        fun text(value: String) {
            var index = 0
            while (index < value.length) {
                val char = value[index++]
                if (char.isHighSurrogate()) require(index < value.length && value[index++].isLowSurrogate(), "Unpaired Unicode surrogate")
                else require(!char.isLowSurrogate(), "Unpaired Unicode surrogate")
            }
        }
        if (node.isTextual) text(node.textValue())
        if (node.isObject) node.fieldNames().forEachRemaining(::text)
        if (node.isContainerNode) node.forEach(::validateUnicode)
    }
}
