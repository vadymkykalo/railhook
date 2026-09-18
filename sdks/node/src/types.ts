export interface RailhookConfig {
  apiKey: string;
  baseUrl?: string;
  timeout?: number;
}

export interface Event {
  type: string;
  data: Record<string, unknown>;
}

export interface EventResponse {
  eventId: string;
  type: string;
  createdAt: string;
  deliveriesCreated: number;
  /**
   * Schema-validation errors this event was accepted despite, when the project has schema
   * validation on with the WARN policy. Absent when the payload matched or validation is off.
   */
  schemaWarnings?: string[];
}

export interface Endpoint {
  id: string;
  projectId: string;
  /** The Consumer this endpoint belongs to, or absent when it is your own. */
  consumerId?: string;
  url: string;
  description?: string;
  secret?: string;
  enabled: boolean;
  rateLimitPerSecond?: number;
  allowedSourceIps?: string;
  mtlsEnabled?: boolean;
  verificationStatus?: string;
  verificationAttemptedAt?: string;
  verificationCompletedAt?: string;
  verificationSkipReason?: string;
  createdAt: string;
  updatedAt?: string;
}

export interface EndpointCreateParams {
  url: string;
  description?: string;
  /** Supply your own signing secret. Omit to have the API generate one. */
  secret?: string;
  enabled?: boolean;
  rateLimitPerSecond?: number;
  /** Comma-separated CIDRs the endpoint is allowed to be reached from. */
  allowedSourceIps?: string;
  /** Registers the endpoint for one of your Consumers, which puts it in their portal. */
  consumerId?: string;
}

export interface EndpointUpdateParams {
  url?: string;
  description?: string;
  secret?: string;
  enabled?: boolean;
  rateLimitPerSecond?: number;
  allowedSourceIps?: string;
  /** Moves the endpoint to another Consumer of the same project. Omit to leave it alone. */
  consumerId?: string;
}

export interface Subscription {
  id: string;
  projectId: string;
  endpointId: string;
  eventType: string;
  enabled: boolean;
  orderingEnabled: boolean;
  maxAttempts: number;
  timeoutSeconds: number;
  retryDelays?: string;
  payloadTemplate?: string;
  customHeaders?: string;
  transformationId?: string;
  transformationName?: string;
  createdAt: string;
  updatedAt?: string;
}

export interface SubscriptionCreateParams {
  endpointId: string;
  eventType: string;
  enabled?: boolean;
  orderingEnabled?: boolean;
  maxAttempts?: number;
  timeoutSeconds?: number;
  retryDelays?: string;
  payloadTemplate?: string;
  customHeaders?: string;
  transformationId?: string;
}

export interface Delivery {
  id: string;
  eventId: string;
  endpointId: string;
  subscriptionId: string;
  status: DeliveryStatus;
  attemptCount: number;
  maxAttempts: number;
  nextRetryAt?: string;
  lastAttemptAt?: string;
  succeededAt?: string;
  failedAt?: string;
  createdAt: string;
}

export type DeliveryStatus = 'PENDING' | 'PROCESSING' | 'SUCCESS' | 'FAILED' | 'DLQ';

export interface DeliveryAttempt {
  id: string;
  deliveryId: string;
  attemptNumber: number;
  /** JSON object, serialized as a string, of the headers that were sent. */
  requestHeaders?: string;
  requestBody?: string;
  httpStatusCode?: number;
  /** JSON object, serialized as a string, of the headers that came back. */
  responseHeaders?: string;
  responseBody?: string;
  errorMessage?: string;
  /** Wall-clock duration of the HTTP request, in milliseconds. */
  durationMs?: number;
  /** When the attempt was recorded. */
  createdAt: string;
}

export interface PaginatedResponse<T> {
  content: T[];
  totalElements: number;
  totalPages: number;
  /** Page size requested. */
  size: number;
  /** Zero-based index of this page. */
  number: number;
  numberOfElements?: number;
  first?: boolean;
  last?: boolean;
  empty?: boolean;
}

export interface EndpointListParams {
  page?: number;
  size?: number;
}

export interface DeliveryListParams {
  status?: DeliveryStatus;
  endpointId?: string;
  fromDate?: string;
  toDate?: string;
  page?: number;
  size?: number;
}

export interface EndpointTestResult {
  success: boolean;
  httpStatusCode?: number;
  responseBody?: string;
  errorMessage?: string;
  latencyMs: number;
  /** Human-readable summary, e.g. "Endpoint returned non-2xx status". */
  message?: string;
}

export interface RateLimitInfo {
  limit: number;
  remaining: number;
  /**
   * Unix timestamp in **seconds** at which the window resets — the raw value
   * of the `X-RateLimit-Reset` header, which the API sends in seconds, not
   * milliseconds.
   */
  reset: number;
}

export interface WebhookEvent {
  eventId: string;
  deliveryId: string;
  timestamp: number;
  type: string;
  data: Record<string, unknown>;
}

// ── Incoming Webhooks ──

export type ProviderType = 'GENERIC' | 'GITHUB' | 'GITLAB' | 'STRIPE' | 'SHOPIFY' | 'SLACK' | 'TWILIO';
/**
 * `HMAC_GENERIC` checks your own header and prefix; `PROVIDER` uses the scheme of the source's
 * `providerType` (Stripe's timestamped `t=…,v1=…`, GitHub's `sha256=`, …).
 */
