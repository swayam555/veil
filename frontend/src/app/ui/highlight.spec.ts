import { describe, expect, it } from 'vitest';
import { segments } from './highlight';

describe('segments', () => {
  const text = 'Mail priya@acme.in or call 9876543210';
  it('splits text around findings in order', () => {
    const parts = segments(text, [
      { start: 27, end: 37, type: 'PHONE', label: 'Phone', action: 'REDACT' },
      { start: 5, end: 18, type: 'EMAIL', label: 'Email', action: 'REDACT' },
    ]);
    expect(parts.map(p => p.text)).toEqual(['Mail ', 'priya@acme.in', ' or call ', '9876543210']);
    expect(parts[1].span?.type).toBe('EMAIL');
    expect(parts.map(p => p.text).join('')).toBe(text);
  });

  it('skips overlapping and out-of-range spans without losing text', () => {
    const parts = segments(text, [
      { start: 5, end: 18, type: 'EMAIL', label: 'Email', action: 'REDACT' },
      { start: 10, end: 20, type: 'X', label: 'X', action: 'WARN' },
      { start: 30, end: 999, type: 'Y', label: 'Y', action: 'WARN' },
    ]);
    expect(parts.map(p => p.text).join('')).toBe(text);
    expect(parts.filter(p => p.span).length).toBe(1);
  });
});
