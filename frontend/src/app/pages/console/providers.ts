import { Component, OnDestroy, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Api, Provider, Route } from '../../core/api';
import { AuthService } from '../../core/auth.service';
import { PageHeader } from '../../ui/page';
import { Toasts } from '../../ui/toast';
import { Confirm } from '../../ui/confirm';

@Component({
  selector: 'veil-providers',
  imports: [FormsModule, PageHeader],
  template: `
    <veil-page-header title="Providers & routes"
      subtitle="Where requests go. Each model name your apps use maps to an ordered list of providers; if one fails, the next takes over, and a circuit breaker stops waiting on a provider that keeps failing.">
      @if (auth.isAdmin()) { <button class="btn btn-primary" (click)="newProvider()">Add provider</button> }
    </veil-page-header>

    <div class="grid grid-cols-1 xl:grid-cols-[minmax(0,1fr)_340px] gap-5 items-start">
      <div class="flex flex-col gap-5 min-w-0">
        <section class="card overflow-x-auto">
          <table class="table">
            <thead><tr><th>Provider</th><th>Kind</th><th>Where</th><th>Circuit</th><th>Fault injection</th><th></th></tr></thead>
            <tbody>
              @for (p of providers(); track p.id) {
                <tr [class.opacity-55]="!p.enabled">
                  <td><div class="font-medium text-[13.5px]">{{ p.name }}</div><div class="font-mono text-[11px] text-muted">{{ p.id }}</div></td>
                  <td class="text-[13px]">{{ kindLabel(p.kind) }}<div class="text-[11px] text-muted">{{ p.hasApiKey ? 'key stored (encrypted)' : p.kind === 'ECHO' ? 'built in, free' : 'no key' }}</div></td>
                  <td class="text-[13px]">@if (p.local) { <span class="text-local font-medium">On-premises</span> } @else { Cloud }</td>
                  <td>@let st = breaker(p.id);
                    <span class="text-xs font-medium" [class]="st === 'OPEN' ? 'text-block' : st === 'HALF_OPEN' ? 'text-warn' : 'text-protect'">{{ st === 'OPEN' ? 'Open (skipped)' : st === 'HALF_OPEN' ? 'Testing' : st ? 'Closed (healthy)' : 'No traffic yet' }}</span></td>
                  <td>
                    @if (p.kind === 'ECHO' && auth.isAdmin()) {
                      <div class="flex items-center gap-2">
                        <select class="select !h-8 !w-36 !py-0 text-xs" [ngModel]="faultRate(p.id)" (ngModelChange)="setFault(p.id, $event)" [attr.aria-label]="'Failure rate for ' + p.name">
                          <option [ngValue]="0">Healthy</option><option [ngValue]="0.5">50% failures</option><option [ngValue]="1">Outage</option>
                        </select>
                      </div>
                    } @else { <span class="text-xs text-muted">—</span> }
                  </td>
                  <td class="text-right">@if (auth.isAdmin()) { <button class="btn btn-ghost btn-sm" (click)="editProvider(p)">Edit</button> }</td>
                </tr>
              }
            </tbody>
          </table>
        </section>

        <section class="card">
          <div class="flex items-center justify-between px-5 pt-5 pb-3"><h2 class="font-semibold">Routes</h2>
            @if (auth.isAdmin()) { <button class="btn btn-ghost btn-sm" (click)="newRoute()">Add route</button> }</div>
          <div class="overflow-x-auto"><table class="table">
            <thead><tr><th>Model name apps ask for</th><th>Goes to (in order)</th><th class="text-right">Price in / out per 1M tokens</th><th></th></tr></thead>
            <tbody>
              @for (r of routes(); track r.model) {
                <tr>
                  <td class="font-mono text-[13px]">{{ r.model }}</td>
                  <td><div class="flex flex-wrap items-center gap-1.5 text-[13px]">
                    @for (t of r.targets; track $index; let last = $last) {
                      <span class="rounded-md border border-line px-2 py-0.5">{{ providerName(t.providerId) }} <span class="text-muted font-mono text-[11px]">{{ t.upstreamModel }}</span></span>
                      @if (!last) { <span class="text-muted">→</span> }
                    }</div></td>
                  <td class="text-right tabular text-[13px]">\${{ r.inputUsdPerMTok }} / \${{ r.outputUsdPerMTok }}</td>
                  <td class="text-right">@if (auth.isAdmin()) { <button class="btn btn-ghost btn-sm" (click)="editRoute(r)">Edit</button> }</td>
                </tr>
              }
            </tbody>
          </table></div>
        </section>
      </div>

      <section class="card p-5">
        <h2 class="font-semibold mb-3">This proxy instance</h2>
        @if (status(); as s) {
          <dl class="grid grid-cols-2 gap-y-3 gap-x-4 text-sm">
            <div><dt class="text-xs text-muted">Config version</dt><dd class="tabular">v{{ s.configVersion }} (policy v{{ s.policyVersion }})</dd></div>
            <div><dt class="text-xs text-muted">Uptime</dt><dd class="tabular">{{ uptime(s.uptimeSeconds) }}</dd></div>
            <div><dt class="text-xs text-muted">Semantic cache</dt><dd class="tabular">{{ s.semanticCache ? (s.cacheHits + ' hits / ' + s.cacheMisses + ' misses') : 'unavailable' }}</dd></div>
            <div><dt class="text-xs text-muted">Audit buffer</dt><dd class="tabular">{{ s.auditBuffered }} waiting</dd></div>
            <div><dt class="text-xs text-muted">Detection p50 / p99</dt><dd class="tabular">{{ s.detection?.p50 ?? '—' }} / {{ s.detection?.p99 ?? '—' }} ms</dd></div>
            <div><dt class="text-xs text-muted">Embedding p50</dt><dd class="tabular">{{ s.embedding?.p50 ?? '—' }} ms</dd></div>
          </dl>
          <p class="text-xs text-muted mt-4">Try it: set Veil Echo to "Outage", then send a gpt-4o-mini request from the Playground. It fails over to the EU backup, and after a few failures the circuit opens.</p>
        } @else { <div class="skeleton h-32"></div> }
      </section>
    </div>

    @if (providerForm(); as f) {
      <div class="fixed inset-0 z-40 bg-black/30" (click)="providerForm.set(null)"></div>
      <div class="fixed z-50 inset-x-4 top-[8vh] mx-auto max-w-lg card p-6 shadow-2xl fade-up" role="dialog" aria-label="Provider">
        <h2 class="text-lg font-semibold mb-4">{{ f.isNew ? 'Add provider' : 'Edit ' + f.name }}</h2>
        <div class="flex flex-col gap-3">
          <div class="grid grid-cols-2 gap-3">
            <div><label class="label">Id</label><input class="input font-mono" [(ngModel)]="f.id" [disabled]="!f.isNew" placeholder="groq" /></div>
            <div><label class="label">Kind</label><select class="select" [(ngModel)]="f.kind">
              <option value="OPENAI_COMPATIBLE">OpenAI-compatible</option><option value="ANTHROPIC">Anthropic</option><option value="ECHO">Veil Echo (simulated)</option></select></div>
          </div>
          <div><label class="label">Name</label><input class="input" [(ngModel)]="f.name" placeholder="Groq (free tier)" /></div>
          @if (f.kind !== 'ECHO') {
            <div><label class="label">Base URL</label><input class="input font-mono text-[13px]" [(ngModel)]="f.baseUrl" placeholder="https://api.groq.com/openai/v1" /></div>
            <div><label class="label">API key {{ f.isNew ? '' : '(leave empty to keep the current one)' }}</label>
              <input class="input font-mono" type="password" [(ngModel)]="f.apiKey" autocomplete="off" /></div>
          }
          <div class="flex gap-5 text-sm">
            <label class="inline-flex items-center gap-2"><input type="checkbox" class="accent-brand" [(ngModel)]="f.local" />Runs on-premises</label>
            <label class="inline-flex items-center gap-2"><input type="checkbox" class="accent-brand" [(ngModel)]="f.enabled" />Enabled</label>
          </div>
          <p class="text-xs text-muted">Keys are encrypted with AES-256-GCM before they are stored and are never shown again.</p>
        </div>
        <div class="flex justify-end gap-2 mt-6"><button class="btn btn-ghost" (click)="providerForm.set(null)">Cancel</button>
          <button class="btn btn-primary" (click)="saveProvider()">Save</button></div>
      </div>
    }

    @if (routeForm(); as r) {
      <div class="fixed inset-0 z-40 bg-black/30" (click)="routeForm.set(null)"></div>
      <div class="fixed z-50 inset-x-4 top-[8vh] mx-auto max-w-xl card p-6 shadow-2xl fade-up" role="dialog" aria-label="Route">
        <h2 class="text-lg font-semibold mb-4">Route</h2>
        <div class="flex flex-col gap-3">
          <div><label class="label">Model name apps ask for</label><input class="input font-mono" [(ngModel)]="r.model" /></div>
          <div><label class="label">Targets, tried in order</label>
            @for (t of r.targets; track $index; let i = $index) {
              <div class="flex gap-2 mb-2">
                <select class="select" [(ngModel)]="t.providerId">@for (p of providers(); track p.id) { <option [value]="p.id">{{ p.name }}</option> }</select>
                <input class="input font-mono text-[13px]" [(ngModel)]="t.upstreamModel" placeholder="provider's model name" />
                <button class="btn btn-ghost" (click)="r.targets.splice(i, 1)" aria-label="Remove target">✕</button>
              </div>
            }
            <button class="btn btn-ghost btn-sm" (click)="r.targets.push({ providerId: providers()[0]?.id ?? '', upstreamModel: '' })">Add fallback</button>
          </div>
          <div class="grid grid-cols-2 gap-3">
            <div><label class="label">Input $ / 1M tokens</label><input type="number" min="0" step="0.01" class="input" [(ngModel)]="r.inputUsdPerMTok" /></div>
            <div><label class="label">Output $ / 1M tokens</label><input type="number" min="0" step="0.01" class="input" [(ngModel)]="r.outputUsdPerMTok" /></div>
          </div>
        </div>
        <div class="flex justify-between gap-2 mt-6">
          <button class="btn btn-ghost text-block" (click)="deleteRoute(r.model)">Delete</button>
          <div class="flex gap-2"><button class="btn btn-ghost" (click)="routeForm.set(null)">Cancel</button><button class="btn btn-primary" (click)="saveRoute()">Save</button></div>
        </div>
      </div>
    }`,
})
export class Providers implements OnDestroy {
  private api = inject(Api);
  private toasts = inject(Toasts);
  private confirm = inject(Confirm);
  auth = inject(AuthService);
  providers = signal<Provider[]>([]);
  routes = signal<Route[]>([]);
  status = signal<any>(null);
  providerForm = signal<any>(null);
  routeForm = signal<Route | null>(null);
  private timer = setInterval(() => this.loadStatus(), 3000);

