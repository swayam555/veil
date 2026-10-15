import { Component, computed, input } from '@angular/core';

export interface Span { start: number; end: number; type: string; label: string; action: string; }

const STYLE: Record<string, string> = {
  BLOCK: 'bg-block-soft text-[#8d1f24] decoration-block',
  REDACT: 'bg-protect-soft text-[#08584f] decoration-protect',
  ROUTE_LOCAL: 'bg-local-soft text-[#1e40af] decoration-local',
  WARN: 'bg-warn-soft text-[#7a4d05] decoration-warn',
  ALLOW: 'bg-stone-100 text-ink-2 decoration-stone-400',
};

export function segments(text: string, spans: Span[]): { text: string; span?: Span }[] {
  const sorted = [...spans].filter(s => s.end > s.start && s.end <= text.length).sort((a, b) => a.start - b.start);
  const out: { text: string; span?: Span }[] = [];
  let pos = 0;
  for (const s of sorted) {
    if (s.start < pos) continue;
    if (s.start > pos) out.push({ text: text.slice(pos, s.start) });
    out.push({ text: text.slice(s.start, s.end), span: s });
    pos = s.end;
  }
  if (pos < text.length) out.push({ text: text.slice(pos) });
  return out;
}

/** Text with each finding marked by the colour of its decision, a dotted underline and a tooltip naming it. */
@Component({
  selector: 'veil-highlight',
  template: `<div class="whitespace-pre-wrap break-words leading-relaxed">@for (seg of parts(); track $index) {@if (seg.span; as s) {<mark
    class="rounded px-0.5 underline decoration-dotted underline-offset-4 decoration-2 {{ style(s.action) }}"
    [title]="s.label + ' — ' + s.action.toLowerCase()">{{ seg.text }}</mark>} @else {<span>{{ seg.text }}</span>}}</div>`,
})
export class Highlight {
  text = input.required<string>();
  spans = input<Span[]>([]);
  parts = computed(() => segments(this.text(), this.spans()));
  style(a: string) {
    return STYLE[a] ?? STYLE['ALLOW'];
  }
}

/** Renders placeholders like [EMAIL_1] as small tokens, to show what the model actually saw. */
@Component({
  selector: 'veil-placeholders',
  template: `<div class="whitespace-pre-wrap break-words leading-relaxed">@for (p of parts(); track $index) {@if (p.ph) {<span
    class="inline-block rounded-md bg-ink text-white font-mono text-[11px] px-1.5 py-px mx-px align-[1px]">{{ p.text }}</span>} @else {<span>{{ p.text }}</span>}}</div>`,
})
export class Placeholders {
  text = input.required<string>();
  parts = computed(() => {
    const out: { text: string; ph: boolean }[] = [];
    const re = /\[[A-Z][A-Z_]*?_\d{1,4}]|\[REDACTED:[A-Z_]+]/g;
    let last = 0;
    for (const m of this.text().matchAll(re)) {
      if (m.index! > last) out.push({ text: this.text().slice(last, m.index), ph: false });
      out.push({ text: m[0], ph: true });
      last = m.index! + m[0].length;
    }
    if (last < this.text().length) out.push({ text: this.text().slice(last), ph: false });
    return out;
  });
}
