import { Component, OnDestroy, OnInit, computed, inject, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { AuthService } from '../core/auth.service';
import { Logo } from '../ui/logo';

interface DemoToken { text: string; kind?: 'PERSON' | 'EMAIL' | 'AADHAAR' | 'CARD'; placeholder?: string; }

/** The prompt used in the hero animation: plain text interleaved with sensitive values. */
const PROMPT: DemoToken[] = [
  { text: 'Reply to customer ' },
  { text: 'Priya Sharma', kind: 'PERSON', placeholder: '[PERSON_1]' },
  { text: ' (' },
  { text: 'priya.sharma@gmail.com', kind: 'EMAIL', placeholder: '[EMAIL_1]' },
  { text: '). Her Aadhaar ' },
  { text: '2345 6789 0124', kind: 'AADHAAR', placeholder: '[AADHAAR_1]' },
  { text: ' failed KYC and card ' },
  { text: '4111 1111 1111 1111', kind: 'CARD', placeholder: '[CREDIT_CARD_1]' },
  { text: ' was charged twice.' },
];

const STEPS = ['Typing', 'Detecting', 'Redacting', 'Model answers', 'Restoring'];

@Component({
  selector: 'veil-landing',
  imports: [RouterLink, Logo],
  template: `
    <div class="bg-night text-white">
      <header class="max-w-6xl mx-auto px-6 h-16 flex items-center justify-between">
        <veil-logo [dark]="true" [size]="28" />
        <nav class="hidden md:flex items-center gap-7 text-sm text-white/60">
          <a href="#problem" class="hover:text-white">Problem</a>
          <a href="#how" class="hover:text-white">How it works</a>
          <a href="#adopt" class="hover:text-white">Adopt</a>
          <a href="#architecture" class="hover:text-white">Architecture</a>
        </nav>
        <a [routerLink]="auth.signedIn() ? '/console' : '/login'" class="btn bg-white text-ink hover:bg-white/90">{{ auth.signedIn() ? 'Open console' : 'Sign in' }}</a>
      </header>

      <section class="max-w-6xl mx-auto px-6 pt-14 pb-24 grid lg:grid-cols-[1fr_1.05fr] gap-14 items-center">
        <div class="fade-up">
          <p class="inline-flex items-center gap-2 text-xs text-white/60 border border-white/15 rounded-full px-3 py-1 mb-6">
            <span class="size-1.5 rounded-full bg-protect"></span>Open source · self-hosted · free</p>
          <h1 class="text-[44px] sm:text-[56px] leading-[1.02] font-semibold tracking-[-0.035em]">Use AI.<br/>Keep your data.</h1>
          <p class="text-white/65 text-lg leading-relaxed mt-6 max-w-xl">Veil is an AI data firewall. It sits between your people, your apps and every
            LLM, and makes sure customer data, secrets and confidential work never leave, while the answers still come back complete.</p>
          <div class="flex flex-wrap gap-3 mt-8">
            <a routerLink="/login" class="btn btn-primary h-11 px-5 text-[15px]">Try the live console</a>
            <a href="#adopt" class="btn h-11 px-5 text-[15px] border-white/20 text-white hover:bg-white/10">Ways to adopt</a>
          </div>
          <p class="text-xs text-white/40 mt-4">Demo sign-in: admin&#64;acme.test / veil-admin</p>
        </div>

        <div class="rounded-2xl border border-night-line bg-night-2 shadow-[0_30px_80px_-30px_rgba(79,70,229,.45)] overflow-hidden fade-up" aria-label="Animated demo of Veil redacting a prompt">
          <div class="flex items-center gap-1.5 px-4 h-10 border-b border-night-line">
            <span class="size-2.5 rounded-full bg-white/15"></span><span class="size-2.5 rounded-full bg-white/15"></span><span class="size-2.5 rounded-full bg-white/15"></span>
            <div class="ml-3 flex gap-1 text-[11px]">
              @for (s of steps; track s; let i = $index) {
                <span class="px-2 py-0.5 rounded-full transition-colors" [class]="i === phase() ? 'bg-brand text-white' : i < phase() ? 'text-white/50' : 'text-white/25'">{{ s }}</span>
              }
            </div>
          </div>
          <div class="p-5 flex flex-col gap-4 text-[14px] leading-relaxed min-h-[360px]">
            <div>
              <div class="text-[11px] uppercase tracking-wider text-white/35 mb-1.5">Support agent writes</div>
              <div class="rounded-xl bg-white/[.04] border border-night-line p-3.5 font-mono text-[13px]">
                @for (t of shown(); track $index) {
                  @if (t.kind && phase() >= 1) {
                    <span class="rounded px-0.5 transition-colors duration-500" [class]="phase() >= 2 ? 'bg-protect/20 text-[#7be0d2]' : 'bg-warn/25 text-[#ffd48a]'">{{ phase() >= 2 ? t.placeholder : t.text }}</span>
                  } @else { <span>{{ t.text }}</span> }
                }@if (phase() === 0) {<span class="inline-block w-2 h-4 bg-white/70 align-middle animate-pulse"></span>}
              </div>
            </div>
            @if (phase() >= 1) {
              <div class="flex flex-wrap gap-1.5 text-[11px] fade-up">
                <span class="rounded-full border border-white/15 px-2 py-0.5">Person name · 92%</span>
                <span class="rounded-full border border-white/15 px-2 py-0.5">Email · 99%</span>
                <span class="rounded-full border border-white/15 px-2 py-0.5">Aadhaar · Verhoeff ✓</span>
                <span class="rounded-full border border-white/15 px-2 py-0.5">Card · Luhn ✓</span>
                <span class="rounded-full bg-protect/20 text-[#7be0d2] px-2 py-0.5">Decision: redact</span>
              </div>
            }
            @if (phase() >= 3) {
              <div class="fade-up">
                <div class="text-[11px] uppercase tracking-wider text-white/35 mb-1.5">{{ phase() >= 4 ? 'Agent receives' : 'Model replies (it only saw placeholders)' }}</div>
                <div class="rounded-xl bg-white/[.04] border border-night-line p-3.5 text-[13.5px] text-white/85">
                  Dear <span [class]="phase() >= 4 ? 'text-white font-medium' : 'font-mono text-[#7be0d2]'">{{ phase() >= 4 ? 'Priya Sharma' : '[PERSON_1]' }}</span>, we're sorry about the
                  duplicate charge on card <span [class]="phase() >= 4 ? 'text-white font-medium' : 'font-mono text-[#7be0d2]'">{{ phase() >= 4 ? '4111 1111 1111 1111' : '[CREDIT_CARD_1]' }}</span>.
                  The refund is on its way, and we'll confirm at <span [class]="phase() >= 4 ? 'text-white font-medium' : 'font-mono text-[#7be0d2]'">{{ phase() >= 4 ? 'priya.sharma@gmail.com' : '[EMAIL_1]' }}</span>.
                </div>
              </div>
            }
          </div>
        </div>
      </section>
    </div>

    <section id="problem" class="max-w-6xl mx-auto px-6 py-20">
      <p class="eyebrow mb-3">The problem</p>
      <h2 class="text-3xl sm:text-4xl font-semibold tracking-tight max-w-3xl">Everyone is pasting company data into AI. Banning it doesn't work.</h2>
      <p class="text-ink-2 mt-4 max-w-3xl leading-relaxed">Employees paste customer records, contracts, salary sheets, API keys and source code into ChatGPT,
        Claude and Gemini to get work done faster, often from personal accounts the company can't see. Apps built on LLMs send whole tickets,
        phone numbers and all, to models hosted in another country.</p>
      <div class="grid sm:grid-cols-2 lg:grid-cols-4 gap-4 mt-10">
        @for (s of stats; track s.n) {
          <div class="card p-5"><div class="text-3xl font-semibold tracking-tight tabular">{{ s.n }}</div>
            <p class="text-sm text-ink-2 mt-2 leading-snug">{{ s.text }}</p><p class="text-[11px] text-muted mt-3">{{ s.src }}</p></div>
        }
      </div>
    </section>

    <section id="how" class="bg-white border-y border-line">
      <div class="max-w-6xl mx-auto px-6 py-20">
        <p class="eyebrow mb-3">How it works</p>
        <h2 class="text-3xl sm:text-4xl font-semibold tracking-tight max-w-3xl">Milliseconds on the hot path. Nothing sensitive ever stored.</h2>
        <ol class="grid sm:grid-cols-2 lg:grid-cols-3 gap-x-10 gap-y-8 mt-12">
          @for (h of how; track h.title; let i = $index) {
            <li class="flex gap-4"><span class="font-mono text-sm text-brand pt-0.5">{{ (i + 1).toString().padStart(2, '0') }}</span>
              <div><div class="font-semibold">{{ h.title }}</div><p class="text-sm text-ink-2 mt-1 leading-relaxed">{{ h.text }}</p></div></li>
          }
        </ol>
        <div class="mt-14">
          <div class="eyebrow mb-3">What it finds</div>
          <div class="flex flex-wrap gap-2">
            @for (d of detects; track d) { <span class="rounded-full border border-line bg-paper px-3 py-1 text-[13px]">{{ d }}</span> }
          </div>
        </div>
      </div>
    </section>

    <section id="adopt" class="max-w-6xl mx-auto px-6 py-20">
      <p class="eyebrow mb-3">Adopt it your way</p>
      <h2 class="text-3xl sm:text-4xl font-semibold tracking-tight">Three ways in. No code changes required.</h2>
      <div class="grid lg:grid-cols-3 gap-5 mt-10">
        @for (a of adopt; track a.title) {
          <div class="card p-6 flex flex-col">
            <div class="eyebrow">{{ a.for }}</div>
            <div class="text-xl font-semibold mt-2">{{ a.title }}</div>
            <p class="text-sm text-ink-2 mt-2 leading-relaxed flex-1">{{ a.text }}</p>
            <pre class="mt-5 rounded-lg bg-night text-[#e7e9ee] text-[11.5px] p-3 overflow-x-auto">{{ a.code }}</pre>
          </div>
        }
      </div>
    </section>

    <section id="architecture" class="bg-night text-white">
      <div class="max-w-6xl mx-auto px-6 py-20">
        <p class="eyebrow !text-white/45 mb-3">Architecture</p>
        <h2 class="text-3xl sm:text-4xl font-semibold tracking-tight max-w-3xl">Built like infrastructure, because it is.</h2>
        <div class="mt-12 grid lg:grid-cols-[1.2fr_1fr] gap-10 items-start">
          <div class="rounded-2xl border border-night-line p-5 font-mono text-[12px] leading-[1.7] text-white/75 overflow-x-auto">
            <pre>{{ diagram }}</pre>
          </div>
          <ul class="flex flex-col gap-5">
            @for (e of engineering; track e.title) {
              <li><div class="font-semibold">{{ e.title }}</div><p class="text-sm text-white/60 mt-1 leading-relaxed">{{ e.text }}</p></li>
            }
          </ul>
        </div>
        <div class="flex flex-wrap gap-2 mt-12 text-xs text-white/60">
          @for (t of stack; track t) { <span class="rounded-full border border-white/15 px-3 py-1">{{ t }}</span> }
        </div>
      </div>
    </section>

    <footer class="max-w-6xl mx-auto px-6 py-10 flex flex-wrap items-center justify-between gap-4 text-sm text-muted">
      <veil-logo [size]="22" />
      <span>An open-source AI data firewall. Runs on one laptop or a Kubernetes cluster.</span>
      <a routerLink="/login" class="text-brand hover:underline">Open the console →</a>
    </footer>`,
})
export class Landing implements OnInit, OnDestroy {
  auth = inject(AuthService);
  steps = STEPS;
  phase = signal(0);
  typed = signal(0);
  private timer?: ReturnType<typeof setInterval>;
  private readonly fullText = PROMPT.map(t => t.text).join('');

  shown = computed<DemoToken[]>(() => {
    if (this.phase() > 0) return PROMPT;
    let left = this.typed();
    const out: DemoToken[] = [];
    for (const t of PROMPT) {
      if (left <= 0) break;
      out.push({ text: t.text.slice(0, left) });
      left -= t.text.length;
    }
    return out;
  });

  stats = [
    { n: '40%', text: 'of AI-related data breaches will come from cross-border misuse of GenAI by 2027.', src: 'Gartner, Feb 2025' },
    { n: '80% / 60%', text: 'of organisations worry about GenAI data leakage; 60% have no strategy for it.', src: 'Mimecast, State of Human Risk 2026' },
    { n: '47%', text: 'of enterprise GenAI users use personal accounts that IT cannot see.', src: 'Netskope, 2026' },
    { n: '₹250 cr', text: 'maximum penalty per instance for failing to keep reasonable safeguards, under India\'s DPDP Rules.', src: 'DPDP Rules 2025' },
  ];
  how = [
    { title: 'Authenticate and admit', text: 'Virtual keys per team or app, cached in memory and Redis. Rate limits and daily budgets are atomic Redis scripts shared by every instance.' },
    { title: 'Detect', text: 'Checksum-validated patterns (Aadhaar Verhoeff, card Luhn, IBAN mod-97, GSTIN), names, secrets, company terms in one Aho–Corasick pass, plus in-process embeddings for topics and injections.' },
    { title: 'Decide', text: 'A versioned policy maps each finding to allow, flag, redact, keep on-prem or block, per team and channel. The strictest rule wins.' },
    { title: 'Redact reversibly', text: 'Values become placeholders like [EMAIL_1], consistent across the conversation. The mapping lives only in the request\'s memory.' },
    { title: 'Route', text: 'Model names map to provider chains with timeouts, circuit breakers and failover. Confidential work can be pinned to your own model.' },
    { title: 'Restore and audit', text: 'Real values are put back into the answer, even mid-stream. One audit event goes to Kafka with types and salted fingerprints, never values.' },
  ];
  detects = ['Aadhaar', 'PAN', 'GSTIN', 'IFSC', 'Bank accounts', 'Payment cards', 'IBAN', 'US SSN', 'Emails', 'Phone numbers', 'Person names',
    'IP addresses', 'AWS / GitHub / OpenAI / Stripe keys', 'Private keys', 'JWTs', 'Passwords', 'Company codenames', 'Source code',
    'Unreleased financials, M&A, HR, health, legal', 'Prompt injection'];
  adopt = [
    { for: 'Employees', title: 'Browser extension', text: 'Catches sensitive pastes on ChatGPT, Claude, Gemini, Copilot and more. Offers a redacted version, logs the choice, and maps which AI tools are really in use.', code: 'chrome://extensions → Load unpacked\nServer: https://veil.yourco.com' },
    { for: 'Developers', title: 'Drop-in proxy', text: 'OpenAI-compatible, including streaming. Point any SDK, LangChain or curl at Veil and use a virtual key. Answers come back with real values restored.', code: 'OpenAI(base_url="https://veil/v1",\n       api_key="vk_live_…")' },
    { for: 'Any system', title: 'Scan API', text: 'Find and redact sensitive data in tickets, transcripts, logs or documents before they are stored or indexed for RAG.', code: 'POST /v1/scan {"text": "…"}\n→ findings, redactedText' },
  ];
  engineering = [
    { title: 'No database on the hot path', text: 'Configuration is pushed through a compacted Kafka topic into every proxy\'s memory; the proxy is stateless and scales horizontally.' },
    { title: 'Streaming rehydration', text: 'Placeholders split across SSE chunks are restored with at most a few characters of delay.' },
    { title: 'Semantic cache on Redis vectors', text: 'Keyed on the redacted prompt, so two customers\' similar requests share an answer without sharing data.' },
    { title: 'Exactly-once audit on at-least-once Kafka', text: 'Idempotent inserts into day-partitioned Postgres; rollups only count new rows. Poison events go to a dead-letter topic.' },
  ];
  stack = ['Java 21', 'Spring Boot 4', 'WebFlux', 'Spring Cloud Gateway', 'Kafka', 'Redis 8 vector search', 'PostgreSQL', 'ONNX embeddings', 'Resilience4j',
    'Angular 22', 'Docker', 'Kubernetes', 'Chrome MV3'];
  diagram = `  Browser extension ──┐      Apps & SDKs ──┐
                      ▼                    ▼
  Console ──► edge-gateway        guard-proxy  ×N   (WebFlux)
              JWT · roles ·       detect → decide → redact
              rate limits         → cache → route → restore
                   │                 │   │        │
       control-plane ── outbox ─► Kafka  │     LLM providers
       policies, keys   veil.config ─────┘     (cloud / on-prem)
                                  veil.audit ──► insight-service
       Redis: keys · buckets · budgets · vector cache   audit · incidents
       Postgres: config (control) · audit (insight)     live feed · reports`;

  ngOnInit() {
    this.timer = setInterval(() => this.tick(), 60);
  }

  ngOnDestroy() {
    clearInterval(this.timer);
  }

  private holds = 0;

  private tick() {
    if (window.matchMedia?.('(prefers-reduced-motion: reduce)').matches) {
      this.phase.set(4);
      return;
    }
    if (this.phase() === 0) {
      this.typed.update(n => n + 3);
      if (this.typed() >= this.fullText.length) this.next(12);
      return;
    }
    if (++this.holds >= this.wait) this.next(this.phase() === 4 ? 60 : 22);
  }

  private wait = 0;

  private next(wait: number) {
    this.holds = 0;
    this.wait = wait;
    if (this.phase() === 4) {
      this.phase.set(0);
      this.typed.set(0);
    } else {
      this.phase.update(p => p + 1);
    }
  }
}
