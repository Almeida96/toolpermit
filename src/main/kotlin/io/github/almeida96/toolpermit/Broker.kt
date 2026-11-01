package io.github.almeida96.toolpermit

import java.time.Clock

internal class Broker(val policy: Policy, private val store: Store, private val executor: Executor, private val clock: Clock = Clock.systemUTC()) {
    fun submit(call: Invocation): Record {
        val initial = store.register(call, policy, clock.millis())
        if (initial.state !in setOf(State.READY, State.APPROVED)) return initial
        if (!store.claim(call.id, policy.fingerprint, clock.millis())) return status(call.id)!!
        val outcome = try { executor.execute(initial.call, policy.tools.getValue(initial.call.tool)) }
        catch (_: Exception) { Outcome(State.UNKNOWN, "DISPATCH_UNCERTAIN") }
        return store.finish(call.id, outcome.state, outcome.reason, outcome.result, clock.millis())
    }
    fun status(id: String): Record? = store.get(id, clock.millis(), policy.fingerprint)
}
