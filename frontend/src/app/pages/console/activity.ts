import { Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Api, RequestRow, Team } from '../../core/api';
import { ago, micros, ms, time, usd } from '../../core/format';
import { ActionBadge, Chip, SeverityBadge } from '../../ui/badges';
import { Placeholders } from '../../ui/highlight';
import { Empty, PageHeader } from '../../ui/page';
import { Toasts } from '../../ui/toast';

@Component({
  selector: 'veil-activity',
  imports: [FormsModule, PageHeader, ActionBadge, SeverityBadge, Chip, Placeholders, Empty],
  template: `
    <veil-page-header title="Activity" subtitle="Every request Veil handled. Values are never stored: prompts are kept redacted and data is reduced to fingerprints.">
      <a class="btn btn-ghost" href="/api/insight/export.csv?range=7d" (click)="download($event)">Export CSV</a>
    </veil-page-header>

    <div class="card p-3 mb-4 flex flex-wrap gap-2 items-center">
      <input class="input !w-64" placeholder="Search prompt, person, model, site, id…" [(ngModel)]="q" (keyup.enter)="reload()" aria-label="Search" />
      <select class="select !w-40" [(ngModel)]="action" (ngModelChange)="reload()" aria-label="Decision">
        <option value="">Any decision</option><option value="BLOCK">Blocked</option><option value="REDACT">Redacted</option>
        <option value="ROUTE_LOCAL">Kept on-prem</option><option value="WARN">Flagged</option><option value="ALLOW">Allowed</option>
      </select>
      <select class="select !w-36" [(ngModel)]="team" (ngModelChange)="reload()" aria-label="Team">
        <option value="">Any team</option>@for (t of teams(); track t.id) { <option [value]="t.id">{{ t.name }}</option> }
      </select>
      <select class="select !w-40" [(ngModel)]="source" (ngModelChange)="reload()" aria-label="Channel">
        <option value="">Any channel</option><option value="api">Apps (proxy)</option><option value="extension">Browser extension</option><option value="scan">Scan API</option>
      </select>
      <select class="select !w-44" [(ngModel)]="type" (ngModelChange)="reload()" aria-label="Data type">
        <option value="">Any data type</option>@for (c of catalogue(); track c.type) { <option [value]="c.type">{{ c.label }}</option> }
      </select>
      <button class="btn btn-ghost" (click)="reload()">Search</button>
      @if (q || action || team || source || type) { <button class="btn btn-ghost" (click)="clear()">Clear</button> }
    </div>

    <div class="card overflow-x-auto">
      <table class="table">
        <thead><tr><th>When</th><th>Decision</th><th>Team · who</th><th>Where</th><th>Found</th><th class="text-right">Latency</th><th class="text-right">Cost</th></tr></thead>
        <tbody>
          @for (r of rows(); track r.id) {
            <tr class="clickable" (click)="open(r)" [class.bg-brand-soft]="selected()?.id === r.id">
              <td class="text-muted text-xs tabular whitespace-nowrap" [title]="time(r.at)">{{ ago(r.at) }}</td>
              <td><veil-action [action]="r.action" /></td>
              <td class="whitespace-nowrap"><div class="font-medium text-[13px]">{{ r.teamName }}</div><div class="text-[11px] text-muted truncate max-w-48">{{ r.actor ?? 'app key' }}</div></td>
              <td class="text-[13px] whitespace-nowrap">{{ r.source === 'extension' ? r.site : r.source === 'scan' ? 'Scan API' : r.model }}
                <div class="text-[11px] text-muted">{{ kindLabel(r) }}</div></td>
              <td><div class="flex flex-wrap gap-1 max-w-72">@for (t of r.types.slice(0, 3); track t) { <veil-chip>{{ t }}</veil-chip> }
                @if (r.types.length > 3) { <span class="text-[11px] text-muted">+{{ r.types.length - 3 }}</span> }</div></td>
              <td class="text-right tabular text-[13px]">{{ r.kind === 'VISIT' ? '—' : ms(r.latencyMs) }}</td>
              <td class="text-right tabular text-[13px]">{{ r.cacheHit ? 'cache' : r.costMicros ? usd(r.costMicros) : '—' }}</td>
            </tr>
          }
        </tbody>
      </table>
      @if (!loading() && rows().length === 0) { <veil-empty title="No requests match" text="Try a wider filter, or send something from the Playground." /> }
      @if (loading()) { <div class="p-4"><div class="skeleton h-6"></div></div> }
    </div>
    @if (next()) { <div class="text-center mt-4"><button class="btn btn-ghost" (click)="more()">Load more</button></div> }

    @if (selected(); as s) {
      <div class="fixed inset-0 z-40 bg-black/20" (click)="selected.set(null)"></div>
      <aside class="fixed z-50 top-0 right-0 h-full w-full sm:w-[520px] bg-white border-l border-line shadow-2xl overflow-y-auto fade-up" role="dialog" aria-label="Request details">
        <div class="sticky top-0 bg-white border-b border-line px-5 py-4 flex items-center justify-between">
          <div><div class="font-semibold">Request</div><div class="font-mono text-[11px] text-muted">{{ s.id }}</div></div>
          <button class="btn btn-ghost btn-sm" (click)="selected.set(null)">Close</button>
        </div>
        @if (detail(); as d) {
          <div class="p-5 flex flex-col gap-5 text-sm">
            <div class="flex items-center gap-2"><veil-action [action]="d.action" /><span class="text-muted">{{ d.status }} · {{ time(d.at) }}</span></div>
            <dl class="grid grid-cols-2 gap-x-4 gap-y-3">
              <div><dt class="text-xs text-muted">Team</dt><dd>{{ d.teamName }}</dd></div>
              <div><dt class="text-xs text-muted">Who</dt><dd>{{ d.actor ?? 'Application key ' + d.keyId }}</dd></div>
              <div><dt class="text-xs text-muted">Channel</dt><dd>{{ d.source }} · {{ d.kind }}</dd></div>
              <div><dt class="text-xs text-muted">{{ d.site ? 'Site' : 'Model' }}</dt><dd>{{ d.site ?? d.model ?? '—' }}{{ d.provider ? ' via ' + d.provider : '' }}</dd></div>
              <div><dt class="text-xs text-muted">Latency</dt><dd class="tabular">{{ ms(d.latencyMs) }} (detection {{ micros(d.detectMicros) }})</dd></div>
              <div><dt class="text-xs text-muted">Tokens · cost</dt><dd class="tabular">{{ d.tokensIn }} / {{ d.tokensOut }} · {{ usd(d.costMicros) }}</dd></div>
              <div><dt class="text-xs text-muted">Policy</dt><dd>v{{ d.policyVersion }}</dd></div>
              <div><dt class="text-xs text-muted">Cache</dt><dd>{{ d.cacheHit ? 'Semantic cache hit' : 'Miss' }}</dd></div>
            </dl>
            @if (d.reasons?.length) {
              <div><div class="eyebrow mb-1.5">Why</div><ul class="list-disc pl-5 space-y-1 text-ink-2">@for (r of d.reasons; track r) { <li>{{ r }}</li> }</ul></div>
            }
            @if (d.redactedPrompt) {
              <div><div class="eyebrow mb-1.5">Prompt as stored (redacted)</div>
                <div class="rounded-xl bg-[#fbfbf9] border border-line p-3 text-[13px]"><veil-placeholders [text]="d.redactedPrompt" /></div></div>
            }
            @if (d.findings?.length) {
              <div><div class="eyebrow mb-1.5">Findings</div>
                <table class="table"><thead><tr><th>Type</th><th>Where</th><th>Decision</th><th>Fingerprint</th></tr></thead><tbody>
                  @for (f of d.findings; track $index) {
                    <tr><td><div class="flex items-center gap-1.5"><veil-severity [severity]="f.severity" />{{ f.detail }}</div></td>
                      <td class="text-xs text-muted">{{ f.location }} {{ f.start }}–{{ f.end }}</td>
                      <td><veil-action [action]="f.action" /></td><td class="font-mono text-[11px] text-muted">{{ f.fingerprint ?? '—' }}</td></tr>
                  }
                </tbody></table>
                <p class="text-[11px] text-muted mt-2">Fingerprints are salted hashes: the same value always has the same fingerprint, so repeats can be traced without storing it.</p>
              </div>
            }
          </div>
        } @else { <div class="p-5"><div class="skeleton h-40"></div></div> }
      </aside>
    }`,
})
export class Activity {
  private api = inject(Api);
  private toasts = inject(Toasts);
  rows = signal<RequestRow[]>([]);
  next = signal('');
  loading = signal(false);
  teams = signal<Team[]>([]);
  catalogue = signal<{ type: string; label: string }[]>([]);
  selected = signal<RequestRow | null>(null);
  detail = signal<any>(null);
  q = ''; action = ''; team = ''; source = ''; type = '';
  ago = ago; time = time; ms = ms; micros = micros; usd = usd;

