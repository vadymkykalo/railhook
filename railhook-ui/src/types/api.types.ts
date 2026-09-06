export interface PageResponse<T> {
  content: T[];
  totalElements: number;
  totalPages: number;
  size: number;
  number: number;
  first: boolean;
  last: boolean;
}

export interface RegisterRequest {
  email: string;
  password: string;
  fullName: string;
  organizationName: string;
  /** Only sent when the deployment configured a CAPTCHA; absent otherwise. */
  captchaToken?: string;
}

export interface LoginRequest {
  email: string;
  password: string;
}

export interface AuthResponse {
  accessToken: string;
  refreshToken: string;
  emailVerified?: boolean;
}

export type UserStatus = 'ACTIVE' | 'PENDING_VERIFICATION' | 'DISABLED';

export interface UserResponse {
  id: string;
  email: string;
  fullName: string | null;
  status: UserStatus;
}

export interface CurrentUserResponse {
  user: UserResponse;
  organization: OrganizationResponse;
  role: 'OWNER' | 'DEVELOPER' | 'VIEWER';
  /**
   * Whether this deployment can deliver mail. False is the shipped default
   * (`EMAIL_ENABLED=false`), and where it is false the product must not claim an
   * invite or a reset "was sent" — it wasn't.
   */
  emailDeliveryEnabled: boolean;
}

export interface OrganizationResponse {
  id: string;
  name: string;
  createdAt: string;
}

export interface ProjectRequest {
  name: string;
  description?: string;
  schemaValidationEnabled?: boolean;
  schemaValidationPolicy?: string;
  idempotencyPolicy?: string;
}

export interface ProjectResponse {
  id: string;
  name: string;
  description?: string;
  schemaValidationEnabled: boolean;
  schemaValidationPolicy: string;
  idempotencyPolicy: string;
  createdAt: string;
  updatedAt: string;
}

/**
 * Which signature headers an endpoint receives.
 *
 * `BOTH` is the default: extra headers cost a receiver nothing, so an existing integration
 * keeps verifying `X-Signature` while a new one can use an off-the-shelf Standard Webhooks
 * library.
 */
export type SignatureScheme = 'LEGACY' | 'STANDARD' | 'BOTH';

export interface EndpointRequest {
  url: string;
  description?: string;
  secret?: string;
  enabled?: boolean;
  rateLimitPerSecond?: number;
  allowedSourceIps?: string;
  signatureScheme?: SignatureScheme;
}

export interface EndpointResponse {
  id: string;
  projectId: string;
  url: string;
  description?: string;
  enabled: boolean;
  rateLimitPerSecond?: number;
  allowedSourceIps?: string;
  mtlsEnabled?: boolean;
  verificationStatus?: 'PENDING' | 'VERIFIED' | 'FAILED' | 'SKIPPED';
  verificationAttemptedAt?: string;
  verificationCompletedAt?: string;
  verificationSkipReason?: string;
  createdAt: string;
  updatedAt: string;
  secret?: string;
  signatureScheme?: SignatureScheme;
  /**
   * The same secret in the form a Standard Webhooks library expects (`whsec_` + base64).
   * Present only where `secret` is — at creation and rotation.
   */
  standardWebhooksSecret?: string;
}

export interface DeliveryResponse {
  id: string;
  eventId: string;
  endpointId: string;
  subscriptionId: string;
  status: 'PENDING' | 'PROCESSING' | 'SUCCESS' | 'FAILED' | 'DLQ';
  attemptCount: number;
  maxAttempts: number;
  nextRetryAt?: string;
  lastAttemptAt?: string;
  succeededAt?: string;
  failedAt?: string;
  createdAt: string;
}

export interface DeliveryAttemptResponse {
  id: string;
  deliveryId: string;
  attemptNumber: number;
  requestHeaders?: string;
  requestBody?: string;
  httpStatusCode?: number;
  responseHeaders?: string;
  responseBody?: string;
  errorMessage?: string;
  durationMs?: number;
  createdAt: string;
}

export interface EventResponse {
  id: string;
  projectId: string;
  eventType: string;
  payload: string;
  createdAt: string;
  deliveriesCreated?: number;
  /** Set only on a test-event response, and only under a project whose policy is WARN. */
  schemaWarnings?: string[];
}

export interface SubscriptionResponse {
  id: string;
  projectId: string;
  endpointId: string;
  eventType: string;
  enabled: boolean;
  createdAt: string;
  updatedAt: string;
}

