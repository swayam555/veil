import { Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Api, Incident } from '../../core/api';
import { AuthService } from '../../core/auth.service';
import { ago, time } from '../../core/format';
import { ActionBadge, Chip, SeverityBadge } from '../../ui/badges';
import { Placeholders } from '../../ui/highlight';
import { Empty, PageHeader } from '../../ui/page';
import { Toasts } from '../../ui/toast';

const STATUS_LABEL: Record<string, string> = { OPEN: 'Open', ACKNOWLEDGED: 'Acknowledged', RESOLVED: 'Resolved', FALSE_POSITIVE: 'False positive' };

@Component({
  selector: 'veil-incidents',
  imports: [FormsModule, PageHeader, ActionBadge, SeverityBadge, Chip, Placeholders, Empty],
  template: `
    <veil-page-header title="Incidents"
      subtitle="Blocked requests, critical data, prompt injection and risky pastes. Repeats within 30 minutes are folded into one incident." />

    <div class="flex flex-wrap gap-2 mb-4" role="tablist">
      @for (f of filters; track f.value) {
        <button role="tab" [attr.aria-selected]="status() === f.value" (click)="setStatus(f.value)"
          class="btn btn-sm" [class]="status() === f.value ? 'btn-primary' : 'btn-ghost'">
          {{ f.label }}@if (counts() && f.count) { <span class="tabular opacity-80">{{ counts()[f.count] }}</span> }
        </button>
      }
    </div>

    <div class="grid grid-cols-1 xl:grid-cols-[minmax(0,1fr)_minmax(0,1.1fr)] gap-4 items-start">
      <div class="card divide-y divide-line">
        @for (i of items(); track i.id) {
          <button class="w-full text-left p-4 hover:bg-stone-50 flex gap-3 items-start" [class.bg-brand-soft]="current()?.id === i.id" (click)="open(i.id)">
            <veil-severity [severity]="i.severity" />
            <div class="min-w-0 flex-1">
              <div class="font-medium text-[14px] leading-snug">{{ i.title }}</div>
              <div class="text-xs text-muted mt-1">{{ i.actor ?? 'App key' }} · {{ ago(i.lastAt) }}
                @if (i.occurrences > 1) { · <span class="text-ink-2 font-medium">{{ i.occurrences }}×</span> }</div>
            </div>
            <span class="text-[11px] rounded-full px-2 py-0.5 shrink-0" [class]="statusClass(i.status)">{{ statusLabel(i.status) }}</span>
          </button>
        } @empty {
          <veil-empty title="Nothing here" text="No incidents with this status. That's good news." />
        }
      </div>

      @if (current(); as c) {
        <section class="card p-5 xl:sticky xl:top-6 fade-up">
          <div class="flex items-start justify-between gap-3">
            <div><div class="flex items-center gap-2 mb-1"><veil-severity [severity]="c.severity" /><veil-action [action]="c.action" /></div>
              <h2 class="text-lg font-semibold leading-snug">{{ c.title }}</h2></div>
            <span class="text-[11px] rounded-full px-2 py-0.5 shrink-0" [class]="statusClass(c.status)">{{ statusLabel(c.status) }}</span>
          </div>
          <dl class="grid grid-cols-2 gap-3 mt-4 text-sm">
            <div><dt class="text-xs text-muted">Who</dt><dd>{{ c.actor ?? 'Application key' }}</dd></div>
            <div><dt class="text-xs text-muted">Team</dt><dd>{{ c.teamName }}</dd></div>
            <div><dt class="text-xs text-muted">First / last seen</dt><dd class="tabular">{{ time(c.firstAt) }} → {{ ago(c.lastAt) }}</dd></div>
            <div><dt class="text-xs text-muted">Occurrences · distinct values</dt><dd class="tabular">{{ c.occurrences }} · {{ c.distinctValues }}</dd></div>
          </dl>
          <div class="flex flex-wrap gap-1 mt-3">@for (t of c.types; track t) { <veil-chip>{{ t }}</veil-chip> }</div>

          @if (c.request?.redactedPrompt) {
            <div class="mt-4"><div class="eyebrow mb-1.5">Prompt (redacted)</div>
              <div class="rounded-xl bg-[#fbfbf9] border border-line p-3 text-[13px] max-h-40 overflow-y-auto"><veil-placeholders [text]="c.request.redactedPrompt" /></div></div>
          }

          <div class="mt-5">
            <div class="flex items-center justify-between mb-2">
              <div class="eyebrow">Explanation</div>
              <button class="btn btn-ghost btn-sm" (click)="explain(c.id)" [disabled]="explaining()">{{ explaining() ? 'Writing…' : c.explanation ? 'Regenerate' : 'Explain this incident' }}</button>
            </div>
            @if (c.explanation) {
              <div class="rounded-xl border border-line p-4 text-[13.5px] leading-relaxed whitespace-pre-wrap">{{ c.explanation }}</div>
              <div class="text-[11px] text-muted mt-1.5">{{ c.explainedBy === 'template' ? 'Written by Veil\\'s built-in explainer (no LLM configured)' : 'Written by ' + c.explainedBy + '. Only data types and the redacted prompt were shared with it.' }}</div>
            } @else {
              <p class="text-sm text-muted">Get a plain-language summary: what happened, why it matters, and what to do.</p>
            }
          </div>

          @if (auth.isAdmin()) {
            <div class="mt-5 pt-4 border-t border-line flex flex-col gap-3">
              <div><label class="label" for="notes">Notes</label>
                <textarea id="notes" class="textarea" rows="2" [(ngModel)]="notes" placeholder="What you found, what you did"></textarea></div>
              <div class="flex flex-wrap gap-2">
                @if (c.status === 'OPEN') { <button class="btn btn-ghost" (click)="update(c.id, 'ACKNOWLEDGED')">Acknowledge</button> }
                @if (c.status !== 'RESOLVED') { <button class="btn btn-primary" (click)="update(c.id, 'RESOLVED')">Resolve</button> }
                @if (c.status !== 'FALSE_POSITIVE') { <button class="btn btn-ghost" (click)="update(c.id, 'FALSE_POSITIVE')">False positive</button> }
                @if (c.status === 'RESOLVED' || c.status === 'FALSE_POSITIVE') { <button class="btn btn-ghost" (click)="update(c.id, 'OPEN')">Reopen</button> }
              </div>
            </div>
          } @else if (c.notes) {
            <div class="mt-4 text-sm"><div class="eyebrow mb-1">Notes</div>{{ c.notes }}</div>
          }
        </section>
      } @else {
        <div class="card p-10 text-center text-sm text-muted hidden xl:block">Select an incident to see details.</div>
      }
    </div>`,
})
export class Incidents {
  private api = inject(Api);
  private toasts = inject(Toasts);
  auth = inject(AuthService);
  status = signal('active');
  items = signal<Incident[]>([]);
  counts = signal<any>(null);
  current = signal<Incident | null>(null);
  explaining = signal(false);
  notes = '';
  ago = ago; time = time;
  filters = [
    { value: 'active', label: 'Needs attention', count: 'open' },
    { value: 'RESOLVED', label: 'Resolved', count: 'resolved' },
    { value: 'FALSE_POSITIVE', label: 'False positives', count: 'falsePositive' },
    { value: '', label: 'All', count: '' },
  ];

