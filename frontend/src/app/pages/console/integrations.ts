import { Component, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { PageHeader } from '../../ui/page';

const DEMO_KEY = 'vk_live_demo_support_7Hq2mX9pLw4nR8sT';
const DEMO_EXT_KEY = 'vk_ext_demo_support_B6wE3nU8yT1qA5zR';

@Component({
  selector: 'veil-integrations',
  imports: [PageHeader, RouterLink],
  template: `
    <veil-page-header title="Integrations" subtitle="Three ways to put Veil in front of AI. All of them are free and self-hosted." />

    <div class="grid lg:grid-cols-3 gap-4 mb-8">
      @for (w of ways; track w.title) {
        <div class="card p-5"><div class="text-2xl mb-2" aria-hidden="true">{{ w.icon }}</div><div class="font-semibold">{{ w.title }}</div>
          <p class="text-sm text-muted mt-1">{{ w.text }}</p></div>
      }
    </div>

    <section class="card p-6 mb-6" id="extension">
      <div class="flex flex-wrap items-start justify-between gap-4">
        <div class="max-w-2xl">
          <h2 class="text-lg font-semibold">Browser extension (Chrome, Edge, Brave)</h2>
          <p class="text-sm text-muted mt-1">Checks what employees paste or send on ChatGPT, Claude, Gemini, Copilot, Perplexity, DeepSeek and others,
            offers to send a redacted version, and reports which AI sites are in use.</p>
        </div>
        <a class="btn btn-primary" href="/downloads/veil-extension.zip" download>Download extension (.zip)</a>
      </div>
      <ol class="mt-5 grid md:grid-cols-2 gap-x-8 gap-y-3 text-sm list-decimal pl-5">
        <li>Unzip it, open <code class="font-mono text-[12.5px] bg-stone-100 px-1 rounded">chrome://extensions</code> and turn on <b>Developer mode</b>.</li>
        <li>Click <b>Load unpacked</b> and choose the unzipped <code class="font-mono text-[12.5px] bg-stone-100 px-1 rounded">veil-extension</code> folder.</li>
        <li>In the extension's options, set the server to <code class="font-mono text-[12.5px] bg-stone-100 px-1 rounded">{{ origin }}</code>, your email, and an extension key.</li>
        <li>Try it on the <a routerLink="/demo/chat" class="text-brand hover:underline">demo chat page</a>, or on any AI site.</li>
      </ol>
      <div class="mt-4 text-xs text-muted">Demo extension key (Support team): <code class="font-mono bg-stone-100 px-1 rounded">{{ demoExtKey }}</code>.
        Companies roll it out to every browser with Chrome Enterprise policies (ExtensionInstallForcelist + managed storage).</div>
    </section>

    <section class="card p-6 mb-6" id="proxy">
      <h2 class="text-lg font-semibold">Drop-in proxy for apps (OpenAI-compatible)</h2>
      <p class="text-sm text-muted mt-1 max-w-3xl">Change the base URL and the key. Streaming, tools and JSON mode keep working; Veil redacts on the way out and restores values in the answer.</p>
      <div class="flex flex-wrap gap-1.5 mt-4" role="tablist">
        @for (s of snippets; track s.label) {
          <button role="tab" class="btn btn-sm" [class]="tab() === s.label ? 'btn-primary' : 'btn-ghost'" (click)="tab.set(s.label)">{{ s.label }}</button>
        }
      </div>
      @for (s of snippets; track s.label) {
        @if (tab() === s.label) {
          <div class="relative mt-3">
            <pre class="rounded-xl bg-night text-[#e7e9ee] text-[12.5px] leading-relaxed p-4 overflow-x-auto"><code>{{ s.code }}</code></pre>
            <button class="absolute top-2 right-2 btn btn-sm bg-white/10 text-white hover:bg-white/20" (click)="copy(s.code)">{{ copied() === s.label ? 'Copied' : 'Copy' }}</button>
          </div>
        }
      }
      <p class="text-xs text-muted mt-3">The demo key belongs to the Support team. Create your own under <a routerLink="../keys" class="text-brand hover:underline">Keys &amp; teams</a>.</p>
    </section>

    <section class="card p-6" id="scan">
      <h2 class="text-lg font-semibold">Scan API</h2>
      <p class="text-sm text-muted mt-1 max-w-3xl">Check or redact any text before you store it, log it or send it anywhere: support tickets, call transcripts, documents for RAG.</p>
      <pre class="mt-4 rounded-xl bg-night text-[#e7e9ee] text-[12.5px] leading-relaxed p-4 overflow-x-auto"><code>{{ scanSnippet }}</code></pre>
    </section>`,
})
export class Integrations {
  origin = location.origin;
  demoExtKey = DEMO_EXT_KEY;
  tab = signal('Python');
  copied = signal('');
  ways = [
    { icon: '◐', title: 'For employees', text: 'A browser extension that catches sensitive pastes into AI chat websites, and shows you which AI tools are in use.' },
    { icon: '⇄', title: 'For apps', text: 'An OpenAI-compatible endpoint. Any SDK, LangChain or curl works by changing two lines.' },
    { icon: '⌕', title: 'For any system', text: 'A Scan API and a small Java client to find and redact sensitive data anywhere.' },
  ];
  snippets = [
    { label: 'Python', code: `from openai import OpenAI

client = OpenAI(base_url="${location.origin}/v1", api_key="${DEMO_KEY}")

reply = client.chat.completions.create(
    model="gpt-4o-mini",
    messages=[{"role": "user", "content": "Reply to Priya Sharma (priya@gmail.com) about her refund"}],
)
print(reply.choices[0].message.content)   # the model never saw Priya's name or email` },
    { label: 'Node.js', code: `import OpenAI from "openai";

const client = new OpenAI({ baseURL: "${location.origin}/v1", apiKey: "${DEMO_KEY}" });

const stream = await client.chat.completions.create({
  model: "gpt-4o-mini",
  stream: true,
  messages: [{ role: "user", content: "Draft a note to Rahul Verma, +91 98765 43210" }],
});
for await (const chunk of stream) process.stdout.write(chunk.choices[0]?.delta?.content ?? "");` },
    { label: 'curl', code: `curl ${location.origin}/v1/chat/completions \\
  -H "Authorization: Bearer ${DEMO_KEY}" \\
  -H "Content-Type: application/json" \\
  -d '{"model": "gpt-4o-mini", "messages": [{"role": "user", "content": "Email meera@acme.in about invoice 42"}]}'` },
    { label: 'Java (LangChain4j)', code: `ChatModel model = OpenAiChatModel.builder()
        .baseUrl("${location.origin}/v1")
        .apiKey("${DEMO_KEY}")
        .modelName("gpt-4o-mini")
        .build();

String answer = model.chat("Summarise this ticket from priya@gmail.com: ...");` },
    { label: 'LangChain', code: `from langchain_openai import ChatOpenAI

llm = ChatOpenAI(base_url="${location.origin}/v1", api_key="${DEMO_KEY}", model="gpt-4o-mini")
print(llm.invoke("Write to customer Arjun Mehta about his card ending 4242").content)` },
  ];
  scanSnippet = `# HTTP
curl ${location.origin}/v1/scan -H "Authorization: Bearer ${DEMO_KEY}" -H "Content-Type: application/json" \\
  -d '{"text": "PAN ABCPE1234F, mail a.b@acme.in"}'
# → {"action":"REDACT","redactedText":"PAN [PAN_1], mail [EMAIL_1]","findings":[...]}

// Java (clients/java in the repo)
VeilClient veil = VeilClient.builder("${location.origin}", System.getenv("VEIL_KEY")).build();
String safe = veil.redact(ticket.body());`;

  copy(code: string) {
    navigator.clipboard?.writeText(code).then(() => {
      this.copied.set(this.tab());
      setTimeout(() => this.copied.set(''), 1500);
    });
  }
}
