# Changelog

## 0.1.0 — 2026-11-01

- Add a loopback HTTP broker for agent tool invocations with bearer authentication.
- Evaluate allow, deny, and operator-approval policies with JSON argument constraints.
- Persist requests, approvals, execution claims, results, and audit events in SQLite.
- Bind review to invocation fingerprints and invalidate outstanding requests after policy changes or expiry.
- Prevent automatic replay of completed, claimed, or uncertain invocations.
- Include operator CLI commands, a local HTTP/file-write demo, and Linux/Windows CI.
