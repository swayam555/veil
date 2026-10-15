import { Component, computed, effect, inject, signal, untracked } from '@angular/core';
import { RouterLink } from '@angular/router';
import { Api } from '../../core/api';
import { ago } from '../../core/format';
import { ActionBadge, Chip } from '../../ui/badges';
import { BarList, BarItem } from '../../ui/bar-list';
import { Empty, PageHeader, RangePicker } from '../../ui/page';
import { Stat } from '../../ui/stat';
import { Toasts } from '../../ui/toast';

@Component({
  selector: 'veil-shadow-ai',
  imports: [PageHeader, RangePicker, Stat, BarList, ActionBadge, Chip, Empty, RouterLink],
  template: `
    <veil-page-header title="Shadow AI" subtitle="Which AI websites people actually use, and what they paste into them, as reported by the Veil browser extension.">
      <veil-range [(value)]="range" [options]="['24h', '7d', '30d']" />
    </veil-page-header>
    @if (data(); as d) {
      @if (d.totals.visits + d.totals.pastes === 0) {
        <div class="card"><veil-empty title="No browser activity yet" text="Install the extension for your teams, or start simulated traffic from the Overview.">
          <a routerLink="../integrations" class="btn btn-primary">Get the extension</a></veil-empty></div>
      } @else {
        <div class="grid grid-cols-2 xl:grid-cols-4 gap-4">
          <veil-stat label="AI sites in use" [value]="'' + d.totals.sites" [hint]="d.totals.users + ' people'" />
          <veil-stat label="Site visits" [value]="'' + d.totals.visits" />
          <veil-stat label="Pastes and messages checked" [value]="'' + d.totals.pastes" />
          <veil-stat label="Flagged or blocked" [value]="'' + d.totals.flagged" [hint]="d.totals.blocked + ' blocked'" />
        </div>
        <div class="grid lg:grid-cols-2 gap-4 mt-4">
          <section class="card p-5"><h2 class="font-semibold mb-1">Sites</h2><p class="text-xs text-muted mb-4">Visits plus pastes; darker part: flagged or blocked</p>
            <veil-bar-list [items]="sites()" color="#c27c0e" lighter="#efe2c8" secondaryLabel="risky" /></section>
          <section class="card p-5"><h2 class="font-semibold mb-1">Teams</h2><p class="text-xs text-muted mb-4">Pastes checked; darker part: flagged or blocked</p>
            <veil-bar-list [items]="teams()" color="#c27c0e" lighter="#efe2c8" secondaryLabel="risky" /></section>
        </div>
        <section class="card mt-4 overflow-x-auto">
          <div class="px-5 pt-5 pb-2 font-semibold">Recent risky pastes</div>
          <table class="table"><thead><tr><th>When</th><th>Who</th><th>Site</th><th>Found</th><th>Decision</th></tr></thead><tbody>
            @for (r of d.risky; track r.id) {
              <tr><td class="text-xs text-muted">{{ ago(r.at) }}</td><td class="text-[13px]">{{ r.actor }}<div class="text-[11px] text-muted">{{ r.team }}</div></td>
                <td class="text-[13px]">{{ r.site }}</td><td><div class="flex flex-wrap gap-1">@for (t of r.types; track t) { <veil-chip>{{ t }}</veil-chip> }</div></td>
                <td><veil-action [action]="r.action" /></td></tr>
            }
          </tbody></table>
        </section>
      }
    } @else { <div class="skeleton h-64"></div> }`,
})
export class ShadowAi {
  private api = inject(Api);
  private toasts = inject(Toasts);
  range = signal('7d');
  data = signal<any>(null);
  ago = ago;

  constructor() {
    effect(() => {
      const r = this.range();
      untracked(() => this.api.shadowAi(r).subscribe({ next: d => this.data.set(d), error: e => this.toasts.error(e) }));
    });
  }

  sites = computed<BarItem[]>(() => (this.data()?.sites ?? []).map((s: any) => ({ label: s.site, value: Number(s.visits) + Number(s.pastes),
    secondary: Number(s.flagged), hint: `${s.users} people · last seen ${ago(s.lastSeen)}` })));
  teams = computed<BarItem[]>(() => (this.data()?.teams ?? []).map((t: any) => ({ label: t.team, value: Number(t.pastes), secondary: Number(t.flagged) })));
}
