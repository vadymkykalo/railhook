import type { DeliveryAttemptResponse } from '../types/api.types';

export type ErrorCategory =
  | 'TIMEOUT'
  | 'CONNECTION_REFUSED'
  | 'DNS_FAILURE'
  | 'SSL_ERROR'
  | 'AUTH_REJECTED'
  | 'RATE_LIMITED'
  | 'SERVER_ERROR'
  | 'CLIENT_ERROR'
  | 'ENDPOINT_NOT_FOUND'
  | 'PAYLOAD_TOO_LARGE'
  | 'UNKNOWN';

export interface ErrorClassification {
  category: ErrorCategory;
  /** i18n key for error category label */
  labelKey: string;
  /** i18n key for suggested fix */
  fixKey: string;
  /** severity: info, warning, error */
  severity: 'info' | 'warning' | 'error';
}

export function classifyError(attempt: DeliveryAttemptResponse): ErrorClassification {
  const msg = (attempt.errorMessage || '').toLowerCase();
  const status = attempt.httpStatusCode;

  // Connection-level errors (no HTTP status)
  if (!status || status === 0) {
    if (msg.includes('timeout') || msg.includes('timed out') || msg.includes('deadline exceeded')) {
      return { category: 'TIMEOUT', labelKey: 'errorClass.timeout.label', fixKey: 'errorClass.timeout.fix', severity: 'warning' };
    }
    if (msg.includes('connection refused') || msg.includes('econnrefused')) {
      return { category: 'CONNECTION_REFUSED', labelKey: 'errorClass.connRefused.label', fixKey: 'errorClass.connRefused.fix', severity: 'error' };
    }
    if (msg.includes('dns') || msg.includes('enotfound') || msg.includes('name resolution') || msg.includes('getaddrinfo')) {
      return { category: 'DNS_FAILURE', labelKey: 'errorClass.dns.label', fixKey: 'errorClass.dns.fix', severity: 'error' };
    }
    if (msg.includes('ssl') || msg.includes('tls') || msg.includes('certificate') || msg.includes('handshake')) {
      return { category: 'SSL_ERROR', labelKey: 'errorClass.ssl.label', fixKey: 'errorClass.ssl.fix', severity: 'error' };
    }
    return { category: 'UNKNOWN', labelKey: 'errorClass.unknown.label', fixKey: 'errorClass.unknown.fix', severity: 'error' };
  }

  // HTTP status-based classification
  if (status === 401 || status === 403) {
    return { category: 'AUTH_REJECTED', labelKey: 'errorClass.auth.label', fixKey: 'errorClass.auth.fix', severity: 'error' };
  }
  if (status === 404) {
    return { category: 'ENDPOINT_NOT_FOUND', labelKey: 'errorClass.notFound.label', fixKey: 'errorClass.notFound.fix', severity: 'error' };
  }
  if (status === 413) {
    return { category: 'PAYLOAD_TOO_LARGE', labelKey: 'errorClass.payloadTooLarge.label', fixKey: 'errorClass.payloadTooLarge.fix', severity: 'warning' };
  }
  if (status === 429) {
    return { category: 'RATE_LIMITED', labelKey: 'errorClass.rateLimited.label', fixKey: 'errorClass.rateLimited.fix', severity: 'warning' };
  }
  if (status >= 400 && status < 500) {
    return { category: 'CLIENT_ERROR', labelKey: 'errorClass.clientError.label', fixKey: 'errorClass.clientError.fix', severity: 'warning' };
  }
  if (status >= 500) {
    return { category: 'SERVER_ERROR', labelKey: 'errorClass.serverError.label', fixKey: 'errorClass.serverError.fix', severity: 'error' };
  }

  return { category: 'UNKNOWN', labelKey: 'errorClass.unknown.label', fixKey: 'errorClass.unknown.fix', severity: 'info' };
}