  constructor() {
    this.load();
    this.loadStatus();
  }

  ngOnDestroy() {
    clearInterval(this.timer);
  }

  load() {
    this.api.providers().subscribe({ next: p => this.providers.set(p), error: e => this.toasts.error(e) });
    this.api.routes().subscribe({ next: r => this.routes.set(r), error: () => {} });
  }

  loadStatus() {
    this.api.proxyStatus().subscribe({ next: s => this.status.set(s), error: () => {} });
  }

  breaker(id: string): string | null {
    return this.status()?.circuitBreakers?.[id] ?? null;
  }

  faultRate(id: string): number {
    return this.status()?.faults?.[id]?.errorRate ?? 0;
  }

  setFault(id: string, rate: number) {
    this.api.setFault(id, rate, 0).subscribe({
      next: () => { this.toasts.show(rate ? `${id} now fails ${rate * 100}% of requests` : `${id} is healthy again`, 'info'); this.loadStatus(); },
      error: e => this.toasts.error(e),
    });
  }

  kindLabel(k: string) {
    return k === 'ECHO' ? 'Veil Echo (simulated)' : k === 'ANTHROPIC' ? 'Anthropic' : 'OpenAI-compatible';
  }

  providerName(id: string) {
    return this.providers().find(p => p.id === id)?.name ?? id;
  }

