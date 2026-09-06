import * as https from 'https';
import * as http from 'http';
import { URL } from 'url';
import {
  HookflowConfig,
  Event,
  EventResponse,
  Endpoint,
  EndpointCreateParams,
  EndpointUpdateParams,
  EndpointListParams,
  Subscription,
  SubscriptionCreateParams,
  Delivery,
  DeliveryAttempt,
  DeliveryListParams,
  PaginatedResponse,
  EndpointTestResult,
  RateLimitInfo,
  IncomingSource,
  IncomingSourceCreateParams,
  IncomingSourceUpdateParams,
  IncomingDestination,
  IncomingDestinationCreateParams,
  IncomingDestinationUpdateParams,
  IncomingEvent,
  IncomingEventListParams,
  IncomingForwardAttempt,
  ReplayEventResponse,
} from './types';
import {
  HookflowError,
  AuthenticationError,
  RateLimitError,
  ValidationError,
  NotFoundError,
} from './errors';

const DEFAULT_BASE_URL = 'http://localhost:8080';
const DEFAULT_TIMEOUT = 30000;
const SDK_VERSION = '2.11.0';

export class Hookflow {
  private readonly apiKey: string;
  private readonly baseUrl: string;
  private readonly timeout: number;

  public readonly events: Events;
  public readonly endpoints: Endpoints;
  public readonly subscriptions: Subscriptions;
  public readonly deliveries: Deliveries;
  public readonly incomingSources: IncomingSources;
  public readonly incomingEvents: IncomingEvents;

  constructor(config: HookflowConfig) {
    if (!config.apiKey) {
      throw new Error('API key is required');
    }

    this.apiKey = config.apiKey;
    this.baseUrl = config.baseUrl || DEFAULT_BASE_URL;
    this.timeout = config.timeout || DEFAULT_TIMEOUT;

    this.events = new Events(this);
    this.endpoints = new Endpoints(this);
    this.subscriptions = new Subscriptions(this);
    this.deliveries = new Deliveries(this);
    this.incomingSources = new IncomingSources(this);
    this.incomingEvents = new IncomingEvents(this);
  }

  async request<T>(
    method: string,
    path: string,
    body?: unknown,
    idempotencyKey?: string
  ): Promise<T> {
    const url = new URL(path, this.baseUrl);
    const isHttps = url.protocol === 'https:';
    const lib = isHttps ? https : http;

    const headers: Record<string, string> = {
      'X-API-Key': this.apiKey,
      'Content-Type': 'application/json',
      'User-Agent': `hookflow-node/${SDK_VERSION}`,
    };

    if (idempotencyKey) {
      headers['Idempotency-Key'] = idempotencyKey;
    }

    const options: http.RequestOptions = {
      method,
      hostname: url.hostname,
      port: url.port || (isHttps ? 443 : 80),
      path: url.pathname + url.search,
      headers,
      timeout: this.timeout,
    };

    return new Promise((resolve, reject) => {
      const req = lib.request(options, (res) => {
        let data = '';
        res.on('data', (chunk) => (data += chunk));
        res.on('end', () => {
          try {
            const rateLimitInfo = this.extractRateLimitInfo(res.headers);
            
            if (res.statusCode === 204) {
              resolve(undefined as T);
              return;
            }

            const parsed = data ? JSON.parse(data) : {};

            if (res.statusCode && res.statusCode >= 400) {
              reject(this.handleError(res.statusCode, parsed, rateLimitInfo));
              return;
            }

            resolve(parsed as T);
          } catch (err) {
            reject(new HookflowError('Failed to parse response', 500));
          }
        });
      });

      req.on('error', (err) => {
        reject(new HookflowError(err.message, 0, 'network_error'));
      });

      req.on('timeout', () => {
        req.destroy();
        reject(new HookflowError('Request timeout', 0, 'timeout'));
      });

      if (body) {
        req.write(JSON.stringify(body));
      }

      req.end();
    });
  }

