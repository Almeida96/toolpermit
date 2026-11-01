# HTTP API

The service binds to `127.0.0.1`, default port `8787`. All routes require `Authorization: Bearer <token>` from the environment variable named by `serve --token-env`. One token represents one trust domain; there are no per-user ownership checks. Use separate service/database instances when callers must not see each other's results.

Accepted Host headers are `127.0.0.1:<port>` and `localhost:<port>`. Browser `Origin` headers and query strings are rejected. Responses are JSON with `Cache-Control: no-store`. The service does not enable CORS.

## Submit

`POST /v1/invocations`, with `Content-Type: application/json`:

```json
{
  "schemaVersion": 1,
  "id": "note-001",
  "tool": "notes.create",
  "arguments": {"workspace": "sandbox", "text": "Prepare the release checklist"}
}
```

All fields are required and extra fields are rejected. `arguments` must be an object. IDs use 1–80 letters, digits, dots, underscores, or hyphens, beginning with a letter or digit. The request cannot choose an endpoint, bearer token, approval flag, or policy.

A response includes `schemaVersion`, `id`, `tool`, `fingerprint`, `policyFingerprint`, `state`, `reason`, `createdAt`, `expiresAt`, and `result`. Timestamps are Unix milliseconds. `expiresAt` is null for requests not subject to approval expiry. `result` is null until successful completion.

| State | Meaning | Submit status |
| --- | --- | --- |
| `PENDING` | Waiting for operator approval | `202` |
| `APPROVED` | Approved, awaiting resubmission | `202` |
| `DISPATCHING` | Claimed; currently executing or interrupted before completion was recorded | `202` |
| `SUCCEEDED` | Successful HTTP/JSON result stored | `200` |
| `DENIED` | Unknown tool, explicit policy denial, or failed argument constraint | `200` |
| `REJECTED` | Refused by the operator | `200` |
| `EXPIRED` | Original approval deadline passed | `200` |
| `STALE` | Active policy differs from the request's stored policy | `200` |
| `UNKNOWN` | Execution outcome cannot be safely established | `200` |

`READY` is an internal pre-claim state for allowed operations; it can be observed briefly by a concurrent status read. Successful HTTP delivery is not the same as permission or business success. Always inspect `state`; the tool's own business result is inside `result`.

Reusing an ID with a different tool or arguments returns `409 ID_CONFLICT`. Identical requests reuse the stored state/result. Approval is not an automatic dispatch: resubmit the original request after approval. Replays of terminal outcomes never dispatch again while the record is retained.

## Read status and health

- `GET /v1/invocations/{id}` returns `200` with the stored status, or `404`. It checks expiry and policy changes but never executes a tool.
- `GET /health` returns `200` with `status: "ok"` and the application version. It also requires authentication.

There is no HTTP approval, rejection, audit export, or pending-list route. Those are local operator commands. A proposed tool call cannot approve itself by adding fields to the JSON body.

## Request errors

| HTTP status | Meaning |
| --- | --- |
| `400` | Invalid input, Host header, or query string |
| `401` | Missing/incorrect bearer credential |
| `403` | Browser Origin header rejected |
| `404` | Unknown route or invocation |
| `405` | Wrong method; `Allow` identifies the supported method |
| `408` | Request body did not finish within five seconds |
| `409` | ID reused for different content |
| `413` | Request exceeds 64 KiB |
| `415` | Expected JSON content type |
| `503` | Concurrent request limit reached |
| `500` | Internal operation failed; inspect stored status before retrying |

Errors use fixed codes and do not echo request bodies, upstream error bodies, URLs, or credentials. A lost client response does not imply that dispatch failed. Recover by reading status or resubmitting with the same ID, never by generating a replacement ID automatically.

## Upstream tool contract

For an allowed/approved request, ToolPermit sends a single HTTP POST to the endpoint fixed in the policy. The body is the original invocation envelope above. Headers include `Content-Type: application/json`, `Accept: application/json`, and `Idempotency-Key: <id>`. An optional upstream bearer credential comes from the configured `tokenEnv`.

The adapter must validate its arguments, authorize its own operation, and return a 2xx status with `Content-Type: application/json` and one valid JSON value. ToolPermit stores that value as `result`. A valid JSON `null` is permitted. A business-level failure encoded in a 2xx response is still a completed dispatch; callers must interpret the adapter's payload.

Non-2xx statuses, redirects, invalid JSON/content type, oversized bodies, and transport timeouts are `UNKNOWN`. They may occur after a side effect, so there is no automatic retry. The upstream should also honor the idempotency key when practical, but ToolPermit cannot guarantee it does so.

In your agent loop, intercept actual tool dispatch before calling the upstream service. Submit to ToolPermit instead. On `PENDING`, present the request for operator review and suspend the action. Resume with the original ID only after `APPROVED`. Return denials and uncertain outcomes to the application without asking the model to bypass the decision. An MCP tool handler can use this same HTTP integration; ToolPermit itself does not speak MCP.
