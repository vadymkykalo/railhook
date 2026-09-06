import { describe, it, expect, vi, beforeEach } from 'vitest';
import { toast } from 'sonner';

// Mock sonner
vi.mock('sonner', () => ({
  toast: {
    error: vi.fn(),
    success: vi.fn(),
    warning: vi.fn(),
    info: vi.fn(),
  },
}));

// Mock i18n
vi.mock('../../i18n', () => ({
  default: {
    t: (key: string) => {
      const translations: Record<string, string> = {
        'toast.retry': 'Retry',
        'toast.errors.unauthorized': 'Unauthorized',
        'toast.errors.forbidden': 'Forbidden',
        'toast.errors.notFound': 'Not found',
        'toast.errors.conflict': 'Conflict',
        'toast.errors.validation': 'Validation error',
        'toast.errors.tooManyRequests': 'Too many requests',
        'toast.errors.server': 'Server error',
        'toast.errors.network': 'Network error. Check your connection and try again.',
        'toast.fallback': 'Something went wrong',
      };
      return translations[key] || key;
    },
    exists: (key: string) => key.startsWith('toast.'),
  },
}));

import { showApiError, showSuccess, showWarning, showInfo, resolveErrorMessage, isNetworkError } from '../toast';

describe('toast utilities', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  describe('showApiError', () => {
    it('uses API message when available', () => {
      const err = { response: { status: 400, data: { message: 'Bad input' } } };
      showApiError(err, 'toast.fallback');
      expect(toast.error).toHaveBeenCalledWith('Bad input', expect.any(Object));
    });

    it('falls back to HTTP status mapping when no API message', () => {
      const err = { response: { status: 401, data: {} } };
      showApiError(err, 'toast.fallback');
      expect(toast.error).toHaveBeenCalledWith('Unauthorized', expect.any(Object));
    });

    it('falls back to i18n key when no status mapping', () => {
      const err = {};
      showApiError(err, 'toast.fallback');
      expect(toast.error).toHaveBeenCalledWith('Something went wrong', expect.any(Object));
    });

    it('maps 403 to forbidden', () => {
      const err = { response: { status: 403, data: {} } };
      showApiError(err, 'toast.fallback');
      expect(toast.error).toHaveBeenCalledWith('Forbidden', expect.any(Object));
    });

    it('maps 429 to too many requests', () => {
      const err = { response: { status: 429, data: {} } };
      showApiError(err, 'toast.fallback');
      expect(toast.error).toHaveBeenCalledWith('Too many requests', expect.any(Object));
    });

    it('maps 500 to server error', () => {
      const err = { response: { status: 500, data: {} } };
      showApiError(err, 'toast.fallback');
      expect(toast.error).toHaveBeenCalledWith('Server error', expect.any(Object));
    });

    it('adds retry action when provided', () => {
      const retryFn = vi.fn();
      const err = {};
      showApiError(err, 'toast.fallback', { retry: retryFn });
      expect(toast.error).toHaveBeenCalledWith(
        'Something went wrong',
        expect.objectContaining({ action: expect.any(Object) })
      );
    });

    it('deduplicates by fallback key + API message', () => {
      const err = { response: { status: 400, data: { message: 'Duplicate' } } };
      showApiError(err, 'toast.fallback');
      expect(toast.error).toHaveBeenCalledWith('Duplicate', expect.objectContaining({
        id: 'toast.fallback::Duplicate',
      }));
    });
  });

  describe('isNetworkError', () => {
    it('is true when the request has no response (connection refused / backend down)', () => {
      expect(isNetworkError({ request: {}, message: 'Network Error' })).toBe(true);
    });

    it('is true for axios ERR_NETWORK / timeout codes', () => {
      expect(isNetworkError({ code: 'ERR_NETWORK' })).toBe(true);
      expect(isNetworkError({ code: 'ECONNABORTED' })).toBe(true);
    });

    it('is false when the server actually responded, even with a 5xx', () => {
      expect(isNetworkError({ response: { status: 500 }, request: {} })).toBe(false);
    });

    it('is false for a plain object with no axios shape', () => {
      expect(isNetworkError({})).toBe(false);
      expect(isNetworkError(null)).toBe(false);
    });
  });

  describe('resolveErrorMessage', () => {
    it('prioritizes the network-down message over the fallback key', () => {
      const err = { request: {}, message: 'Network Error' };
      expect(resolveErrorMessage(err, 'toast.fallback')).toBe('Network error. Check your connection and try again.');
    });

    it('falls back to the API message when the server responded', () => {
      const err = { response: { status: 400, data: { message: 'Bad input' } } };
      expect(resolveErrorMessage(err, 'toast.fallback')).toBe('Bad input');
    });
  });

  describe('showApiError network handling', () => {
    it('shows the network-down message instead of the generic fallback when the backend is unreachable', () => {
      const err = { request: {}, message: 'Network Error' };
      showApiError(err, 'toast.fallback');
      expect(toast.error).toHaveBeenCalledWith(
        'Network error. Check your connection and try again.',
        expect.any(Object)
      );
    });
  });

  describe('showSuccess', () => {
    it('shows translated message for i18n key', () => {
      showSuccess('toast.fallback');
      expect(toast.success).toHaveBeenCalledWith('Something went wrong', expect.any(Object));
    });

    it('shows raw message for non-i18n string', () => {
      showSuccess('Created successfully');
      expect(toast.success).toHaveBeenCalledWith('Created successfully', expect.any(Object));
    });
  });

  describe('showWarning', () => {
    it('calls toast.warning', () => {
      showWarning('Watch out');
      expect(toast.warning).toHaveBeenCalledWith('Watch out', expect.objectContaining({
        duration: 6000,
      }));
    });
  });

  describe('showInfo', () => {
    it('calls toast.info', () => {
      showInfo('FYI');
      expect(toast.info).toHaveBeenCalledWith('FYI', expect.any(Object));
    });
  });
});
