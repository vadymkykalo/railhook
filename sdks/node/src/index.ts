export { Railhook } from './client';
export { RailhookError, RateLimitError, AuthenticationError, ValidationError, NotFoundError } from './errors';

// Backward-compatible aliases
export { Railhook as WebhookPlatform } from './client';
export { RailhookError as WebhookPlatformError } from './errors';
export { verifySignature, verifyStandardWebhook, constructEvent, generateSignature } from './webhooks';
export type { WebhookHeaders, VerifyOptions } from './webhooks';
export * from './types';
