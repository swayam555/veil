# Veil: product plan and system design

Written before the code, as the build plan. [RESEARCH.md](RESEARCH.md) explains why this problem was chosen.

## 1. Product

**Veil is an open-source AI data firewall.** It lets a company use ChatGPT, Claude, Gemini and any LLM API
while making sure customer data, secrets and confidential material never leave, and it gives the security
team the audit trail and the controls (budgets, model allowlists, routing) they need.

### Three ways to adopt it (all free)

| Adoption | For | How |
|---|---|---|
| **Browser extension** (Chrome / Edge, Manifest V3) | Employees using chat websites | Scans text when it's pasted or sent on chatgpt.com, claude.ai, gemini.google.com, copilot.microsoft.com, perplexity.ai and chat.deepseek.com. Offers *Send redacted* / *Send anyway (logged)* / *Cancel*. Reports which AI sites are used (shadow-AI discovery). |
| **Drop-in LLM proxy** (OpenAI-compatible) | Developers and AI apps | Change `base_url` to Veil and use a Veil virtual key. Works with every OpenAI SDK, LangChain, LlamaIndex and curl. Streaming supported. Veil redacts, routes, enforces budgets, then puts the real values back into the answer. |
| **Scan API** (+ a tiny Java client) | Any system | `POST /v1/scan` returns findings and a redacted copy. Use it before storing tickets, logs or documents. |

### Personas and screens (Angular console)

| Screen | Purpose |
|---|---|
| Landing `/` | The story, a live animated redaction demo, the three adoption paths, the architecture |
| Sign in `/login` | Console users (security admin, viewer) |
| Overview `/console` | KPIs, live request feed (SSE), traffic and findings over time, top detectors, teams, spend |
| Playground `/console/playground` | Type a prompt, see findings highlighted as you type, send it through the real proxy, compare *what the user wrote / what the model saw / what the model answered / what the user got back*, with the decision trace and latency breakdown |
| Activity `/console/activity` | Searchable audit log of every request (never stores raw sensitive values) |
| Incidents `/console/incidents` | Blocked or high-risk events with an AI explanation, severity and a triage workflow |
| Policies `/console/policies` | Rule editor (detector → action, per team), model allowlist, fail-open/closed; an **AI policy copilot** that turns plain English into rules; dry-run any text against a draft |
| Keys `/console/keys` | Virtual API keys per team or app: budgets, rate limits, revoke (propagates to every proxy instance in about a second) |
| Providers `/console/providers` | Upstream LLMs, routes and fallbacks, live circuit-breaker state |
| Shadow AI `/console/shadow-ai` | What the extension saw: AI sites in use, by team, pastes blocked or redacted |
| Dictionaries `/console/dictionaries` | Company-specific terms: project codenames, client names, internal hostnames |
| Compliance `/console/compliance` | DPDP / GDPR / HIPAA mapping, evidence summary, CSV export |
| Integrations `/console/integrations` | Download the extension, copy-paste snippets for the proxy and Scan API |

## 2. Architecture

```
                 ┌──────────────── Browser extension (MV3) ───────────────┐
                 │ content script on AI sites → /v1/scan, /v1/telemetry   │
                 └───────────────────────────┬────────────────────────────┘
 Apps / SDKs ── /v1/chat/completions ──┐     │
                                       ▼     ▼
 Angular console ── /api/** ──► edge-gateway (Spring Cloud Gateway: JWT, roles, Redis rate limits)
                                       │
          ┌────────────────────────────┼─────────────────────────────┐
          ▼                            ▼                             ▼
   guard-proxy (WebFlux)        control-plane (MVC)          insight-service (MVC)
   hot path, stateless          orgs, users, keys,           Kafka consumer, audit store,
   detect → decide → redact     policies, providers,         rollups, incidents, live SSE,
   → route → rehydrate          dictionaries, AI copilot     AI incident explainer, reports
          │   │   │                 │ outbox                       ▲
          │   │   └─ Redis: key cache, token buckets, budgets, semantic cache (vector index)
          │   └──── Kafka veil.audit ───────────────────────────────┘
          └──── Kafka veil.config (compacted, broadcast) ◄── control-plane
          ▼
   LLM providers: built-in "veil-echo" mock (free, default) · OpenAI-compatible (OpenAI, Groq, Gemini,
   OpenRouter, Ollama) · Anthropic
```

