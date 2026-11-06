# Architecture and operations

ToolPermit uses Kotlin/JVM, Java's HTTP client/server, Jackson, and SQLite JDBC. It has no hosted service, provider SDK, or model dependency. Policies are evaluated locally and decisions are deterministic for the same input, policy, and time.

## Module boundaries

| Component | Responsibility |
| --- | --- |
| `Policy`, `Invocation`, `Json` | Strict configuration parsing, constraints, canonical fingerprints |
| `Store` | SQLite transactions, durable states, review, claims, audit events |
| `Broker` | Policy evaluation and claim-before-dispatch orchestration |
| `Dispatcher` | Fixed upstream endpoint, credentials, deadline, bounded response |
| `Server` | Loopback API, authentication, request validation and concurrency |
| `Main` | Operator CLI and process lifecycle |
| `Demo` | Local HTTP fixture, operator simulation, and a real sandbox file write |

## Persistence and execution

SQLite runs in WAL mode with a five-second busy timeout. State changes and their audit events commit in one `BEGIN IMMEDIATE` transaction. The execution claim commits before the HTTP request begins. Network I/O runs outside the database transaction, so an operator can review another request while a tool executes.

An invocation can be claimed only from `READY` or `APPROVED`. A conditional transition inside the write transaction prevents two connections from claiming it. The stored invocation is used for dispatch after approval. The database must remain on a local filesystem with working SQLite locking; do not place it on a shared network filesystem or a folder being synchronized while serving.

This mechanism provides at most one dispatch attempt per retained ID. It does not atomically commit a remote side effect and a local SQLite result. Deleting records, restoring an old database, or using a new ID removes that protection for the original operation.

## Recovery

`UNKNOWN` means a request was attempted but a reliable successful response was not recorded. `DISPATCHING` means it was claimed; after a process crash it may have acted, may still be running upstream, or may never have reached the upstream. Both states prevent another automatic dispatch.

1. Inspect the invocation and its audit history.
2. Locate the operation upstream using its idempotency key and application records.
3. Establish whether a side effect occurred before requesting any replacement operation.

Version 0.1.0 deliberately has no “reset and retry” command or automatic crash replay. Reconciliation is an operator task in the upstream system. Cancellation and timeout cannot undo a remote operation. If completion reached the upstream but SQLite failed while recording it, the durable claim still prevents a replay.

The policy is immutable within a running process. After restarting with a different policy, outstanding requests become stale when read or resubmitted. Existing completed results remain available. The database format uses SQLite `user_version` and an application identifier; unsupported newer versions and unrelated databases are rejected.

JSON strings and object keys must contain valid Unicode scalar values. Escaped unpaired surrogates are rejected before persistence or fingerprinting, because UTF-8 replacement could otherwise bind different arguments to the same fingerprint. Valid supplementary characters, including emoji, are accepted; existing valid request and policy fingerprints are unchanged.

## Operator commands

`pending` lists up to 100 oldest pending/approved requests and refreshes their expiration. If that batch consists of expired requests, running it again advances to the remaining records. `inspect` displays the complete arguments for a single request. `approve`/`reject` require the exact invocation fingerprint; repeated approval does not extend expiry. Approval can be rejected until the execution claim is taken.

`audit` emits the ordered state history for one invocation, without arguments or results. Actor categories are `agent`, `operator`, and `broker`; they describe the code path, not authenticated human identities. There is no cryptographic signature or tamper-evident chain.

CLI exits: `0` for a completed command; `1` for invalid configuration, conflicting review, or file/database failure; `2` when `check` denies or requires approval; `3` when review finds an expired, stale, completed, or otherwise non-approvable request. Serving continues until interrupted. HTTP invocation states are separate from CLI exit codes.

## Trust boundary and stored data

Run the broker/operator under an account that owns the database and policy. The agent should only have the API credential, without permission to read or modify those files, invoke operator commands, or call privileged upstream endpoints directly. If the agent runs with the same filesystem and process privileges as the operator, the approval separation is a workflow convention rather than a security boundary.

The API uses one shared token and lets its holder read any known invocation ID. Use a separate instance for each isolation domain. The listener is loopback-only, uses no browser session cookies, and rejects browser Origin headers. An external gateway needs its own TLS, authentication, authorization, rate limiting, and header handling; public deployment is outside the shipped defaults.

Arguments and successful results are stored in plaintext SQLite, including its WAL/SHM sidecars. The CLI does not encrypt them or set platform-specific ACLs. Keep secrets out of arguments where possible, secure the directory, and define retention appropriate to your application. Database growth is not automatically pruned; monitor disk space. Back up the database only through a consistent SQLite backup procedure or while the service is stopped.

Constraints validate the configured JSON paths. They do not scan natural-language instructions, sandbox the tool process, or prevent a tool from making its own network calls. Keep authorization and input validation inside the upstream adapter as well.

## Resource limits

| Resource | Limit/default |
| --- | --- |
| Policy file | 1 MiB; 1–100 tools |
| Constraints per tool | 20 |
| Incoming invocation | 64 KiB, including chunked bodies |
| Incoming body deadline | 5 seconds |
| Concurrent handlers | 8 by default; configurable 1–32 |
| Upstream response | 1 MiB, including chunked bodies |
| Upstream deadline | 10 seconds by default; configurable 100–120,000 ms |
| Approval lifetime | 1–86,400 seconds from creation |
| JSON nesting/string/number | 32 levels / 65,536 characters / 128 characters |
| Bearer credential | 16–8192 printable ASCII characters, without spaces |

Both byte and field limits apply. Request concurrency bounds active handlers after HTTP headers are parsed; it is not a substitute for an edge server's connection/header protections. A full handler pool returns `503` without creating an invocation. Requests and upstream calls are never automatically retried.

## Release verification

CI tests Java 21 on Linux and Windows, runs the installed demo, and preserves test/coverage artifacts. Releases publish the Linux distribution only after both jobs pass. `SHA256SUMS` verifies archive integrity; it is not a signature or an independent security assessment.
