// Load test for the proxy hot path.  ./scripts/load-test.sh   (uses the grafana/k6 Docker image)
//
// Two scenarios run together for 60 s:
//   scan: the Scan API at a constant 100 requests/s - pure Veil cost (auth, limits, detection incl. embeddings, audit)
//   chat: chat completions at 40 requests/s through the simulated provider (adds 250-600 ms of "model" time)
// Every prompt is unique (random names, numbers and subjects), so the embedding and semantic caches don't flatter the result.
import http from 'k6/http';
import { check } from 'k6';
import { Trend } from 'k6/metrics';

const BASE = __ENV.VEIL_URL || 'http://localhost:8085';
const KEY = __ENV.VEIL_KEY || 'vk_live_demo_support_7Hq2mX9pLw4nR8sT';
const overhead = new Trend('veil_overhead_ms', true);

export const options = {
  scenarios: {
    scan: { executor: 'constant-arrival-rate', rate: Number(__ENV.SCAN_RATE || 100), timeUnit: '1s', duration: '60s', preAllocatedVUs: 50, maxVUs: 200, exec: 'scan' },
    chat: { executor: 'constant-arrival-rate', rate: Number(__ENV.CHAT_RATE || 40), timeUnit: '1s', duration: '60s', preAllocatedVUs: 60, maxVUs: 300, exec: 'chat' },
  },
  thresholds: {
    'http_req_failed': ['rate<0.01'],
    'http_req_duration{scenario:scan}': ['p(95)<150'],
  },
};

const FIRST = ['Priya', 'Rahul', 'Ananya', 'Vikram', 'Sneha', 'Arjun', 'Kavita', 'Rohan', 'Meera', 'Sanjay', 'Michael', 'Sarah'];
const LAST = ['Sharma', 'Verma', 'Iyer', 'Reddy', 'Nair', 'Gupta', 'Patel', 'Mehta', 'Singh', 'Johnson'];
const SUBJECTS = ['refund', 'card replacement', 'KYC update', 'loan statement', 'chargeback', 'UPI failure', 'address change', 'fixed deposit'];
const pick = a => a[Math.floor(Math.random() * a.length)];
const digits = n => Array.from({ length: n }, () => Math.floor(Math.random() * 10)).join('');

function prompt() {
  const name = `${pick(FIRST)} ${pick(LAST)}`;
  const r = Math.random();
  if (r < 0.5) return `Write a short reply about the ${pick(SUBJECTS)} for ticket ${digits(6)}; keep it under 80 words and mention the next step.`;
  if (r < 0.8) return `Customer ${name} (${name.toLowerCase().replace(' ', '.')}${digits(2)}@gmail.com, +91 9${digits(4)} ${digits(5)}) asked about a ${pick(SUBJECTS)}. Draft a polite reply.`;
  return `Summarise this complaint from ${name}: order ${digits(7)} arrived damaged on ${1 + Math.floor(Math.random() * 28)} October and the ${pick(SUBJECTS)} is pending.`;
}

const headers = { 'Authorization': `Bearer ${KEY}`, 'Content-Type': 'application/json', 'Cache-Control': 'no-cache' };

export function scan() {
  const res = http.post(`${BASE}/v1/scan`, JSON.stringify({ text: prompt() }), { headers, tags: { name: 'scan' } });
  check(res, { 'scan 200': r => r.status === 200 });
}

export function chat() {
  const t0 = Date.now();
  const res = http.post(`${BASE}/v1/chat/completions`, JSON.stringify({
    model: 'gpt-4o-mini', max_tokens: 200, messages: [{ role: 'user', content: prompt() }],
  }), { headers, tags: { name: 'chat' } });
  check(res, { 'chat 200': r => r.status === 200 });
}