### Services

| Module | Tech | Responsibility |
|---|---|---|
| `contracts` | Java records | Kafka event types and topic names shared by services |
| `detection-core` | Plain Java 21 library, no Spring | Detectors, validators, overlap resolution, reversible redaction vault, streaming rehydrator, policy engine. Pure functions, heavily unit-tested, embeddable in any JVM app |
| `guard-proxy` | Spring WebFlux + Reactor Netty, Lettuce, Resilience4j, LangChain4j ONNX embeddings | The data plane. OpenAI-compatible endpoints, Scan API, extension telemetry |
| `control-plane` | Spring MVC, JDBC, Flyway, Postgres | Configuration and identity. Transactional outbox → `veil.config` |
| `insight-service` | Spring MVC, JDBC, Flyway, Postgres, Kafka batch listener | Audit log, minute rollups, incidents, live feed, reports |
| `edge-gateway` | Spring Cloud Gateway | Console API edge: JWT verification, role checks, header stripping, per-route rate limits |
| `frontend` | Angular 22 (standalone, signals, zoneless), Tailwind CSS v4, hand-built SVG charts | Landing page and console, served by nginx |
| `extension` | Chrome MV3, plain TypeScript-free JS | Content script, background worker, options page |
| `clients/java` | Java 21, `java.net.http` only | Tiny client for the Scan API |

### The hot path (one chat request)

1. **Authenticate** the virtual key: SHA-256 of the key → local cache → Redis → control-plane (cold start only).
   Revoked keys are pushed out through `veil.config`.
2. **Admission:** Redis Lua token bucket (requests per minute per key) and a daily spend budget per key and team.
   Over limit → `429` with `Retry-After`.
3. **Detect** on every message: pattern detectors with checksums, the company dictionary (Aho–Corasick),
   context-scored names, and two semantic detectors on in-process embeddings (confidential-topic classifier
   and prompt-injection detector). Detection has a time budget; on timeout the org's fail-open/closed setting applies.
4. **Decide** with the policy engine: each rule maps detectors (and teams, minimum confidence) to
   `ALLOW`, `REDACT`, `BLOCK`, or `ROUTE_LOCAL` (send to an on-prem model instead). The strictest action wins.
   Model allowlists and max-token caps are checked here too.
5. **Redact** reversibly: the same value always gets the same placeholder (`[EMAIL_1]`), the vault lives only in
   request memory and is never stored.
6. **Semantic cache** (optional per policy): embed the *redacted* prompt and search a Redis vector index; a close
   enough hit returns the cached answer without calling the provider.
7. **Route** to the provider chosen by the model alias, with a timeout, circuit breaker and fallback chain.
8. **Rehydrate** the answer, including SSE streams, where a placeholder can be split across chunks (the
   rehydrator holds back only a possible placeholder prefix).
9. **Scan the output** for secrets or personal data the model produced on its own and apply the output policy.
10. **Audit:** publish one `AuditEvent` to Kafka (keyed by org) *after* responding. It contains detector types,
    counts, offsets, salted value fingerprints, the redacted prompt (optional) and timings, never raw values.
    If Kafka is unavailable, events go to a bounded in-memory buffer and are retried; the request is not failed.

### Kafka topics

| Topic | Key | Producer → consumer | Notes |
|---|---|---|---|
| `veil.config` | orgId | control-plane (outbox) → every guard-proxy instance | Compacted; full config snapshot per org; each proxy instance uses its own consumer group so all instances receive every change |
| `veil.audit` | orgId | guard-proxy → insight-service | 6 partitions; batch consumer; idempotent insert on request id |
| `veil.audit.dlq` | orgId | insight-service | Poison messages after retries |

