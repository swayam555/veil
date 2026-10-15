# How Veil works

A walkthrough for engineers, from a prompt arriving to the incident an analyst resolves. File names are relative to the
repository root.

## 1. Three entry points, one pipeline

| Entry | Endpoint | Key kind | Decides with source |
|---|---|---|---|
| Apps and SDKs | `POST /v1/chat/completions` (OpenAI format, stream or not) | `vk_live_…` (API) | `api` |
| Any system | `POST /v1/scan` | API | `api` |
| Browser extension | `POST /v1/extension/scan`, `/v1/extension/events` | `vk_ext_…` (EXTENSION) | `extension` |
| Console playground | `POST /api/playground/chat`, `/scan` (JWT via the gateway) | none (the signed-in user picks a team) | `api` or `extension` |

All of them run the same detection and policy code in `services/guard-proxy`. The chat endpoint additionally redacts,
routes, rehydrates and accounts for cost (`chat/ChatPipeline.java`).

## 2. Authentication and admission

`auth/KeyResolver.java` hashes the key (SHA-256; the control plane only ever stores the hash) and looks it up in three tiers:
an in-process map (30 s), Redis (5 min, shared by all proxies), and the control plane's `/internal/keys/{hash}` on a miss.
Unknown keys are cached negatively for 30 s so guessing keys can't hammer the control plane. Revocations arrive on the
`veil.keys` topic and evict both caches immediately. If neither Redis nor the control plane can answer, keys the proxy has
already seen keep working for up to 24 h (stale-if-error); unknown keys get a 503, never a pass.

`limits/Limits.java` then applies:

- **Rate limit:** a token bucket in a Redis Lua script (atomic across all proxy instances). A 60 rpm key gets one request a
  second sustained and bursts up to 60. Over the limit: 429 with `Retry-After`.
- **Budgets:** daily spend counters per key and per team (`INCRBY` on day-scoped keys). The request is refused once the
  budget is used up; the cost of a finished request is added after it completes.

If Redis is down, limits fail open (the AI tools stay available; data protection is unaffected).

## 3. Detection

`detection-core` is a plain Java library (no Spring), so it can be embedded anywhere and tested in milliseconds.

**Pattern detectors** (`PatternLibrary.java`): each is a regex that finds candidates, a validator, and context scoring.

| Type | Validation |
|---|---|
| Aadhaar | 12 digits not starting 0/1, **Verhoeff** check digit, not all one digit |
| Payment card | 13–19 digits, **Luhn**, a known card prefix (Visa, Mastercard, Amex, RuPay…), consistent separators |
| IBAN | **ISO 13616 mod-97** |
| GSTIN | state code, PAN inside, **base-36 check character** |
| PAN | the 4th letter must be a valid holder type (P, C, H, F, A, T…) |
| US SSN | structural rules (no 000, 666, 9xx area; no 00 group; no 0000 serial) |
| Bank account | 9–18 digits, **only** next to words like "account number" |
| Secrets | provider-specific formats (AWS, GitHub, OpenAI, Anthropic, Slack, Stripe, Google), PEM private keys, JWTs, connection-string passwords, and high-entropy values after `api_key=`/`secret:` (Shannon entropy ≥ 3.5 bits/char) |

Context words within ~50 characters raise confidence ("Aadhaar" turns 0.80 into 0.99); words that point to another kind of
number lower it ("account", "tracking", "order" make a Luhn-valid number less likely to be a card).

**Names** (`NameDetector.java`) are runs of capitalised words scored with a first-name gazetteer (Indian and Western names),
honorifics (Mr, Dr, Smt, Shri…) and context ("customer", "patient", "Dear"). Words that are also English ("Grace", "May",
"Will") need a surname or a context word.

**Company terms** (`DictionaryDetector.java`) use an **Aho–Corasick** automaton built from the organisation's dictionary:
one pass over the text finds every term, with word-boundary checks.

**Source code** (`SourceCodeDetector.java`) scores each line for code syntax and reports the densest block, with a language
guess. **Injection heuristics** catch classic phrasings ("ignore all previous instructions", "you are now DAN",
chat-template tokens) with an exact explanation.

**Semantic detection** (`SemanticDetector.java`) finds what has no pattern. Short texts are one passage; longer texts are
split into sentences (short ones merged) so one confidential sentence isn't diluted by pages of harmless text; very long
documents are sampled (at most 16 passages). Each passage is embedded and compared with ~100 labelled example sentences
(`resources/veil/semantic-exemplars.txt`) plus the organisation's own. A label wins if the average of its two closest
examples is at least 0.42 cosine **and** at least 0.15 above the closest "neutral" examples. The neutral set is what keeps
"explain SQL injection in general" from looking like "we have an SQL injection in production". Thresholds were tuned on
held-out sentences (`SemanticDetectorTest`).

