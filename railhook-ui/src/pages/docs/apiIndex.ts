/**
 * The shape of `api-index.generated.json`, which
 * `scripts/generate-docs-api-index.mjs` derives from the committed
 * `openapi.yaml`. Nothing here is hand-maintained: to change what the API
 * reference says, change the API and run `npm run docs:api-index`.
 */

export interface SpecField {
  name: string;
  type: string;
  required: boolean;
  description?: string;
  values?: string[];
}

export interface SpecParam extends Omit<SpecField, 'required'> {
  in: string;
  required: boolean;
}

export interface SpecResponse {
  status: string;
  description: string;
  type?: string;
}

export interface SpecOperation {
  id: string;
  method: string;
  path: string;
  summary: string;
  description?: string;
  deprecated?: boolean;
  auth: string[];
  params: SpecParam[];
  body?: { contentType: string; type: string; required: boolean };
  responses: SpecResponse[];
}

export interface SpecGroup {
  id: string;
  name: string;
  description: string;
  operations: SpecOperation[];
}

export interface SpecSecurityScheme {
  id: string;
  kind: string;
  description: string;
}

export interface ApiIndex {
  /** One example body per schema, generated from the spec. */
  examples: Record<string, unknown>;
  title: string;
  version: string;
  operationCount: number;
  security: SpecSecurityScheme[];
  groups: SpecGroup[];
  schemas: Record<string, SpecField[]>;
}

/**
 * The reference data is ~160 KB minified — worth its own chunk so opening a
 * guide never pays for it. The docs route is already `React.lazy`; this keeps
 * the spec one level further out.
 */
export async function loadApiIndex(): Promise<ApiIndex> {
  const mod = await import('./api-index.generated.json');
  return (mod.default ?? mod) as unknown as ApiIndex;
}

/** The Redoc site GitHub Pages publishes from the same `openapi.yaml`. */
export const CANONICAL_REFERENCE_URL = 'https://vadymkykalo.github.io/railhook/';
export const CANONICAL_SPEC_URL = 'https://vadymkykalo.github.io/railhook/openapi.yaml';
