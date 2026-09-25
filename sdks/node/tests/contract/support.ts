// The SDK has no register/login surface, so the throwaway tenant is bootstrapped with raw fetch.

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

// A login probe: actuator is not published to the host and /v3/api-docs is off by default, so
// either would silently skip the suite on a healthy stack.
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
