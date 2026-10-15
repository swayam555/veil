import { describe, expect, it } from 'vitest';
import { ago, change, compact, humanize, micros, ms, percent, usd } from './format';

describe('format', () => {
  it('formats micro-dollars', () => {
    expect(usd(0)).toBe('$0.00');
    expect(usd(1_500_000)).toBe('$1.50');
    expect(usd(4729)).toBe('$0.0047');
    expect(usd(2_000_000, 0)).toBe('$2');
  });

  it('compacts large numbers', () => {
    expect(compact(950)).toBe('950');
    expect(compact(1250)).toBe('1.3k');
    expect(compact(12_400)).toBe('12k');
    expect(compact(3_400_000)).toBe('3.4M');
    expect(compact(undefined)).toBe('0');
  });

  it('percent and change handle zero', () => {
    expect(percent(1, 4)).toBe('25%');
    expect(percent(1, 0)).toBe('0%');
    expect(change(150, 100)).toBeCloseTo(0.5);
    expect(change(5, 0)).toBeNull();
  });

  it('relative time', () => {
    const now = Date.parse('2026-10-15T10:00:00Z');
    expect(ago('2026-10-15T09:59:58Z', now)).toBe('just now');
    expect(ago('2026-10-15T09:59:15Z', now)).toBe('45s ago');
    expect(ago('2026-10-15T09:59:00Z', now)).toBe('1m ago');
    expect(ago('2026-10-15T09:30:00Z', now)).toBe('30m ago');
    expect(ago('2026-10-15T05:00:00Z', now)).toBe('5h ago');
    expect(ago('2026-10-12T10:00:00Z', now)).toBe('3d ago');
    expect(ago(null)).toBe('—');
  });

  it('durations and labels', () => {
    expect(ms(12.4)).toBe('12 ms');
    expect(ms(1530)).toBe('1.53 s');
    expect(micros(420)).toBe('420 µs');
    expect(micros(2300)).toBe('2.3 ms');
    expect(humanize('API_KEY')).toBe('Api key');
  });
});