**Overlaps** (`ScanEngine.resolve`) keep the most severe, then longest, then most confident span, so each character belongs
to at most one finding. Document-level findings (topic, code, injection) sit alongside.

### Embeddings, and what load taught us

`state/Embeddings.java` and `state/BatchEncoder.java` run all-MiniLM-L6-v2 directly on ONNX Runtime: tokenise with the
HuggingFace tokenizer, pad the batch, run the transformer once, mean-pool each row over its real tokens, L2-normalise.
`BatchEncoderTest` proves the vectors match the reference LangChain4j model (cosine > 0.9999) and that a text gets the
same vector whatever else is in its batch.

The first version called the library model once per text, and ONNX used every core for every inference. Under a k6 load
of 140 req/s, dozens of inferences fought over the same cores and the median embedding went from ~5 ms to 230 ms; the
detection timeout fired and 46% of requests failed. The fixes, each measured:

1. **Dynamic batching.** Callers queue texts; a few workers each take everything waiting (up to 32) and run one inference
   with a few threads. Light traffic: batch of one, same latency. Heavy traffic: bigger batches, more throughput.
2. **Load shedding.** `DetectionService` counts requests in detection. Past twice the pool size, a new request would only wait
   until it timed out, so it is decided at once by the organisation's fail mode: closed → 503 `Retry-After: 1`; open →
   pattern detectors only, marked `degraded`. At 150 req/s this turned 41% timeouts into 84% served + 16% fast 503s
   (fail-closed) or 0% errors (fail-open).
3. **INT8 quantisation (optional).** A dynamically quantised model is 1.6× faster but its vectors drift (minimum cosine 0.957
   to FP32), which would move tuned thresholds, so FP32 stays the default; set `veil.embedding-model` to use INT8.

Beyond one node's capacity, the answer is horizontal: the proxy is stateless and its HPA scales on CPU.

Detection runs on a bounded scheduler, never on Netty's event loop, with a time budget (`DETECTION_TIMEOUT`, 1.5 s).

## 4. The policy decision

`policy/PolicyEngine.java` is a pure function: `(policy, findings, team, source, model) → decision`. For each finding, the
strictest enabled rule that matches its type or category, team, channel and minimum confidence decides its action:
`ALLOW < WARN < REDACT < ROUTE_LOCAL < BLOCK`. The request's action is the strictest finding's action. A model allowlist and
an output-token cap are checked too. Document-level findings can't be replaced by a placeholder, so `REDACT` on them
becomes `WARN`. Every decision carries human-readable reasons ("Redacted: email address — rule 'Redact contact details'").