export type VerificationMode = 'NONE' | 'HMAC_GENERIC' | 'PROVIDER';
export type IncomingSourceStatus = 'ACTIVE' | 'DISABLED';
export type IncomingAuthType = 'NONE' | 'BEARER' | 'BASIC' | 'CUSTOM_HEADER';

export interface IncomingSource {
  id: string;
  projectId: string;
  name: string;
  slug: string;
  providerType: ProviderType;
  status: IncomingSourceStatus;
  ingressPathToken: string;
  ingressUrl: string;
  verificationMode: VerificationMode;
  hmacHeaderName?: string;
  hmacSignaturePrefix?: string;
  hmacSecretConfigured: boolean;
  rateLimitPerSecond?: number;
  createdAt: string;
  updatedAt?: string;
}

export interface IncomingSourceCreateParams {
  name: string;
  slug?: string;
  providerType?: ProviderType;
  verificationMode?: VerificationMode;
  hmacSecret?: string;
  hmacHeaderName?: string;
  hmacSignaturePrefix?: string;
  rateLimitPerSecond?: number;
}

export interface IncomingSourceUpdateParams {
  name?: string;
  slug?: string;
  providerType?: ProviderType;
  status?: IncomingSourceStatus;
  verificationMode?: VerificationMode;
  hmacSecret?: string;
  hmacHeaderName?: string;
  hmacSignaturePrefix?: string;
  rateLimitPerSecond?: number;
}

export interface IncomingDestination {
  id: string;
  incomingSourceId: string;
  url: string;
  authType: IncomingAuthType;
  authConfigured: boolean;
  customHeadersJson?: string;
  enabled: boolean;
  maxAttempts: number;
  timeoutSeconds: number;
  retryDelays?: string;
  payloadTransform?: string;
  transformationId?: string;
  transformationName?: string;
  createdAt: string;
  updatedAt?: string;
}

export interface IncomingDestinationCreateParams {
  url: string;
  authType?: IncomingAuthType;
  authConfig?: string;
  customHeadersJson?: string;
  enabled?: boolean;
  maxAttempts?: number;
  timeoutSeconds?: number;
  retryDelays?: string;
  payloadTransform?: string;
}

export interface IncomingDestinationUpdateParams {
  url?: string;
  authType?: IncomingAuthType;
  authConfig?: string;
  customHeadersJson?: string;
  enabled?: boolean;
  maxAttempts?: number;
  timeoutSeconds?: number;
  retryDelays?: string;
  payloadTransform?: string;
}

export interface IncomingEvent {
  id: string;
  incomingSourceId: string;
  sourceName: string;
  requestId: string;
  method: string;
  path: string;
  queryParams?: string;
  headersJson?: string;
  bodyRaw?: string;
  bodySha256?: string;
  contentType?: string;
  clientIp?: string;
  userAgent?: string;
  verified?: boolean;
  verificationError?: string;
  receivedAt: string;
}

export interface IncomingEventListParams {
  sourceId?: string;
  page?: number;
  size?: number;
}

export type ForwardAttemptStatus = 'PENDING' | 'PROCESSING' | 'SUCCESS' | 'FAILED' | 'DLQ';

export interface IncomingForwardAttempt {
  id: string;
  incomingEventId: string;
  destinationId: string;
  destinationUrl: string;
  attemptNumber: number;
  status: ForwardAttemptStatus;
  startedAt?: string;
  finishedAt?: string;
  responseCode?: number;
  responseHeadersJson?: string;
  responseBodySnippet?: string;
  errorMessage?: string;
  nextRetryAt?: string;
  createdAt: string;
}

export interface ReplayEventResponse {
  status: string;
  eventId: string;
  destinationsCount: number;
}

/** One of your own users, grouping the endpoints registered for them. */
export interface Consumer {
  id: string;
  projectId: string;
  /** Your own identifier for the user, unique within the project. */
  externalId: string;
  name: string;
  /** Live endpoints registered for this Consumer. */
  endpointCount: number;
  createdAt: string;
  updatedAt?: string;
}

export interface ConsumerCreateParams {
  externalId: string;
  /** Shown at the top of the portal. Defaults to externalId. */
  name?: string;
}

export interface ConsumerUpdateParams {
  externalId: string;
  /** Omit to leave the name unchanged. */
  name?: string;
}

export interface ConsumerListParams {
  /** Narrows the list to the Consumer with this external id. */
  externalId?: string;
  page?: number;
  size?: number;
}

export interface PortalSessionCreateParams {
  /** Minutes the session lasts, 1–1440. Defaults to 60. */
  ttlMinutes?: number;
  /** The https origin of the page that embeds the portal, e.g. https://app.example.com. */
  allowedOrigin?: string;
}

/** A new portal session. `token` is returned here and never again. */
export interface PortalSession {
  id: string;
  consumerId: string;
  /** The portal, ready to open or to use as an iframe's src. */
  url: string;
  token: string;
  allowedOrigin?: string | null;
  expiresAt: string;
}
