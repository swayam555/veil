# Veil test plan

> Written before the code. What was actually run, and the results, are in [TESTING.md](TESTING.md).

Veil sits between people and AI, so a bug means either leaked data or a blocked employee. The plan covers
correctness, accuracy, compatibility, performance, resilience, security and the user interface. The results
of each run are recorded in [TESTING.md](TESTING.md).

## 1. Levels

| Level | Tooling | What it proves |
|---|---|---|
| Unit | JUnit 5, AssertJ | Validators, detectors, overlap resolution, redaction vault, streaming rehydrator, policy engine, budget maths, Aho–Corasick |
| Detection accuracy | JUnit + labelled dataset (`detection-core/src/test/resources/eval`) | Precision and recall per detector, with minimum thresholds that fail the build |
| Integration | Spring Boot tests + Testcontainers (Postgres, Kafka, Redis) | Migrations, outbox → Kafka, audit consumer idempotency, Redis Lua limiter, semantic cache index |
| API / contract | Spring WebTestClient, MockMvc | Endpoint shapes, error codes, OpenAI-compatible JSON and SSE framing |
| SDK compatibility | Official `openai` Python SDK in a container (`scripts/compat-test.sh`) | A real client works unchanged against the proxy, streaming and non-streaming |
| End to end | `scripts/smoke-test.sh` against the Docker stack | Sign-in, roles, keys, policy push, redaction, blocking, rehydration, budgets, rate limits, audit appears, incidents, extension endpoints |
| Frontend unit | Angular test runner | Highlighting, formatters, chart scales, guards, services |
| Browser | Claude in Chrome against `localhost:3004` | Every console screen and the landing page work with real data, no console errors |
| Extension | Node tests for the pure logic + manual/automated run on the demo chat page | Paste and send interception, redaction, telemetry |
| Load | k6 (`scripts/load/k6-proxy.js`) | Proxy overhead p50/p95/p99 at sustained RPS against the mock provider |
| Chaos | `scripts/chaos-test.sh` | Provider down (circuit breaker + fallback), Kafka down (proxy keeps serving, audit catches up), Redis down (fail policy), key revocation propagation |
| Infrastructure | kubeconform, kustomize build, `docker compose config` | Manifests and compose files are valid |

## 2. Test cases

### Detection (unit + accuracy)

- Aadhaar: valid Verhoeff numbers with and without spaces are found; random 12-digit numbers are not; numbers
  starting with 0 or 1 are rejected.
- PAN: `ABCPE1234F` found, 4th character must be a valid holder type; GSTIN check character validated.
- Cards: Luhn-valid 13–19 digit numbers with spaces/dashes found; Luhn-invalid ones ignored; known test card prefixes.
- IBAN mod-97; US SSN rules (no 000/666/9xx area); IFSC; emails; Indian and international phone numbers; IPv4.
- Secrets: AWS access keys, GitHub tokens, OpenAI / Anthropic keys, Slack, Stripe, Google API keys, PEM private
  keys, JWTs, and high-entropy values after `password=`/`secret:`; normal words and UUIDs are not flagged as secrets.
- Names: found with context ("Mr.", "patient", "customer", "Dear"), not on capitalised common words.
- Dictionary: case-insensitive whole-word matches of company terms, overlapping terms.
- Overlaps: the higher-priority / longer span wins; adjacent findings both kept.
- Semantic: confidential-topic examples score above threshold, neutral prompts below; injection phrases detected.

### Redaction and rehydration

- Same value → same placeholder; different values → different indexes; vault round-trip restores the original text.
- Streaming: placeholders split at every possible chunk boundary are restored; text that only looks like a
  placeholder prefix is released once disproved; unknown placeholders pass through unchanged.

### Policy engine

- Strictest action wins (BLOCK > ROUTE_LOCAL > REDACT > ALLOW); team-scoped rules only apply to that team;
  minimum confidence respected; model allowlist; max-token cap; empty policy allows.

### Proxy (integration / e2e)

- Missing, unknown or revoked key → 401 in OpenAI error format.
- Redact policy: the provider receives placeholders, the client receives real values (non-stream and stream).
- Block policy: 403 with a `veil_blocked` error listing detector types, no provider call.
- ROUTE_LOCAL: request goes to the local provider.
- Rate limit → 429 with `Retry-After`; budget exceeded → 429 `budget_exceeded`.
- Provider failure → fallback provider used; breaker opens after repeated failures.
- Semantic cache: a paraphrased repeat prompt is served from cache (header `x-veil-cache: hit`).
- Every request produces exactly one audit row, even when the client retries the same `x-request-id`.

### Control plane

- Login, bcrypt, JWT claims; roles (ADMIN can write, VIEWER can only read).
- Key created is shown once and stored only as a hash; revoke → proxy rejects within ~2 s.
- Policy publish → new version, outbox row, Kafka snapshot, proxy applies it.
- Provider API keys are encrypted at rest and never returned by the API.
- Copilot: English sentence → valid rules (rule-based mode); invalid LLM output is rejected.

### Insight

- Audit events are stored once (idempotent), rollups add up to the raw rows, incidents created for BLOCK and
  high-severity findings, status transitions validated, live feed delivers new events, DLQ receives poison messages.

### Security

- Spoofed `X-User-*` headers are stripped at the gateway; console APIs need a JWT; viewer cannot write.
- Audit tables and logs never contain raw detected values (test searches stored rows for the planted values).
- Extension endpoints require an enrollment token.

### UI (browser)

- Landing page renders and animates; sign-in with both roles; each console screen loads real data; the
  playground shows highlights, trace and rehydrated output; policy edit + publish + dry-run; key create and
  revoke; incident triage; extension download; no errors in the browser console; usable at mobile width.

## 3. Exit criteria

- All automated tests pass (`./mvnw verify`, frontend tests, extension tests).
- Detection thresholds met: precision ≥ 0.95 and recall ≥ 0.90 for every pattern detector on the eval set.
- Smoke, compatibility and chaos scripts pass against a fresh `docker compose up`.
- Proxy overhead p95 under 25 ms at 100 RPS on the mock provider (on the development laptop).
  *Result:* Scan API p95 24 ms at 90 req/s (plus 20 chat req/s), 45 ms at 120 req/s, 0 errors; beyond ~130 req/s per node
  the proxy sheds load according to the fail mode. See [TESTING.md](TESTING.md#load-k6-scriptsload-testsh).
- Every console screen verified in Chrome with no console errors.
- Kubernetes manifests pass kubeconform.
