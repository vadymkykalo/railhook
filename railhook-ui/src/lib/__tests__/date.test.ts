import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';

// Mock i18n before importing date utils
vi.mock('../../i18n', () => ({
  default: {
    language: 'en',
    t: (key: string, opts?: Record<string, unknown>) => {
      const translations: Record<string, string> = {
        'relativeTime.justNow': 'just now',
        'relativeTime.minutesAgo': `${opts?.count}m ago`,
        'relativeTime.hoursAgo': `${opts?.count}h ago`,
        'relativeTime.daysAgo': `${opts?.count}d ago`,
      };
      return translations[key] || key;
    },
  },
}));

import { formatDateTime, formatDate, formatRelativeTime, formatNumber } from '../date';

describe('date utilities', () => {
  describe('formatDateTime', () => {
    it('formats an ISO date string to locale string', () => {
      const result = formatDateTime('2025-01-15T14:30:15Z');
      expect(result).toBeTruthy();
      expect(typeof result).toBe('string');
      // Should contain year and time parts
      expect(result).toContain('2025');
    });
  });

  describe('formatDate', () => {
    it('formats an ISO date string to date-only string', () => {
      const result = formatDate('2025-06-20T10:00:00Z');
      expect(result).toContain('2025');
    });
  });

  describe('formatRelativeTime', () => {
    beforeEach(() => {
      vi.useFakeTimers();
    });

    it('returns "just now" for recent times', () => {
      const now = new Date('2025-03-01T12:00:00Z');
      vi.setSystemTime(now);
      const tenSecondsAgo = new Date(now.getTime() - 10_000).toISOString();
      expect(formatRelativeTime(tenSecondsAgo)).toBe('just now');
    });

    it('returns minutes ago', () => {
      const now = new Date('2025-03-01T12:00:00Z');
      vi.setSystemTime(now);
      const fiveMinAgo = new Date(now.getTime() - 5 * 60_000).toISOString();
      expect(formatRelativeTime(fiveMinAgo)).toBe('5m ago');
    });

    it('returns hours ago', () => {
      const now = new Date('2025-03-01T12:00:00Z');
      vi.setSystemTime(now);
      const threeHoursAgo = new Date(now.getTime() - 3 * 3600_000).toISOString();
      expect(formatRelativeTime(threeHoursAgo)).toBe('3h ago');
    });

    it('returns days ago for less than 7 days', () => {
      const now = new Date('2025-03-01T12:00:00Z');
      vi.setSystemTime(now);
      const twoDaysAgo = new Date(now.getTime() - 2 * 86400_000).toISOString();
      expect(formatRelativeTime(twoDaysAgo)).toBe('2d ago');
    });

    it('falls back to formatted date for >7 days', () => {
      const now = new Date('2025-03-01T12:00:00Z');
      vi.setSystemTime(now);
      const tenDaysAgo = new Date(now.getTime() - 10 * 86400_000).toISOString();
      const result = formatRelativeTime(tenDaysAgo);
      // Should fall back to formatDate — contains year
      expect(result).toContain('2025');
    });

    afterEach(() => {
      vi.useRealTimers();
    });
  });

  describe('formatNumber', () => {
    it('formats large numbers with locale separators', () => {
      const result = formatNumber(1234567);
      // en-US: "1,234,567"
      expect(result).toContain('234');
      expect(result).toContain('567');
    });

    it('formats zero', () => {
      expect(formatNumber(0)).toBe('0');
    });
  });
});
