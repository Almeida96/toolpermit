# Policy reference

A policy is loaded once at service startup. Restart the service to apply changes. There is no model-provided policy, wildcard tool matching, or remote policy reload.

Required root fields: `schemaVersion` (integer `1`), `approvalTtlSeconds` (1–86,400), and `tools` (1–100 definitions). Tool names must be unique and contain 1–128 characters, beginning with a letter or digit and otherwise allowing letters, digits, dots, underscores, colons, slashes, and hyphens. Unknown configuration fields are rejected.

## Actions

| Action | Behavior |
| --- | --- |
| `ALLOW` | Forward a valid invocation after committing its execution claim |
| `APPROVAL` | Store the request for local operator review; do not forward it yet |
| `DENY` | Record a terminal denial without forwarding |

Unlisted tools are denied. `ALLOW` and `APPROVAL` require a fixed `endpoint`; `DENY` cannot include an endpoint or token. Optional `tokenEnv` names an environment variable containing the upstream bearer token. The service checks configured credentials before starting. There is no token value in the policy file.

Remote endpoints require HTTPS. Plain HTTP is allowed only for the exact hosts `localhost`, `127.0.0.1`, and IPv6 loopback. URI credentials, query strings, and fragments are rejected. Redirects are never followed. DNS resolution and network access remain the deployment's responsibility; endpoint validation is not a general SSRF sandbox.

## Argument constraints

Each tool can have up to 20 `constraints`. All must pass. A failing or missing value produces `DENIED` with reason `ARGUMENT_CONSTRAINT`, even for a tool requiring approval. Operators cannot approve a denied request.

```json
[
  {"type": "one_of", "path": "/workspace", "values": ["sandbox", "staging"]},
  {"type": "min_number", "path": "/limit", "value": 1},
  {"type": "max_number", "path": "/limit", "value": 25}
]
```

`path` is a JSON Pointer into `arguments`. An empty path selects the whole argument object. Escape `/` as `~1` and `~` as `~0`. Paths are at most 512 characters.

`one_of` accepts 1–100 allowed JSON values. Equality compares full objects, arrays, and scalar values. Object key order does not matter; array order does. Numeric `1` and `1.0` are equivalent, but `"1"` is a string and differs. A missing value does not match an explicit `null`.

`min_number` and `max_number` are inclusive numeric bounds. Both the configured bound and actual value must be numbers. There is no string coercion. Constraints do not reject additional argument fields by themselves. To restrict the whole object, use `one_of` at the empty path, or enforce the tool's full schema in the adapter.

## Fingerprints and expiry

The invocation fingerprint is SHA-256 over canonical JSON containing the schema version, ID, tool name, and arguments. Object keys are sorted and numeric representations normalized. A retry with equivalent JSON is accepted, but dispatch uses the originally stored representation that the operator reviewed.

The policy has its own fingerprint. Any semantic change to the policy, including another tool's definition, makes pending, approved, or not-yet-claimed requests from the old policy `STALE` when the service next sees them. Whitespace and object key order do not matter; array order does. Completed results remain readable and are not executed again.

For `APPROVAL`, expiry is creation time plus `approvalTtlSeconds`. Approval does not extend the deadline. Expiry is checked on submission, status reads, review, and claim, without requiring a background scheduler. A policy change or expired approval prevents dispatch. There is no state transition that reopens a terminal request.

## Check without executing

```sh
bin/toolpermit check --policy examples/policy.json --request examples/create-note.json
```

This prints the decision and fingerprints, makes no network request, and creates no database. Exit `0` means `ALLOW`; exit `2` means denied or approval required. Invalid configuration returns `1`. A policy check does not create an approval or reserve an execution ID.
