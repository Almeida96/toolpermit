package io.github.almeida96.toolpermit

import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import kotlin.test.*

class PolicyTest {
    @Test fun `unknown and explicitly denied tools fail closed`() {
        assertEquals("UNKNOWN_TOOL", policy().decide(call(tool = "shell.exec")).reason)
        assertEquals(Action.DENY, policy("DENY").decide(call()).action)
    }
    @Test fun `all argument constraints must match with no string coercion`() {
        val rule = policy(constraints = """[{"type":"one_of","path":"/workspace","values":["sandbox"]},{"type":"min_number","path":"/amount","value":0},{"type":"max_number","path":"/amount","value":50}]""")
        assertEquals(Action.APPROVAL, rule.decide(call(args = """{"workspace":"sandbox","amount":50.0}""")).action)
        for (args in listOf("""{"workspace":"sandbox","amount":51}""", """{"workspace":"sandbox","amount":-1}""", """{"workspace":"sandbox","amount":"50"}""", """{"workspace":"production","amount":1}""", "{}")) {
            assertEquals("ARGUMENT_CONSTRAINT", rule.decide(call(args = args)).reason)
        }
    }
    @Test fun `one of compares complete JSON values and escaped pointers`() {
        val rule = policy(constraints = """[{"type":"one_of","path":"/a~1b/~0key","values":[{"x":[1,2]}]}]""")
        assertEquals(Action.APPROVAL, rule.decide(call(args = """{"a/b":{"~key":{"x":[1.0,2]}}}""")).action)
        assertEquals(Action.DENY, rule.decide(call(args = """{"a/b":{"~key":{"x":[1,2],"extra":true}}}""")).action)
    }
    @Test fun `fingerprints ignore key order but bind arguments and policy`() {
        assertEquals(call(args = """{"a":1,"b":2}""").fingerprint, call(args = """{"b":2.0,"a":1.0}""").fingerprint)
        assertNotEquals(call().fingerprint, call(args = "{}").fingerprint)
        assertNotEquals(policy().fingerprint, policy("ALLOW").fingerprint)
        assertTrue(Json.canonical(node("1e1000000")).length < 20)
    }
    @ParameterizedTest @ValueSource(strings = ["http://example.com/tools", "https://user:password@example.com/tools", "https://example.com/tools?token=secret", "https://example.com/tools#secret", "file:///tmp/tool", "http://127.0.0.1.evil.example/run"])
    fun `unsafe endpoint configurations are rejected`(url: String) { assertFailsWith<InvalidInput> { Policy.endpoint(url) } }
    @ParameterizedTest @ValueSource(strings = ["http://127.0.0.1:8080/run", "http://localhost:8080/run", "http://[::1]:8080/run", "https://example.com/run"])
    fun `configured HTTPS and loopback endpoints are accepted`(url: String) { assertEquals(url, Policy.endpoint(url).toString()) }
    @ParameterizedTest @ValueSource(strings = [
        "{\"schemaVersion\":1,\"approvalTtlSeconds\":0,\"tools\":[]}",
        "{\"schemaVersion\":1,\"approvalTtlSeconds\":100,\"tools\":[{\"name\":\"x\",\"action\":\"ALLOW\"}]}",
        "{\"schemaVersion\":1,\"approvalTtlSeconds\":100,\"tools\":[{\"name\":\"x\",\"action\":\"DENY\",\"endpoint\":\"https://example.com\"}]}",
        "{\"schemaVersion\":1,\"approvalTtlSeconds\":100,\"tools\":[{\"name\":\"x\",\"action\":\"DENY\"},{\"name\":\"x\",\"action\":\"DENY\"}]}",
        "{\"schemaVersion\":1,\"approvalTtlSeconds\":100,\"tools\":[{\"name\":\"x\",\"action\":\"DENY\",\"constraints\":[{\"type\":\"one_of\",\"path\":\"/~bad\",\"values\":[1]}]}]}"
    ])
    fun `invalid policy definitions are rejected`(json: String) { assertFailsWith<InvalidInput> { Policy.parse(node(json)) } }
    @Test fun `parser rejects duplicate fields trailing values and excessive nesting`() {
        for (json in listOf("{\"id\":1,\"id\":2}", "{} {}", "[".repeat(40) + "0" + "]".repeat(40))) assertFailsWith<InvalidInput> { node(json) }
    }
    @Test fun `invocations reject unknown fields invalid IDs and nonobject arguments`() {
        assertFailsWith<InvalidInput> { call(id = "../secret") }
        assertFailsWith<InvalidInput> { call(args = "[]") }
        assertFailsWith<InvalidInput> { Invocation.parse(Json.obj("schemaVersion" to 1, "id" to "a", "tool" to "x", "arguments" to Json.obj(), "endpoint" to "https://evil.example")) }
    }
}
