import { Component, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Action, Api, CatalogueEntry, Policy, Rule, ScanResult, Team } from '../../core/api';
import { AuthService } from '../../core/auth.service';
import { ago, micros } from '../../core/format';
import { ActionBadge, Chip } from '../../ui/badges';
import { Highlight, Span } from '../../ui/highlight';
import { PageHeader } from '../../ui/page';
import { Toasts } from '../../ui/toast';

const ACTIONS: { value: Action; label: string }[] = [
  { value: 'ALLOW', label: 'Allow' }, { value: 'WARN', label: 'Flag' }, { value: 'REDACT', label: 'Redact' },
  { value: 'ROUTE_LOCAL', label: 'Keep on-prem' }, { value: 'BLOCK', label: 'Block' },
];
const CATEGORY_LABEL: Record<string, string> = {
  CONTACT: 'Contact details', PERSONAL: 'Personal', GOVERNMENT_ID: 'Government IDs', FINANCIAL: 'Financial', TECHNICAL: 'Technical',
  SECRET: 'Secrets', CONFIDENTIAL: 'Confidential', THREAT: 'Threats',
};

@Component({
  selector: 'veil-policies',
  imports: [FormsModule, PageHeader, ActionBadge, Chip, Highlight],
  template: `
    <veil-page-header [title]="'Policy v' + (live()?.version ?? '…')"
      subtitle="For every finding, the strictest matching rule decides. Changes are versioned and reach every proxy within a second of publishing.">
      @if (dirty()) { <span class="text-xs text-warn font-medium">Unpublished changes</span> }
      @if (auth.isAdmin()) {
        <input class="input !w-56 !h-9" placeholder="What changed? (optional)" [(ngModel)]="note" aria-label="Change note" />
        <button class="btn btn-ghost" (click)="discard()" [disabled]="!dirty()">Discard</button>
        <button class="btn btn-primary" (click)="publish()" [disabled]="!dirty() || publishing()">{{ publishing() ? 'Publishing…' : 'Publish' }}</button>
      }
    </veil-page-header>

    @if (draft(); as p) {
      <div class="grid grid-cols-1 2xl:grid-cols-[minmax(0,1fr)_400px] gap-5 items-start">
        <div class="flex flex-col gap-5 min-w-0">
          <section class="card">
            <div class="flex items-center justify-between px-5 pt-5 pb-3">
              <h2 class="font-semibold">Rules <span class="text-muted font-normal text-sm">· {{ p.rules.length }}</span></h2>
              @if (auth.isAdmin()) { <button class="btn btn-ghost btn-sm" (click)="addRule()">Add rule</button> }
            </div>
            <div class="overflow-x-auto">
              <table class="table">
                <thead><tr><th class="w-10">On</th><th>Rule</th><th>When Veil finds</th><th>Applies to</th><th class="text-right">Min. conf.</th><th>Then</th><th></th></tr></thead>
                <tbody>
                  @for (r of p.rules; track r.id; let i = $index) {
                    <tr [class.opacity-50]="!r.enabled">
                      <td><input type="checkbox" [checked]="r.enabled" (change)="toggle(i)" [disabled]="!auth.isAdmin()" [attr.aria-label]="'Enable ' + r.name" class="accent-brand size-4" /></td>
                      <td class="font-medium text-[13.5px] min-w-44">{{ r.name }}</td>
                      <td><div class="flex flex-wrap gap-1 max-w-80">
                        @for (c of r.categories; track c) { <veil-chip>{{ categoryLabel(c) }}</veil-chip> }
                        @for (t of r.types; track t) { <veil-chip>{{ typeLabel(t) }}</veil-chip> }
                        @if (!r.categories.length && !r.types.length) { <span class="text-xs text-muted">anything</span> }</div></td>
                      <td class="text-xs text-ink-2 whitespace-nowrap">{{ r.teams.length ? teamNames(r.teams) : 'All teams' }}<br/>
                        <span class="text-muted">{{ r.sources.length ? sourceNames(r.sources) : 'Apps and browser' }}</span></td>
                      <td class="text-right tabular text-[13px]">{{ (r.minConfidence * 100).toFixed(0) }}%</td>
                      <td><veil-action [action]="r.action" /></td>
                      <td class="whitespace-nowrap text-right">
                        @if (auth.isAdmin()) {
                          <button class="btn btn-ghost btn-sm" (click)="edit(i)">Edit</button>
                          <button class="btn btn-ghost btn-sm" (click)="remove(i)" [attr.aria-label]="'Delete ' + r.name">✕</button>
                        }
                      </td>
                    </tr>
                    @if (editing() === i) {
                      <tr><td colspan="7" class="!p-0">
                        <div class="bg-[#fbfbf9] border-y border-line p-5 grid lg:grid-cols-2 gap-5">
                          <div class="flex flex-col gap-3">
                            <div><label class="label">Name</label><input class="input" [(ngModel)]="r.name" (ngModelChange)="touch()" /></div>
                            <div class="grid grid-cols-2 gap-3">
                              <div><label class="label">Then</label>
                                <select class="select" [(ngModel)]="r.action" (ngModelChange)="touch()">@for (a of actions; track a.value) { <option [value]="a.value">{{ a.label }}</option> }</select></div>
                              <div><label class="label">Minimum confidence: {{ (r.minConfidence * 100).toFixed(0) }}%</label>
                                <input type="range" min="0.5" max="0.99" step="0.01" class="w-full accent-brand mt-2" [(ngModel)]="r.minConfidence" (ngModelChange)="touch()" /></div>
                            </div>
                            <div><label class="label">Teams (none = all)</label>
                              <div class="flex flex-wrap gap-x-4 gap-y-1.5">@for (t of teams(); track t.id) {
                                <label class="text-sm inline-flex items-center gap-1.5"><input type="checkbox" class="accent-brand" [checked]="r.teams.includes(t.id)" (change)="toggleIn(r.teams, t.id)" />{{ t.name }}</label> }</div></div>
                            <div><label class="label">Channels (none = both)</label>
                              <div class="flex gap-4">
                                <label class="text-sm inline-flex items-center gap-1.5"><input type="checkbox" class="accent-brand" [checked]="r.sources.includes('api')" (change)="toggleIn(r.sources, 'api')" />Apps (proxy, Scan API)</label>
                                <label class="text-sm inline-flex items-center gap-1.5"><input type="checkbox" class="accent-brand" [checked]="r.sources.includes('extension')" (change)="toggleIn(r.sources, 'extension')" />Browser extension</label>
                              </div></div>
                          </div>
                          <div>
                            <label class="label">When Veil finds (whole categories or single types)</label>
                            <div class="grid sm:grid-cols-2 gap-3 max-h-80 overflow-y-auto pr-1">
                              @for (g of grouped(); track g.category) {
                                <div class="rounded-lg border border-line bg-white p-2.5">
                                  <label class="text-[13px] font-medium inline-flex items-center gap-1.5"><input type="checkbox" class="accent-brand" [checked]="r.categories.includes(g.category)" (change)="toggleIn(r.categories, g.category)" />{{ categoryLabel(g.category) }}</label>
                                  <div class="mt-1.5 pl-5 flex flex-col gap-1">@for (t of g.types; track t.type) {
                                    <label class="text-xs inline-flex items-center gap-1.5 text-ink-2"><input type="checkbox" class="accent-brand" [checked]="r.types.includes(t.type)" (change)="toggleIn(r.types, t.type)" />{{ t.label }}</label> }</div>
                                </div>
                              }
                            </div>
                            <div class="text-right mt-3"><button class="btn btn-primary btn-sm" (click)="editing.set(-1)">Done</button></div>
                          </div>
                        </div>
                      </td></tr>
                    }
                  }
                </tbody>
              </table>
            </div>
          </section>

          <section class="card p-5">
            <h2 class="font-semibold mb-4">Settings</h2>
            <div class="grid md:grid-cols-2 gap-x-8 gap-y-4 text-sm">
              <label class="flex items-start gap-3"><input type="checkbox" class="accent-brand size-4 mt-0.5"
                     [checked]="p.failMode === 'CLOSED'" (change)="p.failMode = p.failMode === 'CLOSED' ? 'OPEN' : 'CLOSED'; touch()" [disabled]="!auth.isAdmin()" />
                <span><span class="font-medium">Fail closed</span><br/><span class="text-muted text-xs">If detection can't finish in time, refuse the request instead of sending it with pattern checks only.</span></span></label>
              <label class="flex items-start gap-3"><input type="checkbox" class="accent-brand size-4 mt-0.5" [(ngModel)]="p.semanticCache" (ngModelChange)="touch()" [disabled]="!auth.isAdmin()" />
                <span><span class="font-medium">Semantic cache</span><br/><span class="text-muted text-xs">Answer near-identical redacted prompts from cache. Cuts cost and latency; values never enter the cache.</span></span></label>
              <label class="flex items-start gap-3"><input type="checkbox" class="accent-brand size-4 mt-0.5" [(ngModel)]="p.storeRedactedPrompts" (ngModelChange)="touch()" [disabled]="!auth.isAdmin()" />
                <span><span class="font-medium">Keep redacted prompts in the audit log</span><br/><span class="text-muted text-xs">Reviewers see context with every detected value replaced.</span></span></label>
              <label class="flex items-start gap-3"><input type="checkbox" class="accent-brand size-4 mt-0.5" [(ngModel)]="p.outputScanning" (ngModelChange)="touch()" [disabled]="!auth.isAdmin()" />
                <span><span class="font-medium">Scan model answers</span><br/><span class="text-muted text-xs">Mask secrets or personal data the model produces on its own.</span></span></label>
              <div><label class="label">Max output tokens per request (0 = no cap)</label>
                <input type="number" min="0" class="input !w-40" [(ngModel)]="p.maxOutputTokens" (ngModelChange)="touch()" [disabled]="!auth.isAdmin()" /></div>
              <div><label class="label">Allowed models (none selected = all)</label>
                <div class="flex flex-wrap gap-x-4 gap-y-1.5">@for (m of models(); track m) {
                  <label class="text-sm inline-flex items-center gap-1.5"><input type="checkbox" class="accent-brand" [checked]="p.allowedModels.includes(m)" (change)="toggleIn(p.allowedModels, m)" [disabled]="!auth.isAdmin()" /><span class="font-mono text-xs">{{ m }}</span></label> }</div></div>
            </div>
          </section>
        </div>

        <div class="flex flex-col gap-5">
          <section class="card p-5">
            <div class="flex items-center justify-between mb-1"><h2 class="font-semibold">Policy copilot</h2>
              <span class="text-[11px] text-muted">{{ copilotMode() }}</span></div>
            <p class="text-xs text-muted mb-3">Describe what you want in plain English. Drafts are never published until you review them.</p>
            <textarea class="textarea text-[13px]" rows="4" [(ngModel)]="instruction"
              placeholder="Block API keys and passwords everywhere. Redact Aadhaar and PAN for the Support team; keep confidential topics on-prem for Finance."></textarea>
            <button class="btn btn-ghost w-full mt-2" (click)="draftRules()" [disabled]="drafting() || !instruction.trim()">{{ drafting() ? 'Drafting…' : 'Draft rules' }}</button>
            @if (drafted().length || notes().length) {
              <div class="mt-4 flex flex-col gap-2">
                @for (r of drafted(); track r.id) {
                  <div class="rounded-lg border border-line p-3 text-[13px]">
                    <div class="flex items-center justify-between gap-2"><span class="font-medium">{{ r.name }}</span><veil-action [action]="r.action" /></div>
                    <div class="text-xs text-muted mt-1">{{ describe(r) }}</div>
                  </div>
                }
                @for (n of notes(); track n) { <div class="text-xs text-warn">{{ n }}</div> }
                @if (drafted().length && auth.isAdmin()) { <button class="btn btn-primary btn-sm" (click)="adopt()">Add {{ drafted().length }} rule(s) to the draft</button> }
              </div>
            }
          </section>

          <section class="card p-5">
            <h2 class="font-semibold mb-1">Dry run</h2>
            <p class="text-xs text-muted mb-3">Test text against the draft above, before publishing.</p>
            <textarea class="textarea font-mono text-[12.5px]" rows="4" [(ngModel)]="sample"></textarea>
            <div class="flex gap-2 mt-2">
              <select class="select" [(ngModel)]="sampleTeam" aria-label="Team">@for (t of teams(); track t.id) { <option [value]="t.id">{{ t.name }}</option> }</select>
              <select class="select" [(ngModel)]="sampleSource" aria-label="Channel"><option value="api">App</option><option value="extension">Browser</option></select>
              <button class="btn btn-ghost" (click)="dryRun()">Run</button>
            </div>
            @if (dry(); as d) {
              <div class="mt-4 text-[13px]">
                <div class="flex items-center gap-2 mb-2"><veil-action [action]="d.action" /><span class="text-xs text-muted">{{ d.findings.length }} findings · {{ micros(d.micros) }}</span></div>
                <div class="rounded-lg bg-[#fbfbf9] border border-line p-3"><veil-highlight [text]="sampleRun" [spans]="drySpans()" /></div>
                <ul class="mt-2 text-xs text-ink-2 list-disc pl-4">@for (r of d.reasons; track r) { <li>{{ r }}</li> }</ul>
              </div>
            }
          </section>

          <section class="card p-5">
            <h2 class="font-semibold mb-3">History</h2>
            <ul class="flex flex-col divide-y divide-line text-[13px]">
              @for (v of versions().slice(0, 8); track v.version) {
                <li class="py-2 flex items-center gap-2">
                  <span class="font-mono text-xs w-8">v{{ v.version }}</span>
                  <div class="min-w-0 flex-1"><div class="truncate">{{ v.note || 'No note' }}</div><div class="text-[11px] text-muted">{{ v.createdBy }} · {{ ago(v.createdAt) }} · {{ v.rules }} rules</div></div>
                  @if (v.active) { <span class="text-[11px] text-protect font-medium">Active</span> }
                  @else if (auth.isAdmin()) { <button class="btn btn-ghost btn-sm" (click)="rollback(v.version)">Restore</button> }
                </li>
              }
            </ul>
          </section>
        </div>
      </div>
    } @else { <div class="skeleton h-96"></div> }`,
})
export class Policies {
  private api = inject(Api);
  private toasts = inject(Toasts);
  auth = inject(AuthService);
  live = signal<Policy | null>(null);
  draft = signal<Policy | null>(null);
  dirtyFlag = signal(false);
  catalogue = signal<CatalogueEntry[]>([]);
  teams = signal<Team[]>([]);
  models = signal<string[]>([]);
  versions = signal<any[]>([]);
  editing = signal(-1);
  publishing = signal(false);
  drafting = signal(false);
  drafted = signal<Rule[]>([]);
  notes = signal<string[]>([]);
  copilotMode = signal('');
  dry = signal<ScanResult | null>(null);
  note = '';
  instruction = '';
  sample = 'Customer Meera Iyer (meera.iyer@outlook.com) shared Aadhaar 2345 6789 0124. Also our key is sk-proj-Ab3dE5fG7hJ9kL1mN3pQ5rS7tU9vWx';
  sampleRun = '';
  sampleTeam = 'support';
  sampleSource = 'api';
  actions = ACTIONS;
  ago = ago; micros = micros;

