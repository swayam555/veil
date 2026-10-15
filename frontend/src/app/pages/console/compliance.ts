import { Component, effect, inject, signal, untracked } from '@angular/core';
import { Api } from '../../core/api';
import { compact, percent } from '../../core/format';
import { PageHeader, RangePicker } from '../../ui/page';
import { Toasts } from '../../ui/toast';

@Component({
  selector: 'veil-compliance',
  imports: [PageHeader, RangePicker],
  template: `
    <veil-page-header title="Compliance evidence"
      subtitle="What was protected under each regulation, and how Veil keeps its own records safe. Print this page or export the audit log for your auditor.">
      <veil-range [(value)]="range" [options]="['24h', '7d', '30d']" />
      <button class="btn btn-ghost" (click)="print()">Print</button>
    </veil-page-header>
    @if (data(); as d) {
      <div class="grid grid-cols-1 lg:grid-cols-[minmax(0,1fr)_380px] gap-5 items-start">
        <section class="card overflow-x-auto">
          <table class="table">
            <thead><tr><th>Regulation</th><th>Data types seen</th><th class="text-right">Detected</th><th class="text-right">Protected</th><th class="w-40">Coverage</th></tr></thead>
            <tbody>
              @for (r of d.regulations; track r.regulation) {
                <tr>
                  <td class="font-medium whitespace-nowrap">{{ r.regulation }}</td>
                  <td class="text-[13px] text-ink-2">{{ r.types.join(', ') }}</td>
                  <td class="text-right tabular">{{ compact(r.detected) }}</td>
                  <td class="text-right tabular">{{ compact(r.protected) }}</td>
                  <td><div class="flex items-center gap-2"><div class="h-1.5 flex-1 rounded-full bg-stone-100 overflow-hidden">
                    <div class="h-full bg-protect rounded-full" [style.width]="percent(r.protected, r.detected)"></div></div>
                    <span class="text-xs tabular w-9 text-right">{{ percent(r.protected, r.detected) }}</span></div></td>
                </tr>
              }
            </tbody>
          </table>
          <p class="text-xs text-muted px-5 py-3 border-t border-line">"Protected" means redacted, blocked, kept on-prem or masked. The rest were flagged or allowed by policy (for example, source code for Engineering).</p>
        </section>
        <div class="flex flex-col gap-4">
          <section class="card p-5">
            <h2 class="font-semibold mb-3">Controls in place</h2>
            <ul class="flex flex-col gap-2.5 text-[13px] text-ink-2">
              @for (c of d.controls; track c) { <li class="flex gap-2"><span class="text-protect mt-0.5">✓</span><span>{{ c }}</span></li> }
            </ul>
          </section>
          <section class="card p-5 text-sm">
            <h2 class="font-semibold mb-3">In this period</h2>
            <dl class="grid grid-cols-2 gap-3">
              <div><dt class="text-xs text-muted">Requests</dt><dd class="tabular font-semibold">{{ compact(d.totals.requests) }}</dd></div>
              <div><dt class="text-xs text-muted">Blocked</dt><dd class="tabular font-semibold">{{ compact(d.totals.blocked) }}</dd></div>
              <div><dt class="text-xs text-muted">Policy versions in force</dt><dd class="tabular">{{ d.policyVersions.length ? 'v' + d.policyVersions.join(', v') : '—' }}</dd></div>
              <div><dt class="text-xs text-muted">Incidents</dt><dd class="tabular">@for (i of d.incidents; track i.status) { {{ i.count }} {{ i.status.toLowerCase().replace('_', ' ') }}<br/> }</dd></div>
            </dl>
          </section>
        </div>
      </div>
    } @else { <div class="skeleton h-80"></div> }`,
})
export class Compliance {
  private api = inject(Api);
  private toasts = inject(Toasts);
  range = signal('30d');
  data = signal<any>(null);
  compact = compact; percent = percent;

  constructor() {
    effect(() => {
      const r = this.range();
      untracked(() => this.api.compliance(r).subscribe({ next: d => this.data.set(d), error: e => this.toasts.error(e) }));
    });
  }

  print() {
    window.print();
  }
}
