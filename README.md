# Veil

**An open-source AI data firewall. Your people and your apps keep using ChatGPT, Claude, Gemini and any LLM API;
customer data, secrets and confidential work never leave, and the answers still come back complete.**

`Java 21` · `Spring Boot 4` · `WebFlux` · `Spring Cloud Gateway` · `Kafka` · `Redis 8 (vector search)` · `PostgreSQL` ·
`ONNX embeddings (in-process AI)` · `LangChain4j` · `Resilience4j` · `Angular 22` · `Chrome MV3 extension` · `Docker` · `Kubernetes`

> **Who is this page for?** Everyone. Start with [The problem](#the-problem) and [What Veil does](#what-veil-does) for
> the product, [Run it](#run-it) and [How to test it](#how-to-test-it) to try it, and
> [How it works](docs/HOW-IT-WORKS.md) for the full walkthrough. Designers: see [For designers](#for-designers).
> Why this problem was chosen: [docs/RESEARCH.md](docs/RESEARCH.md).

---

## The problem

- **Everyone pastes company data into AI.** Employees put customer records, contracts, salary sheets, API keys and source
  code into chat websites to work faster, often from personal accounts IT can't see (Netskope 2026: 47% of enterprise GenAI
  users). Source code is the most-uploaded data type in DLP events involving GenAI (Verizon DBIR 2026).
- **Apps do it automatically.** A support bot built on an LLM sends the whole ticket, phone number and all, to a model in
  another country. Gartner expects more than 40% of AI-related data breaches to come from cross-border GenAI misuse by 2027.
- **Regulators now have teeth.** India's DPDP Rules 2025: penalties from November 2026, up to ₹250 crore per instance for
  failing to keep reasonable safeguards. GDPR and HIPAA already apply.
- **Banning AI doesn't work.** People use their phones. Companies need a way to say *yes* to AI while keeping the
  sensitive parts at home. 80% of organisations worry about GenAI data leakage; 60% have no strategy for it (Mimecast 2026).

## What Veil does

| For | What they get |
|---|---|
| **Employees** (browser extension) | On ChatGPT, Claude, Gemini, Copilot, Perplexity, DeepSeek and others, a paste or message containing sensitive data is caught before it leaves. One click sends a redacted version; secrets are refused. Nothing changes for harmless text. |
| **Developers** (drop-in proxy) | An OpenAI-compatible endpoint. Change `base_url` and the key; streaming, usage, errors and every SDK keep working. Veil replaces values with placeholders like `[EMAIL_1]` on the way out and **puts the real values back in the answer**, even mid-stream. |
| **Any system** (Scan API) | `POST /v1/scan` finds and redacts sensitive data in tickets, transcripts, logs or documents before they are stored or indexed for RAG. A small Java client is included. |
| **Security teams** (console) | Live dashboard, a searchable audit log that **never stores sensitive values**, incidents with an AI explanation, versioned policies with a plain-English **policy copilot** and dry runs, per-team keys, budgets and rate limits, provider routing with failover, shadow-AI discovery and compliance evidence (DPDP, GDPR, HIPAA, PCI DSS, SOC 2). |

**What it finds (20 kinds):** Aadhaar (Verhoeff checksum), PAN, GSTIN (check character), IFSC, bank accounts, payment cards
(Luhn + card prefixes), IBAN (mod-97), US SSN, emails, phone numbers, person names, IP addresses, API keys and tokens
(AWS, GitHub, OpenAI, Anthropic, Slack, Stripe, Google), private keys, JWTs, passwords, company codenames and client names,
source code, **confidential topics** (unreleased financials, M&A, HR, health, legal, security incidents, strategy) and
**prompt injection**. The last two have no pattern at all: they are recognised by meaning, with an embedding model that runs
inside the proxy.

**What it can do about each finding:** allow, flag, **redact** (and restore), **keep on-premises** (route to the company's own
model instead of the cloud), or **block**, per team and per channel. The strictest matching rule wins.

### Screens

| Screen | URL | What you see |
|---|---|---|
| Landing page | `/` | The story, an animated redaction demo, the research, how it works, the architecture |
| Sign in | `/login` | Two demo accounts: a security admin and a read-only auditor |
| Overview | `/console` | Requests, items protected, blocked, spend; traffic chart; live request feed (SSE); findings by type; teams; latency |
| Playground | `/console/playground` | Type anything and watch findings highlight as you type; send it through the real pipeline and see **what you wrote → what the model saw → what it answered → what you got back**, with the decision, the rules and where the time went |
| Activity | `/console/activity` | Searchable, filterable audit log with a detail drawer (redacted prompt, findings, fingerprints); CSV export |
| Incidents | `/console/incidents` | Blocked and critical events, repeats folded into one incident, an AI explanation, triage (acknowledge, resolve, false positive) |
| Policies | `/console/policies` | Rule editor, settings (fail open/closed, cache, output scanning, model allowlist), the policy copilot, a dry run against the draft, version history and rollback |
| Company terms | `/console/dictionary` | Codenames and client names, and example sentences that teach Veil new confidential topics |
| Keys & teams | `/console/keys` | Virtual keys (shown once), rate limits, budgets, revocation; team daily budgets |
| Providers | `/console/providers` | Providers and routes with fallbacks, live circuit-breaker state, fault injection to rehearse an outage |
| Shadow AI | `/console/shadow-ai` | Which AI sites people use, by team, and the risky pastes |
| Compliance | `/console/compliance` | Detected vs protected per regulation, and the controls that keep the audit log safe |
| Integrations | `/console/integrations` | Download the extension; copy-paste snippets for Python, Node, curl, LangChain4j, LangChain and the Scan API |
| Demo chat | `/demo/chat` | A pretend AI chat site for trying the extension safely |

---

## Architecture

```mermaid
flowchart LR
    EXT[Browser extension<br/>Chrome MV3] -- "/v1/extension/*" --> PX
    APP[Apps and SDKs<br/>OpenAI-compatible] -- "/v1/chat/completions" --> PX
    UI[Angular console<br/>nginx] -->|/api| GW[edge-gateway<br/>JWT · roles · Redis rate limits]
    GW --> CP[control-plane<br/>orgs · keys · policies · providers · copilot]
    GW --> IN[insight-service<br/>audit · incidents · live feed · reports]
    GW -->|/api/playground| PX[guard-proxy ×N<br/>WebFlux · detect → decide → redact<br/>→ cache → route → restore]

    CP -- "outbox → veil.config (compacted)" --> K[(Kafka)]
    CP -- "veil.keys (revocations)" --> K
    K -- "every proxy, own consumer group" --> PX
    PX -- "veil.audit (after the response)" --> K
    K -- "batches, idempotent" --> IN

    PX --- R[(Redis 8<br/>key cache · token buckets · budgets<br/>vector index: semantic cache)]
    PX --> LLM[LLM providers<br/>cloud · on-prem · simulated]
    CP --- PG1[(Postgres: control)]
    IN --- PG2[(Postgres: insight<br/>day-partitioned)]
    CP -. optional .-> AI[LLM for the copilot]
    IN -. optional .-> AI
```

### The life of one request

```mermaid
sequenceDiagram
    participant A as Support app
    participant P as guard-proxy
    participant R as Redis
    participant M as LLM provider
    participant K as Kafka
    participant I as insight-service
    A->>P: POST /v1/chat/completions (Veil key) "Reply to Priya Sharma, priya@gmail.com…"
    P->>P: key from memory/Redis · token bucket · budget
    P->>P: detect (patterns + checksums, names, dictionary, embeddings) · decide by policy
    P->>P: redact: "Reply to [PERSON_1], [EMAIL_1]…" (mapping stays in this request's memory)
    P->>R: semantic cache lookup on the REDACTED prompt (vector search)
    P->>M: send redacted request (route, timeout, circuit breaker, failover)
    M-->>P: "Dear [PERSON_1], …" (streamed or not)
    P->>P: scan the answer · put "Priya Sharma" back, even across stream chunks
    P-->>A: "Dear Priya Sharma, …" + x-veil-action: REDACT
    P--)K: one audit event: types, offsets, salted fingerprints (never values)
    K--)I: stored once, rolled up, maybe an incident, pushed to the live feed
```

## Engineering decisions (and why)

| Decision | Why |
|---|---|
| **No database on the hot path** | Policies, dictionaries, providers and routes reach every proxy through a **compacted Kafka topic** and live in memory; keys are cached in memory and Redis. The proxy is stateless and scales horizontally. A cold proxy also pulls snapshots over HTTP, so it starts even if Kafka is down. |
| **Every instance hears every config change** | Each proxy consumes `veil.config` and `veil.keys` with **its own consumer group** (broadcast, not work-sharing). Revoking a key is enforced everywhere within about a second (measured: under 2.5 s end to end through the UI). |
| **Transactional outbox** | A policy version and its Kafka event commit in one Postgres transaction, so a published policy can't be missing from Kafka, and a crash can only delay a push. |
| **Checksums, not just regexes** | A random 12-digit number passes Aadhaar's Verhoeff check one time in ten, a random card number passes Luhn one time in ten. Context words ("Aadhaar", "card", "account number") raise or lower confidence; some types only fire with context. |
| **One pass for company terms** | The dictionary is an **Aho–Corasick** automaton: 10,000 codenames cost the same per character as ten. |
| **AI that runs inside the proxy** | Confidential topics and paraphrased injections are found with **all-MiniLM-L6-v2 on ONNX Runtime, in-process**: no API cost, nothing leaves, works offline. A k-nearest-neighbour vote over labelled examples, with "neutral" examples as the counterweight; a company adds a topic by adding sentences. |
| **Dynamic batching of embeddings** | Under load, texts waiting for the model are embedded together in one inference (the technique GPU inference servers use, applied on CPU). The first version ran one inference per text and let every inference use every core: at 140 req/s the median embedding took 230 ms. |
| **Load shedding instead of a timeout convoy** | When detection is saturated, a new request is decided immediately by the organisation's **fail mode**: *closed* answers 503 with `Retry-After`, *open* falls back to the fast pattern detectors and marks the request degraded. Before this, an overload made every queued request time out together. |
| **Reversible, consistent redaction** | The same value always gets the same placeholder across the whole conversation, so the model can still reason ("the same customer"). The mapping exists only in the request's memory. |
| **Streaming rehydration** | A placeholder can be split across SSE chunks (`[EMA` + `IL_1]`). The rehydrator holds back only a possible placeholder prefix and releases everything else at once. Tested at every split point. |
| **Semantic cache on the redacted prompt** | Redis 8's built-in vector search (HNSW, cosine). Because placeholders are positional, two customers' similar requests share one cached answer, and each user gets it back with *their* customer's name. Sensitive values never enter the cache. `Cache-Control: no-cache` skips it. |
| **Failover and circuit breakers per provider** | Routes are ordered provider chains (e.g. cloud → backup region). Resilience4j opens a breaker after repeated failures so requests stop waiting for timeouts. A stream fails over only before its first chunk. |
| **Audit without the data** | Audit events carry types, offsets and **salted HMAC fingerprints** (the same Aadhaar always has the same fingerprint, so repeats can be traced) plus the redacted prompt. Blocked prompts are stored with every finding masked. A test greps stored rows for the planted values. |
| **Exactly-once effect on at-least-once Kafka** | The audit table's primary key includes the request id; inserts are `ON CONFLICT DO NOTHING`, and rollups and incidents are only updated for rows that were new. Poison events go to a dead-letter topic instead of blocking a partition. |
| **Day-partitioned audit table** | Retention is `DROP TABLE` on an old partition, instantly, not a giant `DELETE`. Time-range queries touch only the partitions they need. Dashboards read per-minute rollups. |
| **Stale-if-error key cache** | If both Redis and the control plane are unreachable, keys the proxy has already seen keep working from memory (revocations still evict). Found by the chaos test: a control-plane outage used to take every app down. |
| **Output scanning** | Answers are scanned too: secrets or personal data that the *model* produced are masked before the user sees them. |
| **Identity at the edge** | The gateway verifies the console JWT, strips client-sent `X-User-*` headers and enforces roles (auditors can read and use the playground, not change anything). Provider API keys are AES-256-GCM encrypted at rest and in Kafka, and never returned by any API. |
| **Everything works with no API key** | Built-in simulated models (with fault injection), a rule-based policy copilot and a template incident explainer. Add a key (OpenAI-compatible, Anthropic or local Ollama) and the same features use a real LLM. |

## Measured results

On a 16-thread laptop, inside the Docker Desktop VM, with simulated providers. Details in [docs/TESTING.md](docs/TESTING.md).

| Metric | Result |
|---|---|
| Detection accuracy (510 labelled prompts with decoys) | Precision and recall 1.00 for all 15 span types, 0 mistakes, after one fix (synthetic set; see limitations) |
| Pattern detection time | 0.2–0.4 ms per prompt |
| Scan API, 90 req/s sustained (unique prompts, semantic detection on) | p95 **24 ms**, 0 errors |
| Scan API + chat, 120 req/s sustained | scan p95 45 ms, 0 errors |
| Overload, 150 req/s | fail-closed: 84% fully checked, 16% immediate 503 `Retry-After`; fail-open: 0 errors (excess checked by patterns only) |
| Embeddings, host CPU | 4–6 ms for one text; ~335–430 texts/s with dynamic batching (INT8 model: 524/s but less accurate, so optional) |
| Key revocation to enforcement | under 2.5 s through the UI |
| Kafka down for a burst of 20 requests | all served, 20/20 audit events delivered after recovery |
| Provider outage | traffic moves to the backup provider; circuit opens after 5 failures |

---

## Run it

Requirements: Docker with about 3.5 GB of memory for containers.

```bash
docker compose up --build            # first build takes several minutes
open http://localhost:3004            # landing page; "Try the live console" → admin@acme.test / veil-admin
```

1. In the console, press **Start simulated traffic** on the Overview. Realistic requests from six teams flow through the real
   proxy (and simulated browser extensions report pastes and site visits).
2. Open **Playground**, pick a sample ("Customer reply", "Leaked AWS key", "Unreleased results") and send it.
3. Try it from code: the OpenAI-compatible endpoint is `http://localhost:3004/v1` (or the proxy directly on `:8085`):

```bash
curl http://localhost:3004/v1/chat/completions -H "Authorization: Bearer vk_live_demo_support_7Hq2mX9pLw4nR8sT" \
  -H "Content-Type: application/json" \
  -d '{"model":"gpt-4o-mini","messages":[{"role":"user","content":"Reply to Priya Sharma (priya@gmail.com) about her refund"}]}'
```

4. Try the extension: **Integrations → Download extension**, load it unpacked in Chrome, set server `http://localhost:3004`
   and key `vk_ext_demo_support_B6wE3nU8yT1qA5zR`, then paste on the [demo chat page](http://localhost:3004/demo/chat).

Other accounts: `auditor@acme.test` / `veil-auditor` (read-only). More demo keys are in
`services/control-plane/src/main/java/dev/veil/control/seed/DemoSeeder.java`.

**Optional: real models.** Copy `.env.example` to `.env`. `UPSTREAM_*` puts a real OpenAI-compatible model (free tiers of
Groq or Gemini work, or a local Ollama) first in the `gpt-4o-mini` route, with the simulated models as fallbacks. `AI_*`
gives the policy copilot and the incident explainer an LLM.

### Develop locally

```bash
docker compose -f docker-compose.infra.yml up -d       # Postgres, Redis 8, Kafka only
./mvnw package                                         # build + 94 tests (Testcontainers needs Docker)
java -jar services/control-plane/target/control-plane-1.0.0.jar       # :8202
java -jar services/guard-proxy/target/guard-proxy-1.0.0.jar           # :8201
java -jar services/insight-service/target/insight-service-1.0.0.jar   # :8203
java -jar services/edge-gateway/target/edge-gateway-1.0.0.jar         # :8080
cd frontend && npm install && npm start                               # :4204; proxy.conf.json sends /api and /v1 to :8084 (Docker); use :8080 for a local gateway
```

### Kubernetes

```bash
kubectl apply -k k8s/          # CPU-based autoscaling
kubectl apply -k k8s/keda      # with KEDA: insight-service scales on Kafka consumer lag
```

Probes, requests and limits, HPAs (the proxy scales out fast and in slowly), PodDisruptionBudgets, topology spread,
non-root read-only containers with all capabilities dropped, NetworkPolicies (only the gateway and the proxy can reach the
control plane; only the gateway can reach insight), and two Ingresses: `/v1` straight to the proxy with buffering off for
streaming, everything else through the gateway. Replace the bundled Postgres, Redis and Kafka with managed services in
production.

## How to test it

| What | Command | Result on the last run |
|---|---|---|
| Unit + integration (Testcontainers: Postgres, Kafka, Redis 8) | `./mvnw verify` | 94 tests pass |
| Detection accuracy report | `./mvnw -pl detection-core test` → `detection-core/target/detection-eval.md` | 1.00 precision/recall, 0 mistakes |
| Frontend unit tests | `cd frontend && npx ng test --watch=false` | 7 pass |
| Extension unit tests | `cd extension && npm test` | 8 pass |
| End-to-end smoke test | `./scripts/smoke-test.sh` | 42 checks pass |
| Official OpenAI Python SDK, unchanged | `./scripts/compat-test.sh` | 11 checks pass |
| Extension in real Chromium (Playwright) | `./scripts/extension-e2e.sh` | 9 checks pass |
| Chaos: Kafka, Redis, control plane, insight, provider down | `./scripts/chaos-test.sh` | 13 checks pass |
| Load (k6) | `./scripts/load-test.sh` (`SCAN_RATE`, `CHAT_RATE` to change rates) | see Measured results |
| Kubernetes manifests | `kustomize build k8s \| kubeconform -strict` | 34 resources valid |

[docs/TEST-PLAN.md](docs/TEST-PLAN.md) is the plan; [docs/TESTING.md](docs/TESTING.md) records what was run, what was
found and fixed, and a click-by-click manual script.

## API

| Method | Path | Purpose |
|---|---|---|
| `POST` | `/v1/chat/completions` | OpenAI-compatible chat (stream or not). Headers back: `x-veil-action`, `x-veil-redactions`, `x-veil-cache`, `x-veil-request-id` |
| `GET` | `/v1/models` | Models this key can use |
| `POST` | `/v1/scan` | Find and redact (`{text}` → findings with offsets, decision, `redactedText`) |
| `POST` | `/v1/extension/scan` · `/v1/extension/events` · `GET /v1/extension/config` | Browser extension (extension keys only) |
| `POST` | `/api/auth/login` · `GET /api/me` | Console sign-in |
| `GET/POST/PUT` | `/api/keys` · `/api/keys/{id}/revoke` · `/api/keys/{id}/limits` · `/api/teams` | Virtual keys, teams, budgets |
| `GET/PUT` | `/api/policy` · `/api/policy/versions` · `/versions/{v}/rollback` · `POST /api/policy/copilot` · `GET /api/policy/catalogue` | Versioned policies and the copilot |
| `GET/PUT` | `/api/providers/{id}` · `/api/routes/{model}` · `/api/dictionary` · `/api/topics` | Providers, routes, company terms and topics |
| `POST` | `/api/playground/scan` · `/api/playground/chat` | Console playground and policy dry run |
| `GET/PUT` | `/api/proxy/status` · `/api/proxy/faults/{provider}` | Proxy instance state, fault injection |
| `GET` | `/api/insight/overview` · `/requests` · `/requests/{id}` · `/shadow-ai` · `/compliance` · `/export.csv` · `/live` (SSE) | Dashboards, audit log, reports |
| `GET/PATCH/POST` | `/api/insight/incidents` · `/{id}` · `/{id}/explain` | Incidents and the AI explainer |
| `POST` | `/api/simulator/start` · `/stop` | Simulated traffic |

## Project layout

```
detection-core/     Detectors, checksums, Aho–Corasick dictionary, semantic detector, redaction vault,
                    streaming rehydrator, policy engine. Plain Java, no framework. Labelled eval set in src/test/resources/eval
contracts/          Kafka event and config-snapshot records shared by the services
services/
  guard-proxy/      The data plane: OpenAI-compatible API, Scan API, extension API, providers, semantic cache, embeddings
  control-plane/    Orgs, users, keys, versioned policies, providers, routes, outbox, policy copilot, demo seed, simulator
  insight-service/  Audit consumer, day partitions, rollups, incidents and explainer, live feed, reports
  edge-gateway/     Spring Cloud Gateway for the console API
clients/java/       Java client for the Scan API
frontend/           Angular 22 console and landing page (Tailwind v4, hand-built SVG charts), nginx in production
extension/          Chrome/Edge MV3 extension, unit tests, Playwright end-to-end test
k8s/                Kustomize base and KEDA overlay
scripts/            Smoke, SDK compatibility, chaos, load and extension end-to-end tests
docs/               Research, plan, test plan, how it works, testing record
```

## For designers

- **Feel:** calm, precise, security-grade. Warm paper background, ink text, one confident indigo for the brand and primary
  actions. The landing page is dark ("night") to set the product apart; the console is light for long working sessions.
- **Palette:** paper `#f6f6f3`, ink `#0d1117`, brand indigo `#4f46e5`. Four reserved decision colours, always paired with a
  text label: protected/redacted teal `#0e9384`, flagged amber `#c27c0e`, blocked red `#d1373d`, kept-on-prem blue `#2563eb`.
  The chart colours were checked for colour-vision deficiency on the light surface. Tokens: `frontend/src/styles.css` (`@theme`).
- **Type:** Geist for UI and headings (tight tracking on big sizes), Geist Mono for keys, ids, placeholders and code;
  tabular figures for every number.
- **The core visual idea:** sensitive values are marked by what will happen to them (colour + dotted underline + tooltip),
  and placeholders like `[EMAIL_1]` are drawn as small dark tokens, so "what the model saw" reads at a glance.
- **Motion:** short and meaningful: the hero animation plays the real flow (type → detect → redact → answer → restore),
  the live feed fades items in, sections fade up once. Everything respects "reduce motion".
- **Components:** `frontend/src/app/ui/` (badges, stat tiles, area chart with crosshair tooltip, bar lists, highlighter,
  toasts, in-app confirm dialog, page header, range picker). Screens are in `frontend/src/app/pages/`.
- **Open design questions:** onboarding for a new organisation, an in-browser badge for "protected by Veil" on AI sites,
  dark mode for the console, and a mobile view of incidents for on-call triage.

## Honest limitations

- The detection eval set is **synthetic** (template prompts with planted values and decoys), so its near-perfect scores
  overstate real-world accuracy, especially for person names. A production deployment should measure on its own traffic.
- Names come from a gazetteer plus context, not a neural NER model: unusual names without context words can be missed.
- The semantic detector is the expensive part (~4–6 ms per passage on a fast CPU). One 16-thread node sustains roughly
  100–140 req/s with it on; beyond that the proxy sheds load per the fail mode, and the HPA should add replicas.
- The browser extension works on the markup of today's AI sites; a site redesign can need a selector update. It cannot
  stop someone from typing data into a phone.
- LLM providers are simulated by default. The OpenAI-compatible and Anthropic clients are implemented, but this repository's
  automated tests run against stubs.
- A single Kafka broker, one Postgres and one Redis keep the demo light; use replicated managed services in production.
