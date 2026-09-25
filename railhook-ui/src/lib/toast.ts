import { toast } from 'sonner';
import i18n from '../i18n';

interface ToastOptions {
  id?: string;
  duration?: number;
  retry?: () => void;
}

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

export function isNetworkError(err: unknown): boolean {
  if (!err || typeof err !== 'object') return false;
  const e = err as any;
  if (e.response) return false;
  return Boolean(e.request) || e.code === 'ERR_NETWORK' || e.code === 'ECONNABORTED' || e.message === 'Network Error';
}

function dedupeId(fallbackKey: string, apiMsg: string | null): string {
  return apiMsg ? `${fallbackKey}::${apiMsg}` : fallbackKey;
}

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

export function resolveErrorMessage(err: unknown, fallbackKey: string): string {
  if (isNetworkError(err)) {
    return t('toast.errors.network');
  }

  const apiMsg = extractApiMessage(err);
  const status = extractHttpStatus(err);

  // The server says it in English; the demo's refusal is the one every visitor meets.
  if ((err as any)?.response?.data?.error === 'demo_read_only') {
    return t('demo.readOnlyError');
  }
  if (apiMsg) {
    return apiMsg;
  }
  if (status && STATUS_MESSAGE_KEYS[status]) {
    return t(STATUS_MESSAGE_KEYS[status]);
  }
  return t(fallbackKey);
}

const reportedErrors = new WeakSet<object>();

export function wasErrorReported(err: unknown): boolean {
  return !!err && typeof err === 'object' && reportedErrors.has(err);
}

export function showApiError(err: unknown, fallbackKey: string, options?: ToastOptions) {
  if (err && typeof err === 'object') reportedErrors.add(err);
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

export function showSuccess(messageOrKey: string, options?: ToastOptions) {
  const message = i18n.exists(messageOrKey) ? t(messageOrKey) : messageOrKey;
  toast.success(message, {
    id: options?.id ?? messageOrKey,
    duration: options?.duration,
  });
}

export function showWarning(messageOrKey: string, options?: ToastOptions) {
  const message = i18n.exists(messageOrKey) ? t(messageOrKey) : messageOrKey;
  toast.warning(message, {
    id: options?.id ?? messageOrKey,
    duration: options?.duration ?? 6000,
  });
}

export function showInfo(messageOrKey: string, options?: ToastOptions) {
  const message = i18n.exists(messageOrKey) ? t(messageOrKey) : messageOrKey;
  toast.info(message, {
    id: options?.id ?? messageOrKey,
    duration: options?.duration,
  });
}

export function showError(messageOrKey: string, options?: ToastOptions) {
  const message = i18n.exists(messageOrKey) ? t(messageOrKey) : messageOrKey;
  toast.error(message, {
    id: options?.id ?? messageOrKey,
    duration: options?.duration,
  });
}

export function showCriticalSuccess(messageOrKey: string, options?: ToastOptions) {
  showSuccess(messageOrKey, {
    ...options,
    duration: options?.duration ?? 8000,
  });
}