  newProvider() {
    this.providerForm.set({ isNew: true, id: '', name: '', kind: 'OPENAI_COMPATIBLE', baseUrl: '', apiKey: '', local: false, enabled: true });
  }

  editProvider(p: Provider) {
    this.providerForm.set({ isNew: false, ...p, baseUrl: p.baseUrl ?? '', apiKey: '' });
  }

  saveProvider() {
    const f = this.providerForm();
    this.api.saveProvider(f.id.trim(), { name: f.name, kind: f.kind, baseUrl: f.kind === 'ECHO' ? null : f.baseUrl,
      apiKey: f.apiKey ? f.apiKey : f.isNew ? '' : null, local: f.local, enabled: f.enabled }).subscribe({
      next: () => { this.providerForm.set(null); this.toasts.show('Provider saved and pushed to the proxies'); this.load(); },
      error: e => this.toasts.error(e),
    });
  }

  newRoute() {
    this.routeForm.set({ model: '', targets: [{ providerId: this.providers()[0]?.id ?? '', upstreamModel: '' }], inputUsdPerMTok: 0, outputUsdPerMTok: 0 });
  }

  editRoute(r: Route) {
    this.routeForm.set(structuredClone(r));
  }

  saveRoute() {
    this.api.saveRoute(this.routeForm()!).subscribe({
      next: () => { this.routeForm.set(null); this.toasts.show('Route saved'); this.load(); },
      error: e => this.toasts.error(e),
    });
  }

  async deleteRoute(model: string) {
    if (!(await this.confirm.ask('Delete this route?', `Apps asking for ${model} will get "model not found".`, 'Delete route'))) return;
    this.api.deleteRoute(model).subscribe({ next: () => { this.routeForm.set(null); this.load(); }, error: e => this.toasts.error(e) });
  }

  uptime(s: number) {
    return s > 3600 ? `${Math.floor(s / 3600)}h ${Math.floor((s % 3600) / 60)}m` : `${Math.floor(s / 60)}m ${s % 60}s`;
  }
}
