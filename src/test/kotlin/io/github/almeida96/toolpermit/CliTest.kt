package io.github.almeida96.toolpermit

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.test.*

class CliTest {
    @TempDir lateinit var dir: Path
    private data class Result(val code: Int, val stdout: String, val stderr: String)
    private fun invoke(vararg args: String): Result {
        val java = Path.of(System.getProperty("java.home"), "bin", if (System.getProperty("os.name").startsWith("Windows")) "java.exe" else "java")
        val out = Files.createTempFile(dir, "out", ".txt"); val err = Files.createTempFile(dir, "err", ".txt")
        val process = ProcessBuilder(listOf(java.toString(), "-cp", System.getProperty("toolpermit.classpath"), "io.github.almeida96.toolpermit.MainKt") + args)
            .redirectOutput(out.toFile()).redirectError(err.toFile()).start()
        try { assertTrue(process.waitFor(25, TimeUnit.SECONDS), "CLI did not exit"); return Result(process.exitValue(), Files.readString(out), Files.readString(err)) }
        finally { if (process.isAlive) process.destroyForcibly() }
    }
    @Test fun `demo exercises real HTTP approval and idempotency with inspectable artifacts`() {
        val out = dir.resolve("demo"); val result = invoke("demo", "--out", out.toString())
        assertEquals(0, result.code, result.stderr)
        val summary = Json.read(out.resolve("summary.json"), 65536)
        assertEquals(0, summary["writesBeforeApproval"].intValue()); assertEquals(1, summary["writesAfterReplay"].intValue())
        assertTrue(Files.isRegularFile(out.resolve("audit.json")))
        assertEquals("Release checklist", Files.readString(out.resolve("notes/write-1.txt")))
        assertEquals(1, invoke("demo", "--out", out.toString()).code)
    }
    @Test fun `operator CLI approves the exact fingerprint and rejects mismatch`() {
        val db = dir.resolve("operator.db"); val request = call(args = "{\"text\":\"Revisão de amanhã\"}")
        Store(db).use { it.register(request, policy(), System.currentTimeMillis()) }
        val inspected = invoke("inspect", "--db", db.toString(), "--id", request.id)
        assertEquals(0, inspected.code); assertTrue(node(inspected.stdout).has("arguments"))
        assertEquals("Revisão de amanhã", node(inspected.stdout)["arguments"]["text"].textValue())
        assertEquals(1, invoke("approve", "--db", db.toString(), "--id", request.id, "--fingerprint", "0".repeat(64)).code)
        assertEquals(0, invoke("approve", "--db", db.toString(), "--id", request.id, "--fingerprint", request.fingerprint).code)
        assertEquals(0, invoke("reject", "--db", db.toString(), "--id", request.id, "--fingerprint", request.fingerprint).code)
        assertEquals(3, invoke("approve", "--db", db.toString(), "--id", request.id, "--fingerprint", request.fingerprint).code)
    }
    @Test fun `missing operator database is not silently created`() {
        val missing = dir.resolve("missing.db")
        assertEquals(1, invoke("pending", "--db", missing.toString()).code); assertFalse(Files.exists(missing))
    }
    @Test fun `version and invalid option diagnostics have stable exit codes`() {
        assertEquals(VERSION, invoke("--version").stdout.trim())
        val result = invoke("serve", "--token", "PRIVATE_TOKEN_VALUE")
        assertEquals(1, result.code); assertFalse(result.stderr.contains("PRIVATE_TOKEN_VALUE"))
    }
}
