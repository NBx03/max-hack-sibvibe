import { describe, expect, it } from 'vitest';
import { formatDuration, formatRate } from './MetricsCard';

describe('показатели согласования', () => {
  it('срок — коротко и в одну строку', () => {
    expect(formatDuration(null)).toBe('—');
    expect(formatDuration(0.4)).toBe('< 1 ч');
    expect(formatDuration(2.2)).toBe('2 ч');
    expect(formatDuration(47.6)).toBe('48 ч');
    expect(formatDuration(72)).toBe('3 дн');
  });

  it('доля — в процентах', () => {
    expect(formatRate(null)).toBe('—');
    expect(formatRate(0.254)).toBe('25%');
  });
});
