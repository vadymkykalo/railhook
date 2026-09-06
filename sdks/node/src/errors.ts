import { RateLimitInfo } from './types';

export class RailhookError extends Error {
  public readonly status: number;
  public readonly code?: string;

  constructor(message: string, status: number, code?: string) {
    super(message);
    this.name = 'RailhookError';
    this.status = status;
    this.code = code;
  }
}

export class AuthenticationError extends RailhookError {
  constructor(message: string = 'Invalid API key') {
    super(message, 401, 'authentication_error');
    this.name = 'AuthenticationError';
  }
}

export class RateLimitError extends RailhookError {
  public readonly rateLimitInfo: RateLimitInfo;

  constructor(message: string, rateLimitInfo: RateLimitInfo) {
    super(message, 429, 'rate_limit_exceeded');
    this.name = 'RateLimitError';
    this.rateLimitInfo = rateLimitInfo;
  }

  /**
   * Milliseconds to wait before retrying.
   *
   * `rateLimitInfo.reset` is the raw `X-RateLimit-Reset` header, which the API
   * sends as a Unix timestamp in **seconds**; comparing it to `Date.now()`
   * (milliseconds) directly always yields a large negative number, i.e. 0.
   */
  get retryAfter(): number {
    return Math.max(0, this.rateLimitInfo.reset * 1000 - Date.now());
  }
}

export class ValidationError extends RailhookError {
  public readonly fieldErrors: Record<string, string>;

  constructor(message: string, fieldErrors: Record<string, string> = {}) {
    super(message, 400, 'validation_error');
    this.name = 'ValidationError';
    this.fieldErrors = fieldErrors;
  }
}

export class NotFoundError extends RailhookError {
  constructor(message: string = 'Resource not found') {
    super(message, 404, 'not_found');
    this.name = 'NotFoundError';
  }
}