  /**
   * Generic GET request to any API path.
   * Use this for endpoints not yet covered by the SDK.
   */
  async get<T = unknown>(path: string): Promise<T> {
    return this.request<T>('GET', path);
  }

  /**
   * Generic POST request to any API path.
   * Use this for endpoints not yet covered by the SDK.
   */
  async post<T = unknown>(path: string, body?: unknown, idempotencyKey?: string): Promise<T> {
    return this.request<T>('POST', path, body, idempotencyKey);
  }

  /**
   * Generic PUT request to any API path.
   * Use this for endpoints not yet covered by the SDK.
   */
  async put<T = unknown>(path: string, body?: unknown): Promise<T> {
    return this.request<T>('PUT', path, body);
  }

  /**
   * Generic PATCH request to any API path.
   * Use this for endpoints not yet covered by the SDK.
   */
  async patch<T = unknown>(path: string, body?: unknown): Promise<T> {
    return this.request<T>('PATCH', path, body);
  }

  /**
   * Generic DELETE request to any API path.
   * Use this for endpoints not yet covered by the SDK.
   */
  async delete<T = void>(path: string): Promise<T> {
    return this.request<T>('DELETE', path);
  }

  private extractRateLimitInfo(headers: http.IncomingHttpHeaders): RateLimitInfo | undefined {
    const limit = headers['x-ratelimit-limit'];
    const remaining = headers['x-ratelimit-remaining'];
    const reset = headers['x-ratelimit-reset'];

    if (limit && remaining && reset) {
      return {
        limit: parseInt(limit as string, 10),
        remaining: parseInt(remaining as string, 10),
        reset: parseInt(reset as string, 10),
      };
    }
    return undefined;
  }

  private handleError(
    status: number,
    body: Record<string, unknown>,
    rateLimitInfo?: RateLimitInfo
  ): HookflowError {
    const message = (body.message as string) || 'Unknown error';

    switch (status) {
      case 401:
        return new AuthenticationError(message);
      case 404:
        return new NotFoundError(message);
      case 429:
        return new RateLimitError(
          message,
          // `reset` is a Unix timestamp in seconds (see RateLimitInfo), so the
          // fallback has to be in seconds too, not Date.now() + 60000.
          rateLimitInfo || { limit: 0, remaining: 0, reset: Math.floor(Date.now() / 1000) + 60 }
        );
      case 400:
        return new ValidationError(
          message,
          (body.fieldErrors as Record<string, string>) || {}
        );
      default:
        return new HookflowError(message, status, body.error as string);
    }
  }
}

class Events {
  constructor(private client: Hookflow) {}

  async send(event: Event, idempotencyKey?: string): Promise<EventResponse> {
    return this.client.request<EventResponse>(
      'POST',
      '/api/v1/events',
      event,
      idempotencyKey
    );
  }
}

class Endpoints {
  constructor(private client: Hookflow) {}

  async create(projectId: string, params: EndpointCreateParams): Promise<Endpoint> {
    return this.client.request<Endpoint>(
      'POST',
      `/api/v1/projects/${projectId}/endpoints`,
      params
    );
  }

  async get(projectId: string, endpointId: string): Promise<Endpoint> {
    return this.client.request<Endpoint>(
      'GET',
      `/api/v1/projects/${projectId}/endpoints/${endpointId}`
    );
  }

  /**
   * Lists a project's endpoints.
   *
   * The API returns a Spring page envelope here, not a bare array — the
   * endpoints themselves are in `.content`.
   */
  async list(
    projectId: string,
    params: EndpointListParams = {}
  ): Promise<PaginatedResponse<Endpoint>> {
    const query = new URLSearchParams();
    if (params.page !== undefined) query.set('page', params.page.toString());
    if (params.size !== undefined) query.set('size', params.size.toString());

    const queryString = query.toString();
    const path = `/api/v1/projects/${projectId}/endpoints${queryString ? `?${queryString}` : ''}`;

    return this.client.request<PaginatedResponse<Endpoint>>('GET', path);
  }

