// Shared bootstrap for the node SDK's contract suite. These tests exercise
// the Railhook client against a REAL API instance (not stubbed HTTP, unlike
// src/__tests__/*) — see tests/contract/README.md for how to run them and
// what "real instance" means in CI.
//
// The Railhook client itself is API-key scoped (it has no register/login/
// create-project surface — see src/client.ts), so bootstrapping a throwaway
// tenant needs a couple of raw fetch calls against the JWT-authenticated
// endpoints before the SDK proper takes over.
//
// Requires Node >= 18 (global fetch) — CI runs Node 20 (see ci.yml), which
// is already the floor for this repo's tooling even though the published
// SDK itself supports down to Node 16 at runtime for consumers.

export const BASE_URL = process.env.CONTRACT_API_BASE_URL || 'http://localhost:8080';
const PASSWORD = 'ContractTest!2026x'; // meets AuthController's complexity policy

export interface ContractContext {
  projectId: string;
  apiKey: string;
  accessToken: string;
}

async function json(res: Response, label: string): Promise<any> {
  const text = await res.text();
  if (!res.ok) {
    throw new Error(`${label} failed: HTTP ${res.status} ${text}`);
  }
  return text ? JSON.parse(text) : undefined;
}

/**
 * True once verified reachable; checked once via isApiReachable().
 *
 * Probes with an intentionally invalid login: any HTTP response at all proves
 * the API is answering.
 *
 * Deliberately does NOT hit /actuator/health/liveness: under `make up`
 * (docker-compose.yml), actuator is served on its own MANAGEMENT_PORT
 * (8082) which is never published to the host — and on the main port
 * /actuator/health is a 500, not a 404, because nothing maps it. Nor
 * /v3/api-docs: springdoc is only permitAll when SWAGGER_ENABLED=true
 * (SecurityConfig.java) and .env.dist ships it false, so probing it reports
 * a perfectly healthy stack as unreachable and silently skips this whole
 * suite. /api/v1/auth/login is permitAll unconditionally.
 */
export async function isApiReachable(): Promise<boolean> {
  try {
    const res = await fetch(`${BASE_URL}/api/v1/auth/login`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: '{}',
      signal: AbortSignal.timeout(3000),
    });
    return res.status > 0;
  } catch {
    return false;
  }
}

export async function bootstrapContractProject(prefix: string): Promise<ContractContext> {
  const suffix = `${Date.now()}-${Math.floor(Math.random() * 1e6)}`;
  const registerRes = await fetch(`${BASE_URL}/api/v1/auth/register`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({
      email: `${prefix}-${suffix}@node-contract-test.invalid`,
      password: PASSWORD,
      fullName: `Node Contract Test ${prefix}`,
      organizationName: `node-contract-${suffix}`.slice(0, 100),
    }),
  });
  const auth = await json(registerRes, 'register');
  const accessToken = auth.accessToken as string;
  const authHeaders = { 'Content-Type': 'application/json', Authorization: `Bearer ${accessToken}` };

  const projectRes = await fetch(`${BASE_URL}/api/v1/projects`, {
    method: 'POST',
    headers: authHeaders,
    body: JSON.stringify({ name: `node-contract-${suffix}`.slice(0, 100) }),
  });
  const project = await json(projectRes, 'create project');

  const keyRes = await fetch(`${BASE_URL}/api/v1/projects/${project.id}/api-keys`, {
    method: 'POST',
    headers: authHeaders,
    body: JSON.stringify({ name: `node-contract-key-${suffix}`, scope: 'READ_WRITE' }),
  });
  const apiKeyResponse = await json(keyRes, 'create api key');

  return { projectId: project.id as string, apiKey: apiKeyResponse.key as string, accessToken };
}
