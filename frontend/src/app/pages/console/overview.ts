import { Component, OnDestroy, OnInit, computed, effect, inject, signal, untracked } from '@angular/core';
import { RouterLink } from '@angular/router';
import { Api } from '../../core/api';
import { LiveService } from '../../core/live.service';
import { ago, change, compact, micros, ms, percent, usd } from '../../core/format';
import { ActionBadge, Chip } from '../../ui/badges';
import { AreaChart, SeriesDef } from '../../ui/area-chart';
import { BarList, BarItem } from '../../ui/bar-list';
import { PageHeader, RangePicker } from '../../ui/page';
import { Stat } from '../../ui/stat';
import { Toasts } from '../../ui/toast';
import { AuthService } from '../../core/auth.service';

@Component({
  selector: 'veil-overview',
  imports: [PageHeader, RangePicker, Stat, AreaChart, BarList, ActionBadge, Chip, RouterLink],
  template: `
    <veil-page-header title="Overview" subtitle="What your organisation sent to AI, what Veil caught, and what it cost.">
      @if (auth.isAdmin()) {
        <button class="btn btn-ghost" (click)="toggleSimulator()" [title]="'Sends realistic traffic through the real proxy'">
          <span class="size-2 rounded-full" [class]="sim()?.running ? 'bg-protect pulse-dot' : 'bg-stone-300'"></span>
          {{ sim()?.running ? 'Simulated traffic on' : 'Start simulated traffic' }}
        </button>
      }
      <veil-range [(value)]="range" />
    </veil-page-header>

    @if (!data()) {
      <div class="grid grid-cols-2 lg:grid-cols-4 gap-4">@for (i of [1,2,3,4]; track i) { <div class="skeleton h-32"></div> }</div>
    } @else {
      @let t = data().totals;
      @let p = data().previous;
      <div class="grid grid-cols-2 xl:grid-cols-4 gap-4 fade-up">
        <veil-stat label="AI requests" [value]="compact(t.requests)" [delta]="change(t.requests, p.requests)"
                   [hint]="percent(t.cacheHits, t.requests) + ' served from semantic cache'" [spark]="spark('requests')" color="#6b7280" />
        <veil-stat label="Sensitive items protected" [value]="compact(t.protected)" [delta]="change(t.protected, p.protected)"
                   [hint]="compact(t.findings) + ' found · ' + compact(t.redacted) + ' requests redacted'" [spark]="spark('protected')" color="#0e9384" />
        <veil-stat label="Requests blocked" [value]="compact(t.blocked)" [delta]="change(t.blocked, p.blocked)" [riseIsBad]="true"
                   [hint]="compact(t.routedLocal) + ' kept on-prem · ' + compact(t.warned) + ' flagged'" [spark]="spark('blocked')" color="#d1373d" />
        <veil-stat label="Spend" [value]="usd(t.costMicros)" [delta]="change(t.costMicros, p.costMicros)" [riseIsBad]="true"
                   [hint]="compact(t.tokensIn + t.tokensOut) + ' tokens'" [spark]="spark('costMicros')" color="#4f46e5" />
      </div>

      <div class="grid grid-cols-1 xl:grid-cols-[1fr_380px] gap-4 mt-4">
        <section class="card p-5 min-w-0">
          <div class="flex items-center justify-between mb-2">
            <h2 class="font-semibold">Traffic and protection</h2>
            <span class="text-xs text-muted">per {{ bucketLabel() }}</span>
          </div>
          <veil-area-chart [data]="data().series" [series]="series" ariaLabel="Requests, protected requests and blocked requests over time" />
        </section>
        <section class="card p-5 flex flex-col min-h-[320px]">
          <div class="flex items-center justify-between mb-3">
            <h2 class="font-semibold">Live requests</h2>
            <a routerLink="../activity" class="text-xs text-brand hover:underline">Activity →</a>
          </div>
          <ul class="flex-1 overflow-y-auto -mx-2 max-h-[300px]">
            @for (e of live.events().slice(0, 14); track e.id) {
              <li class="fade-up flex items-center gap-2 px-2 py-1.5 rounded-lg hover:bg-stone-50 text-[13px]">
                <veil-action [action]="e.action" />
                <span class="truncate flex-1 text-ink-2">
                  {{ e.team }} · {{ e.source === 'extension' ? (e.kind === 'VISIT' ? 'visited ' : 'pasted on ') + e.site : e.model }}
                  @if (e.types.length) { <span class="text-muted">· {{ e.types.slice(0, 2).join(', ').toLowerCase() }}</span> }
                </span>
                <span class="text-[11px] text-muted tabular shrink-0">{{ ago(e.at, now()) }}</span>
              </li>
            } @empty {
              <li class="text-sm text-muted text-center py-10 px-4">Waiting for traffic…
                @if (auth.isAdmin()) { <br/>Start simulated traffic above, or send a request from the Playground. }</li>
            }
          </ul>
        </section>
      </div>

      <div class="grid lg:grid-cols-3 gap-4 mt-4">
        <section class="card p-5">
          <h2 class="font-semibold mb-1">What was found</h2>
          <p class="text-xs text-muted mb-4">Darker part: protected (redacted, blocked or kept on-prem)</p>
          <veil-bar-list [items]="types()" color="#0e9384" lighter="#bfe7e1" secondaryLabel="protected" />
        </section>
        <section class="card p-5">
          <h2 class="font-semibold mb-1">Teams</h2>
          <p class="text-xs text-muted mb-4">Requests, with blocked shown darker</p>
          <veil-bar-list [items]="teams()" color="#d1373d" lighter="#d9d8ff" secondaryLabel="blocked" />
        </section>
        <section class="card p-5">
          <h2 class="font-semibold mb-4">Performance</h2>
          @let l = data().latency;
          <dl class="grid grid-cols-2 gap-y-4 gap-x-6 text-sm">
            <div><dt class="text-muted text-xs">Veil overhead p95</dt><dd class="text-xl font-semibold tabular">{{ ms(l.overheadP95Ms) }}</dd></div>
            <div><dt class="text-muted text-xs">Detection p50 / p95</dt><dd class="text-xl font-semibold tabular">{{ micros(l.detectP50Micros) }}<span class="text-muted text-sm"> / {{ micros(l.detectP95Micros) }}</span></dd></div>
            <div><dt class="text-muted text-xs">End-to-end p50</dt><dd class="text-xl font-semibold tabular">{{ ms(l.p50) }}</dd></div>
            <div><dt class="text-muted text-xs">Open incidents</dt><dd class="text-xl font-semibold tabular"><a routerLink="../incidents" class="hover:underline">{{ data().openIncidents }}</a></dd></div>
          </dl>
          <div class="mt-5 pt-4 border-t border-line">
            <div class="text-xs text-muted mb-2">Models</div>
            <div class="flex flex-wrap gap-1.5">
              @for (m of data().models; track m.model) { <veil-chip>{{ m.model }} · {{ m.requests }}</veil-chip> }
            </div>
          </div>
        </section>
      </div>
    }`,
})
export class Overview implements OnInit, OnDestroy {
  private api = inject(Api);
  private toasts = inject(Toasts);
  auth = inject(AuthService);
  live = inject(LiveService);
  range = signal(localStorage.getItem('veil.range') ?? '1h');
  data = signal<any>(null);
  sim = signal<any>(null);
  now = signal(Date.now());
  private timer?: ReturnType<typeof setInterval>;
  private ticks = 0;

