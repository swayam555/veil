import { Component, computed, input } from '@angular/core';

export interface BarItem { label: string; value: number; secondary?: number; hint?: string; }

/**
 * Ranked horizontal bars (magnitude by length). Optional secondary value is drawn as a darker segment inside the
 * bar (e.g. "protected" within "found"), with both numbers in text so colour is never the only cue.
 */
@Component({
  selector: 'veil-bar-list',
  template: `
    <ul class="flex flex-col gap-2.5">
      @for (b of items(); track b.label) {
        <li class="group" [title]="b.hint ?? ''">
          <div class="flex items-baseline justify-between gap-3 text-[13px] mb-1">
            <span class="truncate text-ink-2">{{ b.label }}</span>
            <span class="tabular text-ink font-medium shrink-0">
              {{ b.value }}@if (b.secondary !== undefined) {<span class="text-muted font-normal"> · {{ b.secondary }} {{ secondaryLabel() }}</span>}
            </span>
          </div>
          <div class="h-2 rounded-full bg-stone-100 overflow-hidden">
            <div class="h-full rounded-full relative transition-[width] duration-500" [style.width.%]="pct(b.value)" [style.background]="lighter()">
              @if (b.secondary !== undefined) {
                <div class="absolute inset-y-0 left-0 rounded-full" [style.width.%]="b.value ? (b.secondary / b.value) * 100 : 0" [style.background]="color()"></div>
              }
            </div>
          </div>
        </li>
      } @empty {
        <li class="text-sm text-muted py-6 text-center">{{ empty() }}</li>
      }
    </ul>`,
})
export class BarList {
  items = input.required<BarItem[]>();
  color = input('#4f46e5');
  lighter = input('#c7c9f6');
  secondaryLabel = input('');
  empty = input('No data yet');
  private max = computed(() => Math.max(1, ...this.items().map(i => i.value)));
  pct(v: number) {
    return Math.max(2, (v / this.max()) * 100);
  }
}