  constructor() {
    this.load();
  }

  setStatus(s: string) {
    this.status.set(s);
    this.load();
  }

  load() {
    this.api.incidents(this.status()).subscribe({
      next: r => {
        this.items.set(r.items);
        this.counts.set(r.counts);
        if (!this.current() && r.items.length && window.innerWidth >= 1280) this.open(r.items[0].id);
      },
      error: e => this.toasts.error(e),
    });
  }

  open(id: number) {
    this.api.incident(id).subscribe({ next: i => { this.current.set(i); this.notes = i.notes ?? ''; }, error: e => this.toasts.error(e) });
  }

  explain(id: number) {
    this.explaining.set(true);
    this.api.explain(id).subscribe({
      next: i => { this.current.set(i); this.explaining.set(false); },
      error: e => { this.explaining.set(false); this.toasts.error(e); },
    });
  }

  update(id: number, status: string) {
    this.api.updateIncident(id, { status, notes: this.notes }).subscribe({
      next: i => { this.current.set(i); this.toasts.show('Incident marked ' + this.statusLabel(status).toLowerCase()); this.load(); },
      error: e => this.toasts.error(e),
    });
  }

  statusLabel(s: string) {
    return STATUS_LABEL[s] ?? s;
  }

  statusClass(s: string) {
    return s === 'OPEN' ? 'bg-block-soft text-[#a3262b]' : s === 'ACKNOWLEDGED' ? 'bg-warn-soft text-[#7a4d05]'
      : s === 'RESOLVED' ? 'bg-protect-soft text-[#0a6a5f]' : 'bg-stone-100 text-ink-2';
  }
}
