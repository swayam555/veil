#!/usr/bin/env bash
# Chaos test: takes dependencies down one at a time while traffic flows, and checks Veil keeps serving and loses
# nothing. Needs the Docker Compose stack, curl and jq. Takes about 4 minutes; restores everything it stops.
#   ./scripts/chaos-test.sh
set -uo pipefail
cd "$(dirname "$0")/.."
B=http://localhost:3004
P=http://localhost:8085
KEY=vk_live_demo_support_7Hq2mX9pLw4nR8sT
RUN=chaos-$(date +%s)
FAIL=0
check() { if [ "$2" = "$3" ]; then echo "  ok   $1"; else echo "  FAIL $1 (expected $3, got $2)"; FAIL=1; fi; }
token() { curl -s $B/api/auth/login -H 'Content-Type: application/json' -d '{"email":"admin@acme.test","password":"veil-admin"}' | jq -r .accessToken; }
chat() { # actor prompt -> status
  curl -s -o /dev/null -w '%{http_code}' $P/v1/chat/completions -H "Authorization: Bearer $KEY" -H 'Content-Type: application/json' \
    -H 'Cache-Control: no-cache' -H "x-veil-user: $1" -d "$(jq -nc --arg p "$2" '{model:"gpt-4o-mini",messages:[{role:"user",content:$p}]}')"
}
burst() { # actor n -> number of 200s
  local ok=0
  for i in $(seq "$2"); do [ "$(chat "$1" "Write to customer Neha Gupta (neha.$i@acme.in) about request $i of $1")" = 200 ] && ok=$((ok + 1)); done
  echo $ok
}
audited() { # actor -> rows in the audit log
  curl -s "$B/api/insight/requests?q=$1&limit=200" -H "Authorization: Bearer $(token)" | jq '.items | length'
}
wait_healthy() {
  for _ in $(seq 60); do
    [ "$(docker compose ps "$1" --format '{{.Health}}')" = healthy ] && return 0; sleep 2
  done
  echo "  (warning: $1 not healthy after 2 minutes)"
}

echo "1. Kafka down: requests keep flowing, audit events are buffered, then delivered"
docker compose stop kafka >/dev/null 2>&1
check "20 requests served while Kafka is down" "$(burst $RUN-kafka 20)" 20
BUF=$(curl -s $B/api/proxy/status -H "Authorization: Bearer $(token)" | jq .auditBuffered)
check "audit events are buffered in the proxy" "$([ "${BUF:-0}" -gt 0 ] && echo yes)" yes
docker compose start kafka >/dev/null 2>&1; wait_healthy kafka
for _ in $(seq 60); do [ "$(audited $RUN-kafka)" = 20 ] && break; sleep 3; done
check "all 20 audit events arrive after Kafka recovers" "$(audited $RUN-kafka)" 20

echo "2. Redis down: known keys still work (in-memory cache), limits fail open"
chat $RUN-warm "warm the key cache" >/dev/null
docker compose stop redis >/dev/null 2>&1
check "requests served while Redis is down" "$(burst $RUN-redis 5)" 5
TK=$(token)
check "console sign-in still works" "$([ ${#TK} -gt 40 ] && echo yes)" yes
docker compose start redis >/dev/null 2>&1; wait_healthy redis

echo "3. Control plane down: the proxy keeps enforcing its last configuration"
docker compose stop control-plane >/dev/null 2>&1
check "known key served without the control plane" "$(burst $RUN-cp 5)" 5
check "secrets still blocked" "$(curl -s $P/v1/chat/completions -H "Authorization: Bearer $KEY" -H 'Content-Type: application/json' \
  -d '{"model":"gpt-4o-mini","messages":[{"role":"user","content":"key AKIAIOSFODNN7EXAMPLE fails"}]}' | jq -r .error.type)" veil_blocked
check "unknown key can't be verified: 503, not let through" "$(curl -s -o /dev/null -w '%{http_code}' $P/v1/scan -H 'Authorization: Bearer vk_live_never_seen_before_123' \
  -H 'Content-Type: application/json' -d '{"text":"hello world"}')" 503
docker compose start control-plane >/dev/null 2>&1; wait_healthy control-plane

echo "4. insight-service down: no effect on requests, nothing lost"
docker compose stop insight-service >/dev/null 2>&1
check "requests served while insight-service is down" "$(burst $RUN-insight 10)" 10
docker compose start insight-service >/dev/null 2>&1; wait_healthy insight-service
for _ in $(seq 60); do [ "$(audited $RUN-insight)" = 10 ] && break; sleep 3; done
check "events consumed after insight-service restarts" "$(audited $RUN-insight)" 10

echo "5. Provider outage: failover, then the circuit opens"
T=$(token)
curl -s -X PUT $B/api/proxy/faults/veil-echo -H "Authorization: Bearer $T" -H 'Content-Type: application/json' -d '{"errorRate":1,"extraLatencyMs":0}' >/dev/null
check "requests served by the backup provider" "$(burst $RUN-outage 24)" 24
check "primary provider's circuit is open" "$(curl -s $B/api/proxy/status -H "Authorization: Bearer $T" | jq -r '.circuitBreakers["veil-echo"]')" OPEN
curl -s -X PUT $B/api/proxy/faults/veil-echo -H "Authorization: Bearer $T" -H 'Content-Type: application/json' -d '{"errorRate":0,"extraLatencyMs":0}' >/dev/null

echo
[ $FAIL = 0 ] && echo "All chaos checks passed." || { echo "Some chaos checks FAILED."; exit 1; }
