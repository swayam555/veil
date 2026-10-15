#!/usr/bin/env bash
# Runs the official OpenAI Python SDK against a running Veil stack, in a throwaway container.
#   ./scripts/compat-test.sh [base-url]     (default http://localhost:3004)
set -euo pipefail
cd "$(dirname "$0")"
URL=${1:-http://localhost:3004}
docker run --rm --network host -e VEIL_URL="$URL" -v "$PWD/compat:/compat:ro" python:3.12-slim \
  sh -c "pip install -q --disable-pip-version-check --root-user-action=ignore openai==1.* && python -I /compat/compat_test.py"
