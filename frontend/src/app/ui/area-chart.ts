import { Component, ElementRef, computed, input, signal, viewChild } from '@angular/core';

export interface SeriesDef { key: string; label: string; color: string; fill?: boolean; }

/**
 * Time-series chart in plain SVG: a recessive grid, 2px lines, an optional neutral area for totals, a legend,
 * and a crosshair tooltip that reads every series at the hovered bucket. One y-axis only.
 */
@Component({
  selector: 'veil-area-chart',
  template: `
    <div class="flex flex-wrap items-center gap-x-4 gap-y-1 mb-3 text-xs text-ink-2">
      @for (s of series(); track s.key) {
        <span class="inline-flex items-center gap-1.5">
          <span class="inline-block w-3 h-[3px] rounded-full" [style.background]="s.color"></span>{{ s.label }}
        </span>
      }
    </div>
    <div class="relative" #box (mousemove)="move($event)" (mouseleave)="hover.set(-1)">
      <svg class="w-full block" [attr.viewBox]="'0 0 ' + W + ' ' + H" [style.height.px]="height()" preserveAspectRatio="none" role="img"
           [attr.aria-label]="ariaLabel()">
        @for (g of grid(); track g.y) {
          <line [attr.x1]="0" [attr.x2]="W" [attr.y1]="g.y" [attr.y2]="g.y" stroke="#ecebe5" stroke-width="1" vector-effect="non-scaling-stroke" />
        }
        @for (s of series(); track s.key) {
          @if (s.fill) {
            <path [attr.d]="areaPath(s.key)" [attr.fill]="s.color" fill-opacity="0.18" />
          } @else {
            <path [attr.d]="linePath(s.key)" fill="none" [attr.stroke]="s.color" stroke-width="2" stroke-linejoin="round"
                  stroke-linecap="round" vector-effect="non-scaling-stroke" />
          }
        }
        @if (hover() >= 0) {
          <line [attr.x1]="x(hover())" [attr.x2]="x(hover())" y1="0" [attr.y2]="H" stroke="#9ca3af" stroke-dasharray="3 3"
                stroke-width="1" vector-effect="non-scaling-stroke" />
        }
      </svg>
      <div class="flex justify-between text-[10px] text-muted tabular mt-1.5">
        @for (l of xLabels(); track $index) { <span>{{ l }}</span> }
      </div>
      <div class="absolute top-0 right-0 text-[10px] text-muted tabular">max {{ max() }}</div>
      @if (hover() >= 0 && data()[hover()]; as d) {
        <div class="absolute z-10 pointer-events-none rounded-lg bg-ink text-white text-xs px-3 py-2 shadow-lg min-w-36"
             [style.left.px]="tipLeft()" style="top: 8px">
          <div class="text-white/60 mb-1 tabular">{{ timeLabel(d['t']) }}</div>
          @for (s of series(); track s.key) {
            <div class="flex items-center justify-between gap-4">
              <span class="inline-flex items-center gap-1.5"><span class="size-2 rounded-full" [style.background]="s.color"></span>{{ s.label }}</span>
              <span class="tabular font-medium">{{ d[s.key] }}</span>
            </div>
          }
        </div>
      }
    </div>`,
})
export class AreaChart {
  data = input.required<Record<string, any>[]>();
  series = input.required<SeriesDef[]>();
  height = input(220);
  ariaLabel = input('Chart');
  readonly W = 1000;
  readonly H = 220;
  hover = signal(-1);
  private box = viewChild<ElementRef<HTMLDivElement>>('box');

  max = computed(() => {
    let m = 0;
    for (const d of this.data()) for (const s of this.series()) m = Math.max(m, Number(d[s.key]) || 0);
    return m === 0 ? 1 : m;
  });
  grid = computed(() => [0.25, 0.5, 0.75, 1].map(f => ({ y: this.H - f * (this.H - 8) })));
  x(i: number) {
    const n = this.data().length;
    return n <= 1 ? 0 : (i / (n - 1)) * this.W;
  }
  y(v: number) {
    return this.H - (v / this.max()) * (this.H - 8);
  }
  linePath(key: string) {
    return this.data().map((d, i) => `${i ? 'L' : 'M'}${this.x(i).toFixed(1)},${this.y(Number(d[key]) || 0).toFixed(1)}`).join('');
  }
  areaPath(key: string) {
    const n = this.data().length;
    if (!n) return '';
    return this.linePath(key) + `L${this.x(n - 1).toFixed(1)},${this.H}L0,${this.H}Z`;
  }
  xLabels = computed(() => {
    const d = this.data();
    if (d.length < 2) return [];
    const idx = [0, Math.floor(d.length / 3), Math.floor((2 * d.length) / 3), d.length - 1];
    return idx.map(i => this.timeLabel(d[i]['t']));
  });
  timeLabel(t: string) {
    const date = new Date(t);
    const span = this.data().length > 1 ? new Date(this.data()[this.data().length - 1]['t']).getTime() - new Date(this.data()[0]['t']).getTime() : 0;
    return span > 2 * 86400_000
      ? date.toLocaleString('en-IN', { day: 'numeric', month: 'short', hour: '2-digit' })
      : date.toLocaleTimeString('en-IN', { hour: '2-digit', minute: '2-digit' });
  }
  move(e: MouseEvent) {
    const el = this.box()?.nativeElement;
    if (!el) return;
    const r = el.getBoundingClientRect();
    const f = (e.clientX - r.left) / r.width;
    const n = this.data().length;
    this.hover.set(Math.max(0, Math.min(n - 1, Math.round(f * (n - 1)))));
  }
  tipLeft() {
    const el = this.box()?.nativeElement;
    const w = el?.getBoundingClientRect().width ?? 600;
    const px = (this.x(this.hover()) / this.W) * w;
    return px > w - 170 ? px - 165 : px + 12;
  }
}