// ─── Incoming Webhooks ──────────────────────────────────────────────

export type ProviderType = 'GENERIC' | 'GITHUB' | 'GITLAB' | 'STRIPE' | 'SHOPIFY' | 'SLACK' | 'TWILIO';
export type IncomingSourceStatus = 'ACTIVE' | 'DISABLED';
export type VerificationMode = 'NONE' | 'HMAC_GENERIC' | 'PROVIDER';
export type IncomingAuthType = 'NONE' | 'BEARER' | 'BASIC' | 'CUSTOM_HEADER';
export type ForwardAttemptStatus = 'PENDING' | 'PROCESSING' | 'SUCCESS' | 'FAILED' | 'DLQ';

export interface IncomingSourceRequest {
  name: string;
  slug?: string;
  providerType?: ProviderType;
  status?: IncomingSourceStatus;
  verificationMode?: VerificationMode;
  hmacSecret?: string;
  hmacHeaderName?: string;
  hmacSignaturePrefix?: string;
  rateLimitPerSecond?: number | null;
}

export interface IncomingSourceResponse {
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
  rateLimitPerSecond?: number | null;
  createdAt: string;
  updatedAt: string;
}

export interface IncomingDestinationRequest {
  url: string;
  authType?: IncomingAuthType;
  authConfig?: string;
  customHeadersJson?: string;
  enabled?: boolean;
  maxAttempts?: number;
  timeoutSeconds?: number;
  retryDelays?: string;
  payloadTransform?: string;
  /** A UUID, or '' to detach the destination from its transformation template. */
  transformationId?: string;
}

export interface IncomingDestinationResponse {
  id: string;
  incomingSourceId: string;
  url: string;
  authType: IncomingAuthType;
  authConfigured: boolean;
  customHeadersJson?: string;
  enabled: boolean;
  maxAttempts: number;
  timeoutSeconds: number;
  retryDelays: string;
  payloadTransform?: string;
  transformationId?: string;
  transformationName?: string;
  createdAt: string;
  updatedAt: string;
}

export interface IncomingEventResponse {
  id: string;
  incomingSourceId: string;
  sourceName?: string;
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
  verified?: boolean | null;
  verificationError?: string;
  receivedAt: string;
}

export interface IncomingForwardAttemptResponse {
  id: string;
  incomingEventId: string;
  destinationId: string;
  destinationUrl?: string;
  attemptNumber: number;
  status: ForwardAttemptStatus;
  startedAt?: string;
  finishedAt?: string;
  requestHeadersJson?: string;
  requestBodySnippet?: string;
  responseCode?: number;
  responseHeadersJson?: string;
  responseBodySnippet?: string;
  errorMessage?: string;
  nextRetryAt?: string;
  createdAt: string;
}

/** Statistics for either direction's DLQ; the backend returns one shape for both. */
export interface DlqStatsResponse {
  totalItems: number;
  last24Hours: number;
  last7Days: number;
}

/** One abandoned Forward: a Destination and an Incoming Event, keyed on the Attempt row. */
export interface IncomingDlqItemResponse {
  forwardAttemptId: string;
  incomingEventId: string;
  destinationId: string;
  incomingSourceId?: string;
  sourceName?: string;
  destinationUrl?: string;
  attemptNumber?: number;
  maxAttempts?: number;
  responseCode?: number;
  lastError?: string;
  failedAt?: string;
  createdAt?: string;
}

export interface IncomingDlqRetryRequest {
  forwardAttemptIds: string[];
}

export interface ReplayEventResponse {
  status: string;
  eventId: string;
  destinationsCount: number;
}

export interface IncomingBulkReplayRequest {
  sourceId: string;
  from?: string;
  to?: string;
  verified?: boolean | null;
  eventIds?: string[];
  maxEvents?: number;
}

// ─── Transformations ─────────────────────────────────────────────────

export interface TransformationRequest {
  name: string;
  description?: string;
  template: string;
  enabled?: boolean;
}

export interface TransformationResponse {
  id: string;
  projectId: string;
  name: string;
  description?: string;
  template: string;
  version: number;
  enabled: boolean;
  subscriptionCount: number;
  destinationCount: number;
  createdAt: string;
  updatedAt: string;
}

export interface IncomingBulkReplayResponse {
  status: string;
  sourceId: string;
  eventsReplayed: number;
  totalForwardAttempts: number;
}