  readonly series: SeriesDef[] = [
    { key: 'requests', label: 'All requests', color: '#94a3b8', fill: true },
    { key: 'protectedRequests', label: 'Redacted or kept on-prem', color: '#0e9384' },
    { key: 'warned', label: 'Flagged', color: '#c27c0e' },
    { key: 'blocked', label: 'Blocked', color: '#d1373d' },
  ];

  compact = compact; usd = usd; percent = percent; change = change; ago = ago; ms = ms; micros = micros;

  constructor() {
    effect(() => {
      const r = this.range();
      localStorage.setItem('veil.range', r);
      untracked(() => this.load());
    });
  }

  types = computed<BarItem[]>(() => (this.data()?.findingTypes ?? []).slice(0, 8)
    .map((t: any) => ({ label: t.label, value: Number(t.count), secondary: Number(t.protected ?? 0) })));
  teams = computed<BarItem[]>(() => (this.data()?.teams ?? []).filter((t: any) => t.teamId !== '-')
    .map((t: any) => ({ label: t.team, value: Number(t.requests), secondary: Number(t.blocked), hint: usd(t.costMicros) + ' spend' })));
  bucketLabel = computed(() => ({ '1h': 'minute', '6h': '5 minutes', '24h': '15 minutes', '7d': '2 hours', '30d': '12 hours' } as any)[this.range()]);

  spark(key: string): number[] {
    return (this.data()?.series ?? []).map((p: any) => Number(p[key]) || 0);
  }

  ngOnInit() {
    this.loadSim();
    this.timer = setInterval(() => {
      this.now.set(Date.now());
      if (++this.ticks % 5 === 0) this.load();
    }, 2000);
  }

  ngOnDestroy() {
    clearInterval(this.timer);
  }

  load() {
    this.api.overview(this.range()).subscribe({ next: d => this.data.set(d), error: e => this.toasts.error(e) });
  }

  loadSim() {
    this.api.simulator().subscribe({ next: s => this.sim.set(s), error: () => {} });
  }

  toggleSimulator() {
    const req = this.sim()?.running ? this.api.stopSimulator() : this.api.startSimulator(60);
    req.subscribe({
      next: s => {
        this.sim.set(s);
        this.toasts.show(s.running ? 'Simulated traffic started: about 60 requests a minute through the real proxy.' : 'Simulated traffic stopped.');
      },
      error: e => this.toasts.error(e),
    });
  }
}
