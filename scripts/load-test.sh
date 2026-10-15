#!/usr/bin/env bash
# Runs the k6 load test against a running stack (stop the traffic simulator first for clean numbers).
#   ./scripts/load-test.sh [proxy-url]     (default http://localhost:8085, the proxy's own port)
set -euo pipefail
cd "$(dirname "$0")"
URL=${1:-http://localhost:8085}
# The demo support key is rate-limited to 600/min; the test creates its own unlimited key through the console API.
TOKEN=$(curl -s http://localhost:3004/api/auth/login -H 'Content-Type: application/json' -d '{"email":"admin@acme.test","password":"veil-admin"}' | jq -r .accessToken)
KEY=$(curl -s http://localhost:3004/api/keys -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"name":"k6 load test","teamId":"engineering","kind":"API","rpmLimit":0,"dailyBudgetMicros":0}' | jq -r .secret)
KID=$(curl -s http://localhost:3004/api/keys -H "Authorization: Bearer $TOKEN" | jq -r '[.[] | select(.name=="k6 load test" and .revokedAt==null)][0].id')
docker run --rm --network host -e VEIL_URL="$URL" -e VEIL_KEY="$KEY" -e SCAN_RATE="${SCAN_RATE:-100}" -e CHAT_RATE="${CHAT_RATE:-40}" -v "$PWD/load:/load:ro" grafana/k6 run --quiet /load/k6-proxy.js
curl -s -X POST http://localhost:3004/api/keys/$KID/revoke -H "Authorization: Bearer $TOKEN" >/dev/null
