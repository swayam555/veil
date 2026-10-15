#!/usr/bin/env bash
# Loads the browser extension into real Chromium (official Playwright image) and drives the demo chat page
# against a running stack.   ./scripts/extension-e2e.sh [base-url]   (default http://localhost:3004)
set -euo pipefail
cd "$(dirname "$0")/.."
URL=${1:-http://localhost:3004}
docker run --rm --network host --ipc=host -e VEIL_URL="$URL" -v "$PWD/extension:/extension:ro" \
  mcr.microsoft.com/playwright:v1.64.1-noble sh -c \
  "mkdir -p /work && cd /work && cp /extension/e2e/extension.e2e.mjs . && npm init -y >/dev/null && npm install --silent --no-audit --no-fund playwright@1.64.1 && node extension.e2e.mjs"