  async update(
    projectId: string,
    endpointId: string,
    params: EndpointUpdateParams
  ): Promise<Endpoint> {
    return this.client.request<Endpoint>(
      'PUT',
      `/api/v1/projects/${projectId}/endpoints/${endpointId}`,
      params
    );
  }

  async delete(projectId: string, endpointId: string): Promise<void> {
    return this.client.request<void>(
      'DELETE',
      `/api/v1/projects/${projectId}/endpoints/${endpointId}`
    );
  }

  async rotateSecret(projectId: string, endpointId: string): Promise<Endpoint> {
    return this.client.request<Endpoint>(
      'POST',
      `/api/v1/projects/${projectId}/endpoints/${endpointId}/rotate-secret`
    );
  }

  async test(projectId: string, endpointId: string): Promise<EndpointTestResult> {
    return this.client.request<EndpointTestResult>(
      'POST',
      `/api/v1/projects/${projectId}/endpoints/${endpointId}/test`
    );
  }
}

class Subscriptions {
  constructor(private client: Hookflow) {}

  async create(projectId: string, params: SubscriptionCreateParams): Promise<Subscription> {
    return this.client.request<Subscription>(
      'POST',
      `/api/v1/projects/${projectId}/subscriptions`,
      params
    );
  }

  async get(projectId: string, subscriptionId: string): Promise<Subscription> {
    return this.client.request<Subscription>(
      'GET',
      `/api/v1/projects/${projectId}/subscriptions/${subscriptionId}`
    );
  }

  async list(projectId: string): Promise<Subscription[]> {
    return this.client.request<Subscription[]>(
      'GET',
      `/api/v1/projects/${projectId}/subscriptions`
    );
  }

  async update(
    projectId: string,
    subscriptionId: string,
    params: Partial<SubscriptionCreateParams>
  ): Promise<Subscription> {
    return this.client.request<Subscription>(
      'PUT',
      `/api/v1/projects/${projectId}/subscriptions/${subscriptionId}`,
      params
    );
  }

  async delete(projectId: string, subscriptionId: string): Promise<void> {
    return this.client.request<void>(
      'DELETE',
      `/api/v1/projects/${projectId}/subscriptions/${subscriptionId}`
    );
  }
}

class Deliveries {
  constructor(private client: Hookflow) {}

  async get(deliveryId: string): Promise<Delivery> {
    return this.client.request<Delivery>('GET', `/api/v1/deliveries/${deliveryId}`);
  }

  async list(
    projectId: string,
    params: DeliveryListParams = {}
  ): Promise<PaginatedResponse<Delivery>> {
    const query = new URLSearchParams();
    if (params.status) query.set('status', params.status);
    if (params.endpointId) query.set('endpointId', params.endpointId);
    if (params.fromDate) query.set('fromDate', params.fromDate);
    if (params.toDate) query.set('toDate', params.toDate);
    if (params.page !== undefined) query.set('page', params.page.toString());
    if (params.size !== undefined) query.set('size', params.size.toString());

    const queryString = query.toString();
    const path = `/api/v1/deliveries/projects/${projectId}${queryString ? `?${queryString}` : ''}`;

    return this.client.request<PaginatedResponse<Delivery>>('GET', path);
  }

  async getAttempts(deliveryId: string): Promise<DeliveryAttempt[]> {
    return this.client.request<DeliveryAttempt[]>(
      'GET',
      `/api/v1/deliveries/${deliveryId}/attempts`
    );
  }

  async replay(deliveryId: string): Promise<void> {
    return this.client.request<void>('POST', `/api/v1/deliveries/${deliveryId}/replay`);
  }
}

class IncomingSources {
  constructor(private client: Hookflow) {}

  async create(projectId: string, params: IncomingSourceCreateParams): Promise<IncomingSource> {
    return this.client.request<IncomingSource>(
      'POST',
      `/api/v1/projects/${projectId}/incoming-sources`,
      params
    );
  }

