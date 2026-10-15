# Veil build progress (handover file)

Source of truth for any session continuing this work. Plan: docs/PLAN.md. Tests: docs/TEST-PLAN.md.
Ports: UI 3004, gateway 8084, guard-proxy 8085 (direct). Stop other stacks before `docker compose up`.

- [x] Research, plan, architecture, test plan (docs/)
- [x] detection-core + eval dataset (58 tests; eval report in detection-core/target/detection-eval.md)
- [x] contracts
- [x] control-plane (ControlPlaneIT: 7 tests green)
- [x] guard-proxy (ProxyIT 12 + ProviderRouterTest 3)
- [x] insight-service (InsightIT 6)
- [x] edge-gateway + docker compose + smoke test (42 checks green)
- [x] Angular frontend (all screens built; browser-checked overview, playground, activity, incidents, policies, keys, providers, compliance)
- [x] Chrome extension + demo chat page (8 node tests; Playwright e2e 9/9 in real Chromium)
- [x] k8s manifests: k8s/base + k8s/keda overlay; kustomize build + kubeconform 34/34 valid
- [x] load (scripts/load-test.sh), compat (11/11), chaos (13/13) tests
- [x] Browser verification: Chrome (interactive), Playwright screenshots of every page at 390/1440 px (0 overflow, 0 console errors), auditor role
- [x] README, HOW-IT-WORKS, TESTING, designer notes, CI workflow
- [x] Memory updated. Portfolio site NOT touched (not requested).

**VEIL IS DONE (2026-10-15).** Fresh `docker compose down -v && up --build`: smoke 42, SDK 11, extension e2e 9, chaos 13 all pass; ./mvnw verify 94 tests.

## Notes / decisions log
- Testcontainers Kafka: use apache/kafka:3.8.1 (3.9 rejects 0.0.0.0 advertised listener on Docker Desktop); pom sets TESTCONTAINERS_HOST_OVERRIDE=localhost.
- Another Claude session (projects-85) is testing fraudshield/swiftkart/gigpay; coordinate Docker use with it via SendMessage before stopping their stacks.

## NEXT PROJECT (user request, 2026-10-15)
After Veil is completely done (all items above checked), start ANOTHER new project in ~/Desktop/projects: same brief as Veil
(real-life problem, free to use, plugin/service/APK/web, Java + Angular + Docker + Redis + Kafka + Kubernetes, big,
scalable, fully tested on localhost, docs) but NOT an AI-centric product: "something new and fresh and very useful".
Research ideas from the latest Reddit posts (r/SaaS, r/startups, r/smallbusiness, r/Entrepreneur, r/india etc.) first.

## Measured results (2026-10-15, laptop, Docker Desktop VM; for README/TESTING)
- detection-core eval: 510 prompts, every pattern detector precision/recall >= 0.98 (one card/bank-account confusion fixed), ~0.4 ms/prompt.
- Smoke test 42/42; OpenAI Python SDK compat 11/11.
- Embedding on host: FP32 ~335-427/s batched (16 threads), 4-6 ms single; INT8 524/s but min cosine 0.957 vs FP32 -> kept FP32, INT8 optional via VEIL_EMBEDDING_MODEL.
- k6 (unique prompts, Cache-Control: no-cache): 60 rps -> scan p95 25.8 ms, 0 errors; 90 rps -> scan p95 24 ms; 120 rps -> scan p95 45 ms, 0 errors.
- Overload 150 rps: before shedding 41-46% failed via 1.5 s timeouts; with in-flight shedding: fail-closed 84% served + 16% instant 503 Retry-After; fail-open 0% errors (~875 requests pattern-only).
- Failover: veil-echo outage -> veil-echo-eu serves; breaker opens after 5 failures (ProviderRouterTest).

## Next project decision (research 2026-10-15)
Reddit itself was not reachable (WebFetch and Chrome both blocked reddit.com); used news + secondary sources instead.
Chosen: an open-source **fair virtual waiting room** for flash crowds (ticket drops, Tatkal, exam/admission portals,
limited sales). Evidence: BookMyShow Coldplay sale, 13M users, CEO: ~10M bot instances, linear queue crashed site 9.5 min
(Business Today 2025-01-29, Pollstar, Outlook Money); IRCTC: >half of booking attempts bots, 3.02 cr IDs blocked, Aadhaar
OTP mandate for Tatkal (July 2025) criticised as not fixing peak-hour crashes (SFLC); Queue-it is quote-only, Queue-Fair free
tier = 100 users, no credible open-source alternative. Non-AI. Plan: randomized pre-queue lottery, Redis sorted sets, Kafka
events, signed admission tokens, proof-of-work bot challenge (no CAPTCHA), JS snippet + Java servlet/Spring filter +
nginx auth_request plugin, Angular operator console + queue page, Docker, k8s. Folder: ~/Desktop/projects/foyer (name TBD).