  dirty = computed(() => this.dirtyFlag());
  grouped = computed(() => {
    const m = new Map<string, CatalogueEntry[]>();
    for (const c of this.catalogue()) m.set(c.category, [...(m.get(c.category) ?? []), c]);
    return [...m.entries()].map(([category, types]) => ({ category, types }));
  });
  drySpans = computed<Span[]>(() => (this.dry()?.findings ?? []).filter(f => !['SOURCE_CODE', 'CONFIDENTIAL_TOPIC', 'PROMPT_INJECTION'].includes(f.type))
    .map(f => ({ start: f.start, end: f.end, type: f.type, label: f.label, action: f.action })));

  constructor() {
    this.api.catalogue().subscribe({ next: c => this.catalogue.set(c), error: () => {} });
    this.api.teams().subscribe({ next: t => this.teams.set(t), error: () => {} });
    this.api.routes().subscribe({ next: r => this.models.set(r.map(x => x.model)), error: () => {} });
    this.load();
  }

  load() {
    this.api.policy().subscribe({ next: p => { this.live.set(p); this.draft.set(structuredClone(p)); this.dirtyFlag.set(false); }, error: e => this.toasts.error(e) });
    this.api.policyVersions().subscribe({ next: v => this.versions.set(v), error: () => {} });
  }

