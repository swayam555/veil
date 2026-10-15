import { Component, input, model } from '@angular/core';

@Component({
  selector: 'veil-page-header',
  template: `
    <header class="flex flex-wrap items-end justify-between gap-4 mb-6">
      <div class="min-w-0">
        <h1 class="text-[26px] font-semibold tracking-tight leading-tight">{{ title() }}</h1>
        @if (subtitle()) { <p class="text-muted text-sm mt-1 max-w-2xl">{{ subtitle() }}</p> }
      </div>
      <div class="flex items-center gap-2 flex-wrap"><ng-content /></div>
    </header>`,
})
export class PageHeader {
  title = input.required<string>();
  subtitle = input('');
}

@Component({
  selector: 'veil-range',
  template: `
    <div class="inline-flex rounded-lg border border-line-strong bg-white p-0.5" role="radiogroup" aria-label="Time range">
      @for (r of options(); track r) {
        <button role="radio" [attr.aria-checked]="value() === r" (click)="value.set(r)"
                class="px-2.5 h-7 text-xs font-medium rounded-md transition-colors"
                [class]="value() === r ? 'bg-ink text-white' : 'text-ink-2 hover:bg-stone-100'">{{ r }}</button>
      }
    </div>`,
})
export class RangePicker {
  value = model('24h');
  options = input(['1h', '6h', '24h', '7d', '30d']);
}

@Component({
  selector: 'veil-empty',
  template: `
    <div class="flex flex-col items-center justify-center text-center py-12 px-6">
      <div class="size-10 rounded-full bg-stone-100 flex items-center justify-center text-muted mb-3">○</div>
      <div class="font-medium">{{ title() }}</div>
      @if (text()) { <p class="text-sm text-muted mt-1 max-w-sm">{{ text() }}</p> }
      <div class="mt-4"><ng-content /></div>
    </div>`,
})
export class Empty {
  title = input.required<string>();
  text = input('');
}
