import { afterEach, describe, expect, it, vi } from 'vitest';
import {
  formatCredits,
  formatDay,
  formatDayRange,
  formatEnum,
  formatInstant,
  formatWindow,
  toInstant,
  todayInCompanyZone,
  toPickerValue,
} from './format';

describe('formatCredits', () => {
  it('shows two decimals with thousands separators', () => {
    expect(formatCredits(30)).toBe('30.00');
    expect(formatCredits(1120)).toBe('1,120.00');
    expect(formatCredits(5000)).toBe('5,000.00');
    expect(formatCredits(-12.5)).toBe('-12.50');
  });
});

describe('formatEnum', () => {
  it('turns an upper-case value into words', () => {
    expect(formatEnum('ALLOCATED')).toBe('Allocated');
    expect(formatEnum('RESOURCE_OWNER')).toBe('Resource owner');
  });
});

describe('formatInstant and formatWindow', () => {
  it('show instants in the company time zone, whatever offset they arrive with', () => {
    expect(formatInstant('2026-10-27T14:00:00-04:00')).toBe('Tue Oct 27, 14:00');
    expect(formatInstant('2026-10-27T18:00:00Z')).toBe('Tue Oct 27, 14:00');
  });

  it('show a window within one day with the day once', () => {
    const roomA = { start: '2026-10-27T14:00:00-04:00', end: '2026-10-27T16:00:00-04:00' };
    expect(formatWindow(roomA)).toBe('Tue Oct 27, 14:00 to 16:00');
  });

  it('show a window over several days with both days', () => {
    const gpus = { start: '2026-10-27T09:00:00-04:00', end: '2026-10-29T09:00:00-04:00' };
    expect(formatWindow(gpus)).toBe('Tue Oct 27, 09:00 to Thu Oct 29, 09:00');
  });
});

describe('toInstant', () => {
  it('reads a picker value in the company time zone, daylight saving time included', () => {
    expect(toInstant('2026-10-27T14:00')).toBe('2026-10-27T14:00:00-04:00');
    expect(toInstant('2026-11-03T09:00')).toBe('2026-11-03T09:00:00-05:00');
    expect(toInstant('2026-10-31T23:30')).toBe('2026-10-31T23:30:00-04:00');
  });

  it('round-trips with toPickerValue', () => {
    expect(toPickerValue('2026-10-27T18:00:00Z')).toBe('2026-10-27T14:00');
    expect(toPickerValue(toInstant('2026-11-03T09:00'))).toBe('2026-11-03T09:00');
  });
});

describe('formatDay and formatDayRange', () => {
  it('show days as they are, in no time zone', () => {
    expect(formatDay('2026-10-01')).toBe('Oct 1, 2026');
    expect(formatDayRange({ from: '2026-10-01', to: '2026-10-31' })).toBe('Oct 1, 2026 to Oct 31, 2026');
  });
});

describe('todayInCompanyZone', () => {
  afterEach(() => {
    vi.useRealTimers();
  });

  it('gives the date in New York, not in UTC', () => {
    vi.useFakeTimers({ toFake: ['Date'] });
    // 02:00 UTC on Oct 24 is still Oct 23 in New York.
    vi.setSystemTime(new Date('2026-10-24T02:00:00Z'));
    expect(todayInCompanyZone()).toBe('2026-10-23');
  });
});