  touch() { this.dirtyFlag.set(true); this.draft.update(p => p ? { ...p } : p); }
  discard() { this.draft.set(structuredClone(this.live()!)); this.dirtyFlag.set(false); this.editing.set(-1); }
  toggle(i: number) { const p = this.draft()!; p.rules[i].enabled = !p.rules[i].enabled; this.touch(); }
  edit(i: number) { this.editing.set(this.editing() === i ? -1 : i); }
  remove(i: number) { this.draft()!.rules.splice(i, 1); this.editing.set(-1); this.touch(); }

  toggleIn(list: string[], v: string) {
    const i = list.indexOf(v);
    if (i >= 0) list.splice(i, 1); else list.push(v);
    this.touch();
  }

  addRule() {
    const p = this.draft()!;
    p.rules.push({ id: 'rule-' + Math.random().toString(36).slice(2, 8), name: 'New rule', types: [], categories: ['CONTACT'], teams: [], sources: [],
      minConfidence: 0.75, action: 'REDACT', enabled: true });
    this.editing.set(p.rules.length - 1);
    this.touch();
  }

  publish() {
    this.publishing.set(true);
    this.api.publishPolicy(this.draft()!, this.note).subscribe({
      next: p => { this.publishing.set(false); this.note = ''; this.toasts.show(`Policy v${p.version} published. Proxies apply it within a second.`); this.load(); },
      error: e => { this.publishing.set(false); this.toasts.error(e); },
    });
  }