Policies are immutable versions in the control plane (`policy_versions`); publishing writes a new version and pushes a
config snapshot; rollback publishes an old version as a new one. The **policy copilot** (`PolicyCopilot.java`) turns
plain English into rules: with an LLM it asks for JSON in a fixed schema and validates every field against the real enums
and team ids (anything invalid is dropped with a note); without one, `RuleParser.java` handles common phrasings by clause
("Block API keys and passwords everywhere; redact Aadhaar and PAN for the Support team; keep confidential topics on-prem for
Finance"). Drafts are never published automatically.

## 5. Redaction and rehydration

`RedactionVault.java` replaces each finding marked `REDACT` with a placeholder. The vault is per request and spans every
message, so `priya@acme.in` is `[EMAIL_1]` in the system prompt and in the third user message alike (normalised: e-mails
case-insensitive, numbers by digits). The mapping lives only in memory and dies with the request.

Answers are rehydrated before they reach the client. For streams, `StreamingRehydrator.java` buffers only a tail that could
still become a placeholder (`[` followed by capitals, underscores and digits, up to 32 characters) and releases everything
else immediately. `RedactionTest` checks every split point and random chunkings.

`ROUTE_LOCAL` sends the request, unredacted where the policy allows, to an on-premises provider instead (the first local
target of the requested route, or the first route whose first target is local).

## 6. Semantic cache

`cache/SemanticCache.java` uses Redis 8's built-in vector search (`FT.CREATE … VECTOR HNSW … COSINE`) on a dedicated
RESP2 connection. The key is the embedding of the **redacted** last user message, filtered by organisation, model and a
hash of the rest of the conversation. A hit within cosine distance 0.06 returns the cached (redacted) answer, which is then
rehydrated with the current request's vault. It's skipped for tools, multiple choices and on-prem routing, and when the
client sends `Cache-Control: no-cache` (the answer is still stored).

## 7. Routing, failover and output scanning

`llm/ProviderRouter.java` maps a model name to its route's ordered targets. Each attempt has a timeout and a Resilience4j
circuit breaker per (organisation, provider): 20-call window, opens at 50% failures after 5 calls, half-opens after 15 s.
Network errors, timeouts, 5xx, 429 and bad provider credentials move to the next target; other 4xx go back to the client
as an OpenAI-style error. Streams fail over only before the first chunk. To return a real 502 when every provider fails,
`ChatPipeline.stream` waits for the first chunk before committing the response.

Providers: `EchoProvider` (free, simulated, with fault injection), `OpenAiCompatibleProvider` (passes the request through
with only the model name changed) and `AnthropicProvider` (translates to and from the Messages API, including streamed
`content_block_delta` events).

The answer is scanned with the pattern detectors; secrets or personal data the model produced on its own are masked as
`[REDACTED:TYPE]` when the policy would block or redact them on input.

## 8. Audit

After responding, the proxy publishes one `AuditEvent` (`contracts/…/AuditEvent.java`) to `veil.audit`, keyed by
organisation, on a worker thread (a Kafka metadata wait must never block Netty). It contains the decision, reasons, timings,
tokens, cost, the redacted prompt (if the policy keeps it) and, for every finding, type, severity, offsets, action and a
**salted HMAC fingerprint**: never the value. If Kafka is down, events wait in a bounded buffer (20,000) and are retried
every 2 s; the chaos test stops Kafka mid-burst and checks every event arrives afterwards.

`insight-service` consumes in batches of up to 500 (`ingest/AuditIngestor.java`). The batch is stored in one transaction;
if it fails, events are retried one by one and an unstorable one goes to `veil.audit.dlq`. Inserts are idempotent on
`(id, at)`; rollups (`usage_minute`, `finding_minute`) and incidents are only touched for new rows, so redelivery can't
double-count. `requests` is partitioned by day (`ingest/Partitions.java` keeps partitions three days ahead and drops
expired ones). A second, per-instance consumer feeds the live Server-Sent Events stream to every console that is open.

## 9. Incidents

`incidents/IncidentService.java` opens an incident for anything blocked, any critical finding, prompt injection and masked
model output. The same person (or key), the same data types, the same action and site within 30 minutes fold into one
incident with a count and a set of distinct fingerprints ("53 occurrences, 53 distinct keys" reads very differently from "53
occurrences, 1 key"). `IncidentExplainer.java` writes "What happened / Why it matters / What to do", with an LLM if one is
configured (it only sees types, the decision and the redacted prompt) or from a template that maps types to regulations.

## 10. Configuration flow

Every change in the control plane (policy, key, team budget, provider, route, dictionary, topic) runs in a transaction that
also writes an outbox row with the organisation's full snapshot (`publish/ConfigPublisher.java`), and an admin-audit row
(who changed what). `publish/Outbox.java` relays rows to Kafka every 150 ms with `FOR UPDATE SKIP LOCKED`, so several
control-plane replicas can run. `veil.config` is compacted: the latest snapshot per organisation is kept forever, and a new
proxy rebuilds all state by reading it from the beginning. Snapshots carry a version; proxies ignore older ones. Provider
API keys travel AES-256-GCM-encrypted with a master key only the control plane and the proxies hold.

## 11. The browser extension

`extension/content.js` runs on AI chat sites. It intercepts `paste` (capture phase) and send (Enter in the composer, or
the send button), asks the background worker to call `/v1/extension/scan`, and if the policy says so shows an overlay in a
closed-off Shadow DOM: **Paste/Send redacted** (recommended), **Paste/Send anyway** (when allowed, and logged) or
**Cancel**. Text is inserted the way a real paste would be, so React and ProseMirror editors notice. Texts the user has
already decided about aren't checked again. If Veil is unreachable the extension never blocks work. Settings can be pushed
by IT through Chrome enterprise policy (`chrome.storage.managed`). `lib/veil-core.js` holds the pure decision logic and is
unit-tested in Node; `extension/e2e` drives the real extension in Chromium.

## 12. The console

Angular 22 with standalone components, signals and zoneless change detection. Every screen is lazy-loaded; the initial
bundle is ~88 kB compressed. Charts are hand-built SVG (crosshair tooltip, legend, one axis), with colours validated for
colour-vision deficiency. The JWT goes in an interceptor; a 401 signs the user out. The live feed is one shared
`EventSource` (it can't send headers, so the gateway also accepts `access_token` in the query for that route).
