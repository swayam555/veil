import { Component, computed, input } from '@angular/core';

/** A KPI tile: one big number, an optional comparison with the previous period, an optional sparkline. */
@Component({
  selector: 'veil-stat',
  template: `
    <div class="card p-4 h-full flex flex-col gap-2">
      <div class="flex items-center justify-between gap-2">
        <span class="text-[13px] text-muted">{{ label() }}</span>
        @if (delta() !== null) {
          <span class="text-[11px] font-medium tabular" [class]="deltaClass()">{{ deltaText() }}</span>
        }
      </div>
      <div class="text-[28px] leading-none font-semibold tracking-tight tabular">{{ value() }}</div>
      @if (hint()) { <div class="text-xs text-muted">{{ hint() }}</div> }
      @if (spark().length > 1) {
        <svg class="mt-auto w-full h-8" viewBox="0 0 100 24" preserveAspectRatio="none" aria-hidden="true">
          <path [attr.d]="area()" [attr.fill]="color()" fill-opacity="0.1" />
          <path [attr.d]="line()" fill="none" [attr.stroke]="color()" stroke-width="1.5" vector-effect="non-scaling-stroke" />
        </svg>
      }
    </div>`,
})
export class Stat {
  label = input.required<string>();
  value = input.required<string>();
  hint = input<string>('');
  delta = input<number | null>(null);
  /** When true, a rise is bad (e.g. blocked requests). */
  riseIsBad = input(false);
  spark = input<number[]>([]);
  color = input('#4f46e5');

  deltaText = computed(() => {
    const d = this.delta();
    if (d === null) return '';
    return (d >= 0 ? '▲ ' : '▼ ') + Math.abs(d * 100).toFixed(0) + '% vs prev.';
  });
  deltaClass = computed(() => {
    const d = this.delta() ?? 0;
    const bad = this.riseIsBad() ? d > 0 : d < 0;
    return Math.abs(d) < 0.005 ? 'text-muted' : bad ? 'text-block' : 'text-protect';
  });
  private points = computed(() => {
    const s = this.spark();
    const max = Math.max(1, ...s);
    return s.map((v, i) => [(i / (s.length - 1)) * 100, 22 - (v / max) * 20] as const);
  });
  line = computed(() => this.points().map(([x, y], i) => `${i ? 'L' : 'M'}${x.toFixed(2)},${y.toFixed(2)}`).join(''));
  area = computed(() => {
    const p = this.points();
    return p.length ? `${this.line()}L100,24L0,24Z` : '';
  });
}
