/** Formatting helpers shared by every screen. Pure functions, unit-tested. */

export function usd(micros: number | null | undefined, digits?: number): string {
  const v = (micros ?? 0) / 1_000_000;
  const d = digits ?? (v !== 0 && Math.abs(v) < 1 ? 4 : 2);
  return '$' + v.toLocaleString('en-US', { minimumFractionDigits: d, maximumFractionDigits: d });
}

export function compact(n: number | null | undefined): string {
  const v = n ?? 0;
  if (Math.abs(v) < 1000) return String(Math.round(v));
  if (Math.abs(v) < 1_000_000) return (v / 1000).toFixed(v < 10_000 ? 1 : 0).replace(/\.0$/, '') + 'k';
  return (v / 1_000_000).toFixed(1).replace(/\.0$/, '') + 'M';
}

export function percent(part: number, whole: number, digits = 0): string {
  if (!whole) return '0%';
  return ((part / whole) * 100).toFixed(digits) + '%';
}

/** Relative change vs a previous period, or null when there is nothing to compare with. */
export function change(now: number, before: number): number | null {
  if (!before) return null;
  return (now - before) / before;
}

export function ago(iso: string | number | Date | null | undefined, now = Date.now()): string {
  if (iso === null || iso === undefined) return '—';
  const t = typeof iso === 'number' ? iso : new Date(iso).getTime();
  const s = Math.max(0, Math.round((now - t) / 1000));
  if (s < 5) return 'just now';
  if (s < 60) return `${s}s ago`;
  const m = Math.round(s / 60);
  if (m < 60) return `${m}m ago`;
  const h = Math.round(m / 60);
  if (h < 24) return `${h}h ago`;
  return `${Math.round(h / 24)}d ago`;
}

export function time(iso: string | null | undefined): string {
  if (!iso) return '—';
  return new Date(iso).toLocaleString('en-IN', { day: 'numeric', month: 'short', hour: '2-digit', minute: '2-digit', second: '2-digit' });
}

export function ms(v: number | null | undefined): string {
  const x = v ?? 0;
  return x >= 1000 ? (x / 1000).toFixed(2) + ' s' : Math.round(x) + ' ms';
}

export function micros(v: number | null | undefined): string {
  const x = v ?? 0;
  return x >= 1000 ? (x / 1000).toFixed(1) + ' ms' : Math.round(x) + ' µs';
}

/** "API_KEY" -> "Api key" for unknown enum values; known types use labels from the catalogue. */
export function humanize(s: string | null | undefined): string {
  if (!s) return '';
  const t = s.replace(/_/g, ' ').toLowerCase();
  return t.charAt(0).toUpperCase() + t.slice(1);
}
