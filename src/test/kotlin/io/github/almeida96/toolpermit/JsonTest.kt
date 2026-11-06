package io.github.almeida96.toolpermit

import org.junit.jupiter.api.Test
import kotlin.test.*

class JsonTest {
    @Test fun `unpaired surrogate escapes cannot reach approval fingerprints`() {
        for (escape in listOf("\\ud800", "\\udbff", "\\udc00", "\\udfff", "\\ud800x", "\\ud800\\ud800")) {
            assertFailsWith<InvalidInput>(escape) { call(args = """{"text":"$escape"}""") }
            assertFailsWith<InvalidInput>(escape) { call(args = """{"nested":[{"$escape":true}]}""") }
        }
        assertFailsWith<InvalidInput> { Json.digest(Json.obj("text" to "\ud800")) }
        assertFailsWith<InvalidInput> { Json.digest(Json.obj("\udfff" to true)) }
    }

    @Test fun `unicode scalar values retain stable distinct fingerprints`() {
        val emoji = call(args = """{"text":"\ud83d\ude80"}""")
        assertEquals(emoji.fingerprint, call(args = """{"text":"🚀"}""").fingerprint)
        assertNotEquals(emoji.fingerprint, call(args = """{"text":"?"}""").fingerprint)
        assertEquals("🚀", emoji.arguments["text"].textValue())
    }
}
