import { toast } from 'sonner';
import i18n from '../i18n';

// ─── Types ──────────────────────────────────────────────────────────

interface ToastOptions {
  /** Stable ID for deduplication — same ID prevents duplicate toasts */
  id?: string;
  /** Duration in ms. Use Infinity for sticky. Default: 4000 */
  duration?: number;
  /** Retry callback — adds a "Retry" action button to error toasts */
  retry?: () => void;
}

// ─── Helpers ────────────────────────────────────────────────────────

function t(key: string, opts?: Record<string, unknown>): string {
  return i18n.t(key, opts) as string;
}

function extractApiMessage(err: unknown): string | null {
  if (
    err &&
    typeof err === 'object' &&
    'response' in err &&
    (err as any).response?.data?.message
  ) {
    return (err as any).response.data.message;
  }
  return null;
}

function extractHttpStatus(err: unknown): number | null {
  if (
    err &&
    typeof err === 'object' &&
    'response' in err &&
    typeof (err as any).response?.status === 'number'
  ) {
    return (err as any).response.status;
  }
  return null;
}

/**
 * True when the request never reached a server at all — connection refused,
 * DNS failure, timeout, or the backend simply isn't up. Distinct from a 4xx/5xx,
 * which means a server *did* respond. This is the "the API is down" case.
 */
export function isNetworkError(err: unknown): boolean {
  if (!err || typeof err !== 'object') return false;
  const e = err as any;
  if (e.response) return false; // server responded — not a network error
  return Boolean(e.request) || e.code === 'ERR_NETWORK' || e.code === 'ECONNABORTED' || e.message === 'Network Error';
}

/** Generates a stable dedup ID from the fallback key + optional error message */
function dedupeId(fallbackKey: string, apiMsg: string | null): string {
  return apiMsg ? `${fallbackKey}::${apiMsg}` : fallbackKey;
}

// ─── Global error → user-friendly message mapping ───────────────────

const STATUS_MESSAGE_KEYS: Record<number, string> = {
  401: 'toast.errors.unauthorized',
  403: 'toast.errors.forbidden',
  404: 'toast.errors.notFound',
  409: 'toast.errors.conflict',
  422: 'toast.errors.validation',
  413: 'toast.errors.payloadTooLarge',
  429: 'toast.errors.tooManyRequests',
  500: 'toast.errors.server',
  502: 'toast.errors.server',
  503: 'toast.errors.server',
};

// ─── Public API ─────────────────────────────────────────────────────

/**
 * Resolves a human-readable message from an API error.
 * Priority: API message → HTTP status mapping → fallback i18n key.
 * Shared by toast notifications and inline error states so the wording matches.
 */
export function resolveErrorMessage(err: unknown, fallbackKey: string): string {
  if (isNetworkError(err)) {
    return t('toast.errors.network');
  }

  const apiMsg = extractApiMessage(err);
  const status = extractHttpStatus(err);

  if (apiMsg) {
    return apiMsg;
  }
  if (status && STATUS_MESSAGE_KEYS[status]) {
    return t(STATUS_MESSAGE_KEYS[status]);
  }
  return t(fallbackKey);
}

/**
 * Show an error toast from an API error.
 * Priority: API message → HTTP status mapping → fallback i18n key.
 * Automatically deduplicates identical errors.
 */
export function showApiError(err: unknown, fallbackKey: string, options?: ToastOptions) {
  const apiMsg = extractApiMessage(err);
  const message = resolveErrorMessage(err, fallbackKey);

  const id = options?.id ?? dedupeId(fallbackKey, apiMsg);

  if (options?.retry) {
    const retryFn = options.retry;
    toast.error(message, {
      id,
      duration: options?.duration ?? 8000,
      action: {
        label: t('toast.retry'),
        onClick: () => retryFn(),
      },
    });
  } else {
    toast.error(message, {
      id,
      duration: options?.duration,
    });
  }
}

/** Success toast with deduplication */
export function showSuccess(messageOrKey: string, options?: ToastOptions) {
  const message = i18n.exists(messageOrKey) ? t(messageOrKey) : messageOrKey;
  toast.success(message, {
    id: options?.id ?? messageOrKey,
    duration: options?.duration,
  });
}

/** Warning toast — for non-blocking but important notices */
export function showWarning(messageOrKey: string, options?: ToastOptions) {
  const message = i18n.exists(messageOrKey) ? t(messageOrKey) : messageOrKey;
  toast.warning(message, {
    id: options?.id ?? messageOrKey,
    duration: options?.duration ?? 6000,
  });
}

/** Info toast */
export function showInfo(messageOrKey: string, options?: ToastOptions) {
  const message = i18n.exists(messageOrKey) ? t(messageOrKey) : messageOrKey;
  toast.info(message, {
    id: options?.id ?? messageOrKey,
    duration: options?.duration,
  });
}

/** Error toast for non-API errors (business logic failures, etc.) */
export function showError(messageOrKey: string, options?: ToastOptions) {
  const message = i18n.exists(messageOrKey) ? t(messageOrKey) : messageOrKey;
  toast.error(message, {
    id: options?.id ?? messageOrKey,
    duration: options?.duration,
  });
}

/**
 * Sticky success for critical completed actions (e.g. purge, delete).
 * Stays for 8s so user has time to read.
 */
export function showCriticalSuccess(messageOrKey: string, options?: ToastOptions) {
  showSuccess(messageOrKey, {
    ...options,
    duration: options?.duration ?? 8000,
  });
}