  rollback(v: number) {
    this.api.rollback(v).subscribe({ next: p => { this.toasts.show(`Restored v${v} as v${p.version}`); this.load(); }, error: e => this.toasts.error(e) });
  }

  draftRules() {
    this.drafting.set(true);
    this.api.copilot(this.instruction).subscribe({
      next: d => { this.drafting.set(false); this.drafted.set(d.rules); this.notes.set(d.notes); this.copilotMode.set(d.mode === 'rules' ? 'built-in parser' : d.mode); },
      error: e => { this.drafting.set(false); this.toasts.error(e); },
    });
  }

  adopt() {
    const p = this.draft()!;
    for (const r of this.drafted()) p.rules.push({ ...r, id: r.id + '-' + Math.random().toString(36).slice(2, 6) });
    this.drafted.set([]);
    this.notes.set([]);
    this.touch();
    this.toasts.show('Rules added to the draft. Review them, then publish.', 'info');
  }

  dryRun() {
    this.sampleRun = this.sample;
    this.api.scan(this.sample, this.sampleTeam, this.sampleSource, this.draft()!).subscribe({ next: r => this.dry.set(r), error: e => this.toasts.error(e) });
  }

  typeLabel(t: string) { return this.catalogue().find(c => c.type === t)?.label ?? t; }
  categoryLabel(c: string) { return CATEGORY_LABEL[c] ?? c; }
  teamNames(ids: string[]) { return ids.map(id => this.teams().find(t => t.id === id)?.name ?? id).join(', '); }
  sourceNames(s: string[]) { return s.map(x => x === 'api' ? 'Apps' : 'Browser').join(', '); }
  describe(r: Rule) {
    const what = [...r.categories.map(c => this.categoryLabel(c)), ...r.types.map(t => this.typeLabel(t))].join(', ');
    return `${what} · ${r.teams.length ? this.teamNames(r.teams) : 'all teams'} · ${r.sources.length ? this.sourceNames(r.sources) : 'apps and browser'}`;
  }
}
