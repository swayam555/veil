"""Runs the official OpenAI Python SDK against Veil, unchanged except for base_url and api_key.

Usage (from the repo root, stack running):  ./scripts/compat-test.sh
"""
import os
import sys

from openai import OpenAI, PermissionDeniedError, AuthenticationError, NotFoundError

BASE = os.environ.get("VEIL_URL", "http://localhost:3004") + "/v1"
KEY = os.environ.get("VEIL_KEY", "vk_live_demo_support_7Hq2mX9pLw4nR8sT")
failures = 0


def check(name, ok, detail=""):
    global failures
    print(("  ok   " if ok else "  FAIL ") + name + ("" if ok else f" ({detail})"))
    if not ok:
        failures += 1


client = OpenAI(base_url=BASE, api_key=KEY)

models = [m.id for m in client.models.list()]
check("models.list()", "gpt-4o-mini" in models, models)

r = client.chat.completions.create(
    model="gpt-4o-mini",
    messages=[{"role": "system", "content": "You are a support assistant."},
              {"role": "user", "content": "Write a reply to customer Ananya Iyer (ananya.iyer@outlook.com) about a delayed refund"}],
    max_tokens=300,
    extra_headers={"Cache-Control": "no-cache"},
)
text = r.choices[0].message.content
check("chat.completions.create() returns a typed object", r.object == "chat.completion" and r.model == "gpt-4o-mini", r.model)
check("values restored in the answer", "Ananya Iyer" in text and "ananya.iyer@outlook.com" in text, text[:120])
check("usage reported", r.usage is not None and r.usage.prompt_tokens > 0, r.usage)

raw = client.chat.completions.with_raw_response.create(
    model="gpt-4o-mini", messages=[{"role": "user", "content": "Email meera.nair@acme.in about invoice 42"}],
    extra_headers={"Cache-Control": "no-cache"})
check("Veil headers visible to the SDK", raw.headers.get("x-veil-action") == "REDACT", raw.headers.get("x-veil-action"))

chunks = []
stream = client.chat.completions.create(
    model="claude-sonnet-4-5", stream=True, stream_options={"include_usage": True},
    messages=[{"role": "user", "content": "Draft a note to Rohan Gupta, phone +91 98111 22334, about his card replacement"}],
    extra_headers={"Cache-Control": "no-cache"})
usage = None
for c in stream:
    if c.choices and c.choices[0].delta.content:
        chunks.append(c.choices[0].delta.content)
    if c.usage:
        usage = c.usage
streamed = "".join(chunks)
check("streaming works with the SDK", len(chunks) > 3, len(chunks))
check("streamed values restored", "Rohan Gupta" in streamed and "[PERSON_" not in streamed, streamed[:120])
check("stream_options.include_usage honoured", usage is not None and usage.completion_tokens > 0, usage)

try:
    client.chat.completions.create(model="gpt-4o-mini",
                                   messages=[{"role": "user", "content": "my key is AKIAIOSFODNN7EXAMPLE, why denied?"}])
    check("blocked request raises PermissionDeniedError", False, "no exception")
except PermissionDeniedError as e:
    check("blocked request raises PermissionDeniedError", e.status_code == 403 and "veil_blocked" in str(e.body), e.body)

try:
    OpenAI(base_url=BASE, api_key="vk_live_wrong").chat.completions.create(
        model="gpt-4o-mini", messages=[{"role": "user", "content": "hi"}])
    check("bad key raises AuthenticationError", False, "no exception")
except AuthenticationError as e:
    check("bad key raises AuthenticationError", e.status_code == 401, e)

try:
    client.chat.completions.create(model="does-not-exist", messages=[{"role": "user", "content": "hi"}])
    check("unknown model raises NotFoundError", False, "no exception")
except NotFoundError:
    check("unknown model raises NotFoundError", True)

print()
print("All SDK compatibility checks passed." if failures == 0 else f"{failures} SDK compatibility checks FAILED.")
sys.exit(1 if failures else 0)