  constructor() {
    this.api.teams().subscribe({ next: t => this.teams.set(t), error: () => {} });
    this.api.catalogue().subscribe({ next: c => this.catalogue.set(c), error: () => {} });
    this.reload();
  }

  filters(cursor = '') {
    return { q: this.q, action: this.action, team: this.team, source: this.source, type: this.type, cursor, limit: '50' };
  }

  reload() {
    this.loading.set(true);
    this.api.requests(this.filters()).subscribe({
      next: r => { this.rows.set(r.items); this.next.set(r.nextCursor); this.loading.set(false); },
      error: e => { this.loading.set(false); this.toasts.error(e); },
    });
  }

  more() {
    this.api.requests(this.filters(this.next())).subscribe({
      next: r => { this.rows.update(x => [...x, ...r.items]); this.next.set(r.nextCursor); },
      error: e => this.toasts.error(e),
    });
  }

  clear() {
    this.q = this.action = this.team = this.source = this.type = '';
    this.reload();
  }

  open(r: RequestRow) {
    this.selected.set(r);
    this.detail.set(null);
    this.api.request(r.id).subscribe({ next: d => this.detail.set(d), error: e => this.toasts.error(e) });
  }

  kindLabel(r: RequestRow) {
    return ({ CHAT: 'chat completion', SCAN: 'scan', PASTE: 'paste', SUBMIT: 'message sent', VISIT: 'site visit' } as any)[r.kind] ?? r.kind;
  }

  /** The export needs the session token, which a plain link can't send. */
  download(e: Event) {
    e.preventDefault();
    const token = JSON.parse(localStorage.getItem('veil.session') ?? '{}').token;
    fetch('/api/insight/export.csv?range=7d', { headers: { Authorization: `Bearer ${token}` } })
      .then(r => r.blob())
      .then(b => {
        const a = document.createElement('a');
        a.href = URL.createObjectURL(b);
        a.download = 'veil-audit-7d.csv';
        a.click();
        URL.revokeObjectURL(a.href);
      })
      .catch(() => this.toasts.show('Export failed', 'error'));
  }
}
