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

/** Unverified: captchaToken; verified: currentPassword, or nothing if passwordless and signed in recently. */
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
  /** False by default (EMAIL_ENABLED=false): never claim an invite or reset "was sent". */
  emailDeliveryEnabled: boolean;
  hasPassword: boolean;
  platformAdmin: boolean;
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

/** BOTH is the default: extra headers cost a receiver nothing. */
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
  failingSince?: string;
  consecutiveFailures?: number;
  /** Absent when its owner turned it off; only an auto-disable is cleared by re-enabling. */
  autoDisabledAt?: string;
  autoDisabledReason?: string;
  createdAt: string;
  updatedAt: string;
  secret?: string;
  signatureScheme?: SignatureScheme;
  /** Present only where secret is: at creation and rotation. */
  standardWebhooksSecret?: string;
}

export interface DeliveryResponse {
  id: string;
  eventId: string;
  eventType?: string;
  endpointId: string;
  subscriptionId: string;
  status: 'PENDING' | 'PROCESSING' | 'SUCCESS' | 'FAILED' | 'DLQ' | 'CANCELLED';
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

export interface PortalSessionResponse {
  id: string;
  consumerId: string;
  url: string;
  token: string;
  allowedOrigin?: string;
  expiresAt: string;
}

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
  secret?: string;
  standardWebhooksSecret?: string;
}

export interface PortalDeliveryResponse {
  id: string;
  eventId: string;
  eventType?: string;
  endpointId: string;
  status: 'PENDING' | 'PROCESSING' | 'SUCCESS' | 'FAILED' | 'DLQ' | 'CANCELLED';
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
  deliveryCounts?: DeliveryStatusCounts;
  schemaWarnings?: string[];
}

export interface DeliveryStatusCounts {
  pending: number;
  processing: number;
  success: number;
  failed: number;
  dlq: number;
  cancelled: number;
}

export interface SubscriptionResponse {
  id: string;
  projectId: string;
  endpointId: string;
  eventType: string;
  enabled: boolean;
  retryableStatuses?: string;
  createdAt: string;
  updatedAt: string;
}

export type McpGrantScope = 'READ_ONLY' | 'READ_WRITE';

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

export type ProviderType = 'GENERIC' | 'GITHUB' | 'GITLAB' | 'STRIPE' | 'SHOPIFY' | 'SLACK' | 'TWILIO'
  | 'SQUARE' | 'ADYEN' | 'SENDGRID' | 'HUBSPOT';
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

export interface DlqStatsResponse {
  totalItems: number;
  last24Hours: number;
  last7Days: number;
}

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

/** Omitted on a request means TEMPLATE. */
export type TransformationKind = 'TEMPLATE' | 'JAVASCRIPT';

export interface TransformationRequest {
  name: string;
  description?: string;
  template: string;
  kind?: TransformationKind;
  enabled?: boolean;
}

export interface TransformationResponse {
  id: string;
  projectId: string;
  name: string;
  description?: string;
  template: string;
  kind: TransformationKind;
  version: number;
  enabled: boolean;
  subscriptionCount: number;
  destinationCount: number;
  createdAt: string;
  updatedAt: string;
}

/** `template` is only populated by the single-version endpoint. */
export interface TransformationVersionResponse {
  id: string;
  transformationId: string;
  version: number;
  template?: string;
  current: boolean;
  restoredFromVersion?: number;
  createdBy?: string;
  createdByEmail?: string;
  createdAt: string;
}

export interface TransformationVersionDiffResponse {
  transformationId: string;
  leftVersion: number;
  rightVersion: number;
  leftCreatedAt: string;
  rightCreatedAt: string;
  leftTemplate: string;
  rightTemplate: string;
  diffs: JsonDiffEntry[];
}

export interface JsonDiffEntry {
  path: string;
  type: 'ADDED' | 'REMOVED' | 'CHANGED';
  leftValue?: unknown;
  rightValue?: unknown;
}

export interface IncomingBulkReplayResponse {
  status: string;
  sourceId: string;
  eventsReplayed: number;
  totalForwardAttempts: number;
}
