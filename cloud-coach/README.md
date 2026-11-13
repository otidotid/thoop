# THOOP Cloud Coach Worker

This is THOOP's separately hosted, optional Cloudflare Worker for AI coaching. It uses the Workers AI `AI` binding and, when available, a `DB` D1 binding for conversation summaries and previously stored memories. It is **not** the Android Google Drive backup/restore service.

## Compatibility

- `POST /v1/coach/chat` accepts `userId`, `conversationId`, `question`, `healthContext`, `systemPrompt`, `recentMessages` and optional `localHour`.
- The Android-compatible response retains `answer`, `conversationId`, `intent`, `nutrition`, and `references`, and adds `memorySaved` (boolean) so clients can detect degraded cloud memory.
- `/` responds to unauthenticated health checks without exposing infrastructure configuration.
- Inputs are size-checked (128 KiB total), validated, and sanitized. Missing/invalid input is a 4xx response rather than a server error.
- If D1 fails temporarily, the worker still answers; `memorySaved: false` means no durable summary was saved for that turn.
- External lookup failures are non-fatal. Health references use fixed public topics, not the user's personal question; nutrition lookups use fixed food keywords. The model must not invent measurements or sources.

## Deployment and security considerations

**Do not deploy from a PR without reviewing your existing Worker configuration, D1 schema, Android client URL, and permissions.** Nothing in this repository automatically deploys this worker. You must use your existing Cloudflare deployment workflow.

1. Workers AI: configure an `AI` binding. Without it, requests return HTTP 503.
2. D1: configure a `DB` binding containing `coach_conversations` and `coach_memories`, or use stateless coaching. The existing schema/query names are unchanged.
3. Privacy: user health questions and `healthContext` are sent to the configured Workers AI inference service, while conversation summaries may be persisted in D1. Obtain user consent and set appropriate retention controls.
4. External evidence: set `DISABLE_EXTERNAL_REFERENCES = "true"` to disable calls to Europe PMC and Open Food Facts; this does not disable Workers AI.
5. Optional deployment gate: configure `COACH_ACCESS_TOKEN` as a **secret**, and have your caller send `Authorization: Bearer <token>`. The current Android client **does not** send that header, so enabling the secret before changing the client will cause HTTP 401. This is only a shared deployment gate, **not secure per-user authentication**. Do not embed a shared bearer token in a publicly distributed APK and mistake it for user identity.
6. Abuse protection: optionally bind Cloudflare `COACH_RATE_LIMITER` (rate limiting binding with `.limit({ key })`) and configure Cloudflare WAF rules. The limiter uses a hash of the edge-supplied IP as the key. Without the binding there is no in-code rate limit.
7. **Unresolved production security boundary:** `userId` is supplied by the Android caller and hashed, but is not cryptographically authenticated. Anyone who knows another user's ID can potentially access their stored memories, and the endpoint can be abused without Cloudflare-side access rules. Before advertising this as a secure multi-user service, add proper per-user authorization (e.g. verified identity tokens) and enforce WAF/rate limiting. Prefer disabling server-side memory until that is in place if the endpoint is open to untrusted callers.
8. Backups: never include health-data exports, tokens, `.env`, or Cloudflare API credentials in this repository.

## Tests

Requires Node 20 or newer. No external dependencies and no live Cloudflare account are needed.

```bash
cd cloud-coach
npm test
```

Tests use mocked Workers AI, D1, and fetch, and cover input limits, response compatibility, auth-gate behavior, D1 failover, third-party lookup privacy, food estimation, and upstream errors. These tests **do not** prove real Cloudflare deployment behaviour, latency, identity security, or clinical correctness. Verify a staged Worker with the Android app before rollout.
