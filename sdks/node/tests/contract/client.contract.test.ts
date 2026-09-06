// Contract tests: run the node SDK against a REAL API instance and assert
// its request/response shapes still match what the API actually does. The
// cases in src/__tests__/ stub the HTTP layer entirely — they'd stay
// green even if the API renamed a field out from under this SDK. These
// exist to catch that drift instead of a user finding it in production.
//
// Run with: npm run test:contract (requires CONTRACT_API_BASE_URL reachable
// — defaults to http://localhost:8080, i.e. `make up`). See
// tests/contract/README.md.
//
// The repo now commits an OpenAPI spec (openapi.yaml at the repo root);
// generating these expectations from the spec would be preferable to
// hand-asserting field-by-field, since it would catch drift at build time
// rather than only when this suite happens to run. This hand-asserted
// suite is the accepted fallback until that generation exists.
import { Railhook } from '../../src/index';
import { bootstrapContractProject, isApiReachable, BASE_URL, ContractContext } from './support';

let apiReachable = false;
let ctx: ContractContext;
let client: Railhook;

beforeAll(async () => {
  apiReachable = await isApiReachable();
  if (!apiReachable) {
    // eslint-disable-next-line no-console
    console.warn(
      `[contract] ${BASE_URL} is not reachable (tried POST /api/v1/auth/login) — ` +
        'skipping contract assertions. Run `make up && make wait-healthy` first. See tests/contract/README.md.'
    );
    return;
  }
  ctx = await bootstrapContractProject('sdk-client');
  client = new Railhook({ apiKey: ctx.apiKey, baseUrl: BASE_URL });
});

describe('Railhook client contract', () => {
  test('endpoints.create returns the shape Endpoint declares', async () => {
    if (!apiReachable) return;
    const endpoint = await client.endpoints.create(ctx.projectId, {
      url: 'https://example.com/webhook',
      description: 'contract test endpoint',
      enabled: true,
    });

    expect(typeof endpoint.id).toBe('string');
    expect(endpoint.projectId).toBe(ctx.projectId);
    expect(endpoint.url).toBe('https://example.com/webhook');
    expect(typeof endpoint.enabled).toBe('boolean');
    expect(typeof endpoint.createdAt).toBe('string');
  });

  test('subscriptions.create returns the shape Subscription declares', async () => {
    if (!apiReachable) return;
    const endpoint = await client.endpoints.create(ctx.projectId, {
      url: 'https://example.com/webhook2',
    });
    const subscription = await client.subscriptions.create(ctx.projectId, {
      endpointId: endpoint.id,
      eventType: 'contract.test.created',
      orderingEnabled: false,
    });

    expect(typeof subscription.id).toBe('string');
    expect(subscription.endpointId).toBe(endpoint.id);
    expect(subscription.eventType).toBe('contract.test.created');
    expect(typeof subscription.enabled).toBe('boolean');
    expect(typeof subscription.orderingEnabled).toBe('boolean');
    expect(typeof subscription.maxAttempts).toBe('number');
    // transformationId / transformationName are part of SubscriptionResponse
    // and the SDK's Subscription type now declares both. Asserted as *present*
    // (they are null for a subscription with no transformation) so the mirror
    // cannot quietly fall behind the response again.
    expect('transformationId' in subscription).toBe(true);
    expect('transformationName' in subscription).toBe(true);
  });

  test('events.send accepted and fans out to the subscribed endpoint (deliveriesCreated=1)', async () => {
    if (!apiReachable) return;
    const endpoint = await client.endpoints.create(ctx.projectId, {
      url: 'https://example.com/webhook3',
    });
    await client.subscriptions.create(ctx.projectId, {
      endpointId: endpoint.id,
      eventType: 'contract.test.event_send',
    });

    const response = await client.events.send({
      type: 'contract.test.event_send',
      data: { hello: 'world' },
    });

    expect(typeof response.eventId).toBe('string');
    expect(response.type).toBe('contract.test.event_send');
    expect(response.deliveriesCreated).toBe(1);
  });

  test('deliveries.list returns a paginated response for the project', async () => {
    if (!apiReachable) return;
    const page = await client.deliveries.list(ctx.projectId, { size: 5 });
    expect(Array.isArray(page.content)).toBe(true);
    expect(typeof page.totalElements).toBe('number');
  });

  test('an invalid API key is rejected with a 401-shaped AuthenticationError', async () => {
    if (!apiReachable) return;
    const badClient = new Railhook({ apiKey: 'not-a-real-key', baseUrl: BASE_URL });
    await expect(
      badClient.events.send({ type: 'contract.test.bad_key', data: {} })
    ).rejects.toMatchObject({ status: 401, name: 'AuthenticationError' });
  });
});
