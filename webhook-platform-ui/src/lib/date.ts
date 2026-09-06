import i18n from '../i18n';

const LOCALE_MAP: Record<string, string> = {
  en: 'en-US',
  uk: 'uk-UA',
};

const TZ_STORAGE_KEY = 'railhook_timezone';

function getLocale(): string {
  return LOCALE_MAP[i18n.language] || 'en-US';
}

export function getStoredTimezone(): string {
  return localStorage.getItem(TZ_STORAGE_KEY) || Intl.DateTimeFormat().resolvedOptions().timeZone;
}

export function setStoredTimezone(tz: string): void {
  localStorage.setItem(TZ_STORAGE_KEY, tz);
}

function tzOption(): { timeZone: string } {
  return { timeZone: getStoredTimezone() };
}

/**
 * Full date + time: "Feb 19, 2025, 2:30:15 PM" / "19 лют. 2025 р., 14:30:15"
 */
export function formatDateTime(dateString: string): string {
  return new Date(dateString).toLocaleString(getLocale(), {
    ...tzOption(),
    month: 'short',
    day: 'numeric',
    year: 'numeric',
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit',
  });
}

/**
 * Short date + time (no seconds): "Feb 19, 2025, 2:30 PM"
 */
export function formatDateTimeShort(dateString: string): string {
  return new Date(dateString).toLocaleString(getLocale(), {
    ...tzOption(),
    month: 'short',
    day: 'numeric',
    year: 'numeric',
    hour: '2-digit',
    minute: '2-digit',
  });
}

/**
 * Date only: "Feb 19, 2025" / "19 лют. 2025 р."
 */
export function formatDate(dateString: string): string {
  return new Date(dateString).toLocaleDateString(getLocale(), {
    ...tzOption(),
    month: 'short',
    day: 'numeric',
    year: 'numeric',
  });
}

/**
 * Short date + time (no year): "Feb 19, 2:30:15 PM" — for audit logs, etc.
 */
export function formatDateTimeCompact(dateString: string): string {
  return new Date(dateString).toLocaleString(getLocale(), {
    ...tzOption(),
    month: 'short',
    day: 'numeric',
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit',
  });
}

/**
 * Time only: "14:30" — for chart axes
 */
export function formatTime(dateString: string): string {
  return new Date(dateString).toLocaleTimeString(getLocale(), {
    ...tzOption(),
    hour: '2-digit',
    minute: '2-digit',
  });
}

/**
 * Relative time: "just now", "5m ago", "2h ago", "3d ago"
 * Falls back to formatted date for >7 days.
 * Uses i18n translation keys from `relativeTime.*`.
 */
export function formatRelativeTime(dateString: string): string {
  const date = new Date(dateString);
  const now = new Date();
  const diffMs = now.getTime() - date.getTime();
  const diffSec = Math.floor(diffMs / 1000);
  const diffMin = Math.floor(diffSec / 60);
  const diffHour = Math.floor(diffMin / 60);
  const diffDay = Math.floor(diffHour / 24);

  const t = i18n.t.bind(i18n);

  if (diffSec < 60) return t('relativeTime.justNow');
  if (diffMin < 60) return t('relativeTime.minutesAgo', { count: diffMin });
  if (diffHour < 24) return t('relativeTime.hoursAgo', { count: diffHour });
  if (diffDay < 7) return t('relativeTime.daysAgo', { count: diffDay });

  return formatDate(dateString);
}

/**
 * Relative future time: "in 30s", "in 5m", "in 2h"
 * For past dates, falls back to formatRelativeTime.
 */
export function formatRelativeFuture(dateString: string): string {
  const date = new Date(dateString);
  const now = new Date();
  const diffMs = date.getTime() - now.getTime();

  if (diffMs <= 0) return formatRelativeTime(dateString);

  const t = i18n.t.bind(i18n);
  const diffSec = Math.floor(diffMs / 1000);
  const diffMin = Math.floor(diffSec / 60);
  const diffHour = Math.floor(diffMin / 60);

  if (diffSec < 60) return t('relativeTime.inSeconds', { count: diffSec });
  if (diffMin < 60) return t('relativeTime.inMinutes', { count: diffMin });
  if (diffHour < 24) return t('relativeTime.inHours', { count: diffHour });

  return formatDateTime(dateString);
}

/**
 * Locale-aware number formatting: 1234567 → "1,234,567" / "1 234 567"
 */
export function formatNumber(value: number): string {
  return value.toLocaleString(getLocale());
}