  async get(projectId: string, sourceId: string): Promise<IncomingSource> {
    return this.client.request<IncomingSource>(
      'GET',
      `/api/v1/projects/${projectId}/incoming-sources/${sourceId}`
    );
  }

  async list(projectId: string): Promise<PaginatedResponse<IncomingSource>> {
    return this.client.request<PaginatedResponse<IncomingSource>>(
      'GET',
      `/api/v1/projects/${projectId}/incoming-sources`
    );
  }

  async update(projectId: string, sourceId: string, params: IncomingSourceUpdateParams): Promise<IncomingSource> {
    return this.client.request<IncomingSource>(
      'PUT',
      `/api/v1/projects/${projectId}/incoming-sources/${sourceId}`,
      params
    );
  }

  async delete(projectId: string, sourceId: string): Promise<void> {
    return this.client.request<void>(
      'DELETE',
      `/api/v1/projects/${projectId}/incoming-sources/${sourceId}`
    );
  }

  // ── Destinations ──

  async createDestination(
    projectId: string,
    sourceId: string,
    params: IncomingDestinationCreateParams
  ): Promise<IncomingDestination> {
    return this.client.request<IncomingDestination>(
      'POST',
      `/api/v1/projects/${projectId}/incoming-sources/${sourceId}/destinations`,
      params
    );
  }

  async getDestination(
    projectId: string,
    sourceId: string,
    destinationId: string
  ): Promise<IncomingDestination> {
    return this.client.request<IncomingDestination>(
      'GET',
      `/api/v1/projects/${projectId}/incoming-sources/${sourceId}/destinations/${destinationId}`
    );
  }

  async listDestinations(
    projectId: string,
    sourceId: string
  ): Promise<PaginatedResponse<IncomingDestination>> {
    return this.client.request<PaginatedResponse<IncomingDestination>>(
      'GET',
      `/api/v1/projects/${projectId}/incoming-sources/${sourceId}/destinations`
    );
  }

  async updateDestination(
    projectId: string,
    sourceId: string,
    destinationId: string,
    params: IncomingDestinationUpdateParams
  ): Promise<IncomingDestination> {
    return this.client.request<IncomingDestination>(
      'PUT',
      `/api/v1/projects/${projectId}/incoming-sources/${sourceId}/destinations/${destinationId}`,
      params
    );
  }

  async deleteDestination(
    projectId: string,
    sourceId: string,
    destinationId: string
  ): Promise<void> {
    return this.client.request<void>(
      'DELETE',
      `/api/v1/projects/${projectId}/incoming-sources/${sourceId}/destinations/${destinationId}`
    );
  }
}

class IncomingEvents {
  constructor(private client: Hookflow) {}

  async list(
    projectId: string,
    params: IncomingEventListParams = {}
  ): Promise<PaginatedResponse<IncomingEvent>> {
    const query = new URLSearchParams();
    if (params.sourceId) query.set('sourceId', params.sourceId);
    if (params.page !== undefined) query.set('page', params.page.toString());
    if (params.size !== undefined) query.set('size', params.size.toString());

    const queryString = query.toString();
    const path = `/api/v1/projects/${projectId}/incoming-events${queryString ? `?${queryString}` : ''}`;

    return this.client.request<PaginatedResponse<IncomingEvent>>('GET', path);
  }

  async get(projectId: string, eventId: string): Promise<IncomingEvent> {
    return this.client.request<IncomingEvent>(
      'GET',
      `/api/v1/projects/${projectId}/incoming-events/${eventId}`
    );
  }

  async getAttempts(
    projectId: string,
    eventId: string
  ): Promise<PaginatedResponse<IncomingForwardAttempt>> {
    return this.client.request<PaginatedResponse<IncomingForwardAttempt>>(
      'GET',
      `/api/v1/projects/${projectId}/incoming-events/${eventId}/attempts`
    );
  }

  async replay(projectId: string, eventId: string): Promise<ReplayEventResponse> {
    return this.client.request<ReplayEventResponse>(
      'POST',
      `/api/v1/projects/${projectId}/incoming-events/${eventId}/replay`
    );
  }
}