### Redis keys

| Key | Type | Use |
|---|---|---|
| `veil:key:{sha256}` | string (JSON) | Virtual key → org, team, limits; TTL 5 min |
| `veil:rl:{keyId}` | hash | Token bucket, updated atomically by a Lua script |
| `veil:spend:{day}:{keyId}` / `…:team:{teamId}` | string (micro-dollars) | Daily budgets, `INCRBY` + expiry |
| `veil:cache:{orgId}:{id}` + index `veil-semcache` | hash with vector field | Semantic cache (RediSearch HNSW, cosine) |
| `rate-limit` keys from Spring Cloud Gateway | — | Console API rate limits |

### Data model (Postgres, one database per service)

- **control-plane:** `orgs`, `teams`, `users` (bcrypt), `virtual_keys` (sha256, prefix, team, limits, revoked_at),
  `policies` (versioned JSON rules, one active per org), `policy_versions`, `providers` (base URL, encrypted
  API key with AES-GCM), `routes` (model alias → provider chain), `dictionaries`, `extension_enrollments`,
  `outbox_events`.
- **insight-service:** `requests` (partitioned by day; one row per request), `request_findings`,
  `usage_minute` (rollups per org/team/minute, upserted), `incidents` (status workflow), `extension_events`.

### Scalability and resilience

- guard-proxy is stateless and needs no database on the hot path; scale it on CPU with an HPA.
- Config is pushed, not pulled: compacted Kafka topic → in-memory snapshot. A cold proxy can also fetch a snapshot
  over HTTP, so it starts even if Kafka is down.
- Audit is asynchronous and idempotent; insight-service scales with partitions (and KEDA on consumer lag in k8s).
- Requests table is partitioned by day so retention is `DROP PARTITION`, not `DELETE`.
- Circuit breakers per provider; fallback routes; per-org fail-open/fail-closed.
- Bulkheads: detection runs on a bounded scheduler, separate from Netty event loops.

### AI in Veil (every use works without a paid API)

| Feature | How | Without an API key |
|---|---|---|
| Confidential-topic detection | all-MiniLM-L6-v2 embeddings in-process (ONNX), nearest labelled exemplars | Same (fully local) |
| Prompt-injection detection | heuristics + embedding similarity to a jailbreak corpus | Same |
| Semantic cache | embeddings + Redis vector search | Same |
| Policy copilot (English → rules) | LLM with a JSON schema | Rule-based parser handles common phrasings |
| Incident explainer | LLM summary of an event (only types and redacted text are sent) | Template explanation |
| The proxied LLM itself | Any provider | Built-in `veil-echo` mock model |

### Free to run

Everything is open source and self-hosted. The demo runs on one laptop with Docker. A small company can run it
on a free-tier VM (for example a 4-core / 24 GB ARM VM with k3s). Real LLM calls can use free tiers (Gemini,
Groq) or a local Ollama model.

## 3. Build order

1. `detection-core` with tests and a labelled evaluation set (precision/recall per detector).
2. `contracts`, `control-plane` (auth, keys, policies, providers, outbox), Testcontainers tests.
3. `guard-proxy` (auth, limits, detect, redact, route, rehydrate, stream, audit, semantic cache).
4. `insight-service` (consumer, rollups, incidents, live SSE, reports).
5. `edge-gateway`, Docker Compose, smoke test.
6. Angular console and landing page.
7. Chrome extension + demo chat page for testing it.
8. Kubernetes manifests (Kustomize, HPA, PDB, NetworkPolicy, KEDA), validated with kubeconform.
9. Testing per [TEST-PLAN.md](TEST-PLAN.md): load test, OpenAI SDK compatibility test, chaos tests, browser tests.
10. README, HOW-IT-WORKS, TESTING, designer notes.
