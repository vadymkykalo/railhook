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

/**
 * A new address for the signed-in account. An unverified account sends `captchaToken` (when the
 * deployment has a CAPTCHA); a verified one sends `currentPassword`, or nothing when it has no
 * password and signed in within the last ten minutes.
 */
export interface ChangeEmailRequest {
  newEmail: string;
  currentPassword?: string;
  captchaToken?: string;
}

/** `applied` true: the address changed already and needs verifying. False: it waits at `pendingEmail`. */
export interface EmailChangeResponse {
  email: string;
  pendingEmail?: string;
  pendingExpiresAt?: string;
  applied: boolean;
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
  /**
   * False for an account created with "Continue with Google" that has never set a password.
   * Changing a password needs the current one, so settings points to "Forgot password" instead.
   */
  hasPassword: boolean;
  /**
   * Whether to offer the platform admin panel: a verified, active account listed in
   * `PLATFORM_ADMIN_EMAILS`. The server checks it again, with the sign-in's age, on every
   * admin request.
   */
  platformAdmin: boolean;
  /**
   * The public demo: a read-only session anyone can open. The dashboard shows a banner and greys
   * out every action; the server refuses every change regardless.
   */
  demo?: boolean;
}

export interface DemoSessionResponse {
  accessToken: string;
  expiresAt: string;
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
  /** The Consumer this endpoint belongs to. Absent leaves the assignment alone. */
  consumerId?: string;
}

export interface EndpointResponse {
  id: string;
  projectId: string;
  /** The Consumer this endpoint belongs to, or absent when it is the project's own. */
  consumerId?: string;
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
  /** Start of the current unbroken run of failed deliveries; absent once one succeeds. */
  failingSince?: string;
  /** Attempts in that run. */
  consecutiveFailures?: number;
  /**
   * When Railhook turned this endpoint off for continuous failure. Absent while it is on, and
   * absent when its owner turned it off - the two are different states, and only this one is
   * cleared by re-enabling.
   */
  autoDisabledAt?: string;
  /** Why, in words meant for the endpoint's owner. */
  autoDisabledReason?: string;
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
  /** Absent on a single delivery; the list fills it. */
  eventType?: string;
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

/** One of the customer's own users, grouping the endpoints registered for them. */
export interface ConsumerResponse {
  id: string;
  projectId: string;
  externalId: string;
  name: string;
  endpointCount: number;
  createdAt: string;
  updatedAt: string;
}

export interface ConsumerRequest {
  externalId: string;
  name?: string;
}

export interface PortalSessionRequest {
  ttlMinutes?: number;
  allowedOrigin?: string;
}

/** A new portal session; the token is in this response and nowhere else. */
export interface PortalSessionResponse {
  id: string;
  consumerId: string;
  url: string;
  token: string;
  allowedOrigin?: string;
  expiresAt: string;
}

/** What the portal learns about the session it runs in. */
export interface PortalSessionInfoResponse {
  consumerName: string;
  projectName: string;
  expiresAt: string;
  allowedOrigin?: string;
  eventTypes: string[];
}

export interface PortalEndpointRequest {
  url: string;
  description?: string;
  enabled?: boolean;
  eventTypes?: string[];
}

export interface PortalEndpointResponse {
  id: string;
  url: string;
  description?: string;
  enabled: boolean;
  eventTypes: string[];
  createdAt: string;
  updatedAt: string;
  /** Present only in the response that created or rotated it. */
  secret?: string;
  standardWebhooksSecret?: string;
}

export interface PortalDeliveryResponse {
  id: string;
  eventId: string;
  eventType?: string;
  endpointId: string;
  status: 'PENDING' | 'PROCESSING' | 'SUCCESS' | 'FAILED' | 'DLQ';
  attemptCount: number;
  maxAttempts: number;
  nextRetryAt?: string;
  lastAttemptAt?: string;
  succeededAt?: string;
  failedAt?: string;
  createdAt: string;
}

export interface EventResponse {
  id: string;
  projectId: string;
  eventType: string;
  payload: string;
  createdAt: string;
  deliveriesCreated?: number;
  /** The same Deliveries as `deliveriesCreated`, by status. Absent on a test-event response. */
  deliveryCounts?: DeliveryStatusCounts;
  /** Set only on a test-event response, and only under a project whose policy is WARN. */
  schemaWarnings?: string[];
}

export interface DeliveryStatusCounts {
  pending: number;
  processing: number;
  success: number;
  failed: number;
  dlq: number;
}

export interface SubscriptionResponse {
  id: string;
  projectId: string;
  endpointId: string;
  eventType: string;
  enabled: boolean;
  /**
   * Which HTTP statuses are worth another attempt, as a spec: `408,429,500-599`, `>=500`,
   * `5xx,!501`. The default reproduces what used to be hardcoded.
   */
  retryableStatuses?: string;
  createdAt: string;
  updatedAt: string;
}

// ─── MCP apps (OAuth connections to the MCP server) ─────────────────

/** Same two values an API key carries: a connected app is a key with a person behind it. */
export type McpGrantScope = 'READ_ONLY' | 'READ_WRITE';

/** What the consent screen shows about an app asking to connect. */
export interface McpConsentRequestResponse {
  requestId: string;
  /** Self-declared by the app; the redirect host is what identifies it. */
  clientName: string;
  clientUri: string | null;
  redirectHost: string;
  requestedScope: McpGrantScope;
  canGrantWrite: boolean;
  expiresAt: string;
}

export interface McpConsentApproveRequest {
  projectId: string;
  scope: McpGrantScope;
}

export interface McpConsentDecisionResponse {
  redirectUrl: string;
}

export interface McpGrantResponse {
  id: string;
  projectId: string;
  clientName: string;
  clientUri: string | null;
  redirectHost: string;
  scope: McpGrantScope;
  approvedByEmail: string | null;
  createdAt: string;
  lastUsedAt: string | null;
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
