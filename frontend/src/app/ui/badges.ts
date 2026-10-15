import { Component, computed, input } from '@angular/core';

const ACTION: Record<string, { label: string; cls: string; dot: string }> = {
  ALLOW: { label: 'Allowed', cls: 'bg-stone-100 text-stone-700', dot: 'bg-stone-400' },
  WARN: { label: 'Flagged', cls: 'bg-warn-soft text-[#8a5806]', dot: 'bg-warn' },
  REDACT: { label: 'Redacted', cls: 'bg-protect-soft text-[#0a6a5f]', dot: 'bg-protect' },
  ROUTE_LOCAL: { label: 'Kept on-prem', cls: 'bg-local-soft text-[#1d4ed8]', dot: 'bg-local' },
  BLOCK: { label: 'Blocked', cls: 'bg-block-soft text-[#a3262b]', dot: 'bg-block' },
  MASKED: { label: 'Masked', cls: 'bg-protect-soft text-[#0a6a5f]', dot: 'bg-protect' },
};

/** A decision, always as colour + text (colour alone never carries meaning). */
@Component({
  selector: 'veil-action',
  template: `<span class="inline-flex items-center gap-1.5 rounded-full px-2 py-0.5 text-xs font-medium whitespace-nowrap {{ cfg().cls }}">
    <span class="size-1.5 rounded-full {{ cfg().dot }}"></span>{{ cfg().label }}</span>`,
})
export class ActionBadge {
  action = input.required<string>();
  cfg = computed(() => ACTION[this.action()] ?? ACTION['ALLOW']);
}

const SEVERITY: Record<string, string> = {
  LOW: 'text-stone-600 border-stone-300',
  MEDIUM: 'text-[#8a5806] border-[#e8c98f]',
  HIGH: 'text-[#a3262b] border-[#efb5b7]',
  CRITICAL: 'text-white bg-block border-block',
};

@Component({
  selector: 'veil-severity',
  template: `<span class="inline-flex items-center rounded-md border px-1.5 py-px text-[11px] font-semibold tracking-wide {{ cls() }}">{{ severity() }}</span>`,
})
export class SeverityBadge {
  severity = input.required<string>();
  cls = computed(() => SEVERITY[this.severity()] ?? SEVERITY['LOW']);
}

@Component({
  selector: 'veil-chip',
  template: `<span class="inline-flex items-center rounded-md bg-stone-100 px-1.5 py-0.5 font-mono text-[11px] text-ink-2 whitespace-nowrap"><ng-content /></span>`,
})
export class Chip {}
