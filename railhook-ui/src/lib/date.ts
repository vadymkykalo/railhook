import i18n from '../i18n';
import { canonicalTimezone } from './publicSnippets';

const LOCALE_MAP: Record<string, string> = {
  en: 'en-US',
  uk: 'uk-UA',
};

const TZ_STORAGE_KEY = 'railhook_timezone';

function getLocale(): string {
  return LOCALE_MAP[i18n.language] || 'en-US';
}

export function getStoredTimezone(): string {
  // Chromium still reports Ukraine as the renamed Europe/Kiev.
  return canonicalTimezone(localStorage.getItem(TZ_STORAGE_KEY) || Intl.DateTimeFormat().resolvedOptions().timeZone);
}

export function setStoredTimezone(tz: string): void {
  localStorage.setItem(TZ_STORAGE_KEY, tz);
}

function tzOption(): { timeZone: string } {
  return { timeZone: getStoredTimezone() };
}

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

export function formatDate(dateString: string): string {
  return new Date(dateString).toLocaleDateString(getLocale(), {
    ...tzOption(),
    month: 'short',
    day: 'numeric',
    year: 'numeric',
  });
}

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

export function formatTime(dateString: string): string {
  return new Date(dateString).toLocaleTimeString(getLocale(), {
    ...tzOption(),
    hour: '2-digit',
    minute: '2-digit',
  });
}

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

export function formatNumber(value: number): string {
  return value.toLocaleString(getLocale());
}
