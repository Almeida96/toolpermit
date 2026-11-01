package io.github.almeida96.toolpermit

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

class StoreTest {
    @TempDir lateinit var dir: Path
    @Test fun `approval holds execution and a repeated submission returns the stored result`() {
        val invoked = AtomicInteger(); val clock = MutableClock()
        Store(dir.resolve("approval.db")).use { store ->
            val broker = Broker(policy(), store, { _, _ -> invoked.incrementAndGet(); Outcome(State.SUCCEEDED, "DONE", node("{\"ok\":true}")) }, clock)
            val pending = broker.submit(call())
            assertEquals(State.PENDING, pending.state); assertEquals(0, invoked.get())
            store.review(pending.call.id, pending.call.fingerprint, true, clock.millis())
            assertEquals(State.SUCCEEDED, broker.submit(call()).state)
            assertEquals(State.SUCCEEDED, broker.submit(call()).state)
            assertEquals(1, invoked.get())
            assertEquals(listOf("PENDING", "APPROVED", "DISPATCHING", "SUCCEEDED"), store.audit("request-1")["events"].map { it["state"].textValue() })
        }
    }
    @Test fun `changed arguments and incorrect review fingerprints never execute`() {
        Store(dir.resolve("conflict.db")).use { store ->
            val p = policy(); val request = call(); store.register(request, p, 100)
            assertFailsWith<Conflict> { store.register(call(args = "{}"), p, 200) }
            assertFailsWith<Conflict> { store.review(request.id, "0".repeat(64), true, 200) }
            assertEquals(State.PENDING, store.get(request.id, 200)!!.state)
        }
    }
    @Test fun `approval expires at its original deadline even after review`() {
        Store(dir.resolve("expiry.db")).use { store ->
            val p = policy(ttl = 1); val request = call()
            store.register(request, p, 1000); store.review(request.id, request.fingerprint, true, 1500)
            assertFalse(store.claim(request.id, p.fingerprint, 2000))
            assertEquals(State.EXPIRED, store.get(request.id, 2000)!!.state)
            assertEquals(State.EXPIRED, store.review(request.id, request.fingerprint, true, 2100).state)
        }
    }
    @Test fun `expired review is committed rather than rolled back`() {
        Store(dir.resolve("expired-review.db")).use { store ->
            val request = call(); store.register(request, policy(ttl = 1), 0)
            assertEquals(State.EXPIRED, store.review(request.id, request.fingerprint, true, 1001).state)
            assertEquals("EXPIRED", store.audit(request.id)["events"].last()["state"].textValue())
        }
    }
    @Test fun `policy changes invalidate a pending or approved invocation`() {
        Store(dir.resolve("policy.db")).use { store ->
            val request = call(); store.register(request, policy(), 1000)
            store.review(request.id, request.fingerprint, true, 1001)
            assertFalse(store.claim(request.id, policy("ALLOW").fingerprint, 1002))
            assertEquals(State.STALE, store.get(request.id, 1002)!!.state)
        }
    }
    @Test fun `operator rejection is terminal and unknown tools cannot be approved`() {
        Store(dir.resolve("deny.db")).use { store ->
            val request = call(); store.register(request, policy(), 1000)
            store.review(request.id, request.fingerprint, false, 1001)
            assertEquals(State.REJECTED, store.review(request.id, request.fingerprint, true, 1002).state)
            val unknown = call("other", "unknown"); store.register(unknown, policy(), 1000)
            assertEquals(State.DENIED, store.review(unknown.id, unknown.fingerprint, true, 1002).state)
        }
    }
    @Test fun `claims are exclusive across separate SQLite connections`() {
        val path = dir.resolve("concurrent.db"); val p = policy("ALLOW"); val ready = CountDownLatch(2); val start = CountDownLatch(1)
        Store(path).use { it.register(call(), p, 1000) }
        Executors.newFixedThreadPool(2).use { pool ->
            val futures = (1..2).map { pool.submit<Boolean> { Store(path).use { store -> ready.countDown(); assertTrue(start.await(5, TimeUnit.SECONDS)); store.claim("request-1", p.fingerprint, 1001) } } }
            assertTrue(ready.await(5, TimeUnit.SECONDS)); start.countDown()
            assertEquals(1, futures.count { it.get(5, TimeUnit.SECONDS) })
        }
    }
    @Test fun `restart retains approval and in flight calls are never automatically replayed`() {
        val path = dir.resolve("restart.db"); val request = call(); val p = policy()
        Store(path).use { it.register(request, p, 1000); it.review(request.id, request.fingerprint, true, 1001) }
        Store(path).use { assertTrue(it.claim(request.id, p.fingerprint, 1002)) }
        Store(path).use { store ->
            val broker = Broker(p, store, { _, _ -> fail("A claimed request must not be replayed") }, MutableClock(1003))
            assertEquals(State.DISPATCHING, broker.submit(request).state)
        }
    }
    @Test fun `uncertain dispatch is terminal and does not leak exception contents`() {
        Store(dir.resolve("uncertain.db")).use { store ->
            val count = AtomicInteger()
            val broker = Broker(policy("ALLOW"), store, { _, _ -> count.incrementAndGet(); error("PRIVATE_UPSTREAM_TOKEN") }, MutableClock())
            assertEquals(State.UNKNOWN, broker.submit(call()).state)
            assertEquals(State.UNKNOWN, broker.submit(call()).state)
            assertEquals(1, count.get()); assertFalse(store.audit("request-1").toString().contains("PRIVATE_UPSTREAM_TOKEN"))
        }
    }
    @Test fun `audit omits arguments and results but inspection supports informed review`() {
        Store(dir.resolve("privacy.db")).use { store ->
            val request = call(args = "{\"text\":\"PRIVATE_BODY\"}"); store.register(request, policy(), 1000)
            assertTrue(store.get(request.id, 1001)!!.reviewJson().toString().contains("PRIVATE_BODY"))
            assertFalse(store.audit(request.id).toString().contains("PRIVATE_BODY"))
            assertFalse(store.get(request.id, 1001)!!.publicJson().has("arguments"))
        }
    }
    @Test fun `equivalent retries execute the stored reviewed representation`() {
        Store(dir.resolve("representation.db")).use { store ->
            val original = call(args = "{\"amount\":1}")
            val broker = Broker(policy(), store, { executed, _ ->
                assertTrue(executed.arguments["amount"].isIntegralNumber)
                Outcome(State.SUCCEEDED, "DONE", node("{}"))
            }, MutableClock())
            broker.submit(original); store.review(original.id, original.fingerprint, true, 1000001)
            assertEquals(State.SUCCEEDED, broker.submit(call(args = "{\"amount\":1.0}")).state)
        }
    }
    @Test fun `opening an unrelated database does not add broker tables`() {
        val path = dir.resolve("unrelated.db")
        java.sql.DriverManager.getConnection("jdbc:sqlite:$path").use { connection -> connection.createStatement().use { it.execute("CREATE TABLE other_data (id INTEGER PRIMARY KEY)") } }
        assertFailsWith<InvalidInput> { Store(path) }
        java.sql.DriverManager.getConnection("jdbc:sqlite:$path").use { connection -> connection.createStatement().use {
            it.executeQuery("SELECT name FROM sqlite_master WHERE type='table'").use { rows -> assertTrue(rows.next()); assertEquals("other_data", rows.getString(1)); assertFalse(rows.next()) }
        } }
    }
}
