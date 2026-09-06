#!/usr/bin/env node
/**
 * Derives the in-app API reference from the committed OpenAPI spec.
 *
 * `openapi.yaml` at the repo root is the single source of truth for the API and
 * CI already fails when it drifts from what springdoc serves
 * (OpenApiDriftIntegrationTest). Before this script the docs page restated that
 * spec by hand, so every endpoint change had to be copied into a React file and
 * nothing caught it when nobody did.
 *
 * The output is a *derived* index rather than the raw spec: $refs are resolved
 * to plain field lists so the page needs no resolver, springdoc's synthetic
 * `auth`/`pageable` argument objects are normalised into what actually goes on
 * the wire, and the result is a fifth of the size of the JSON-ified spec.
 *
 *   npm run docs:api-index           regenerate (commit the result)
 *   npm run docs:api-index -- --check  fail if the committed copy is stale
 */
import { readFileSync, writeFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, resolve } from 'node:path';
import yaml from 'js-yaml';

const here = dirname(fileURLToPath(import.meta.url));
const SPEC = resolve(here, '../../openapi.yaml');
const OUT = resolve(here, '../src/pages/docs/api-index.generated.json');

const METHODS = ['get', 'post', 'put', 'patch', 'delete', 'head', 'options'];
/** springdoc exposes the resolved auth principal as a query parameter. It is not one. */
const SYNTHETIC_PARAM_SCHEMAS = new Set(['AuthContext']);
/** Spring's Pageable arrives as one object parameter; on the wire it is three. */
const FLATTENED_PARAM_SCHEMAS = new Set(['Pageable']);

const spec = yaml.load(readFileSync(SPEC, 'utf8'));
const schemas = spec.components?.schemas ?? {};

const refName = (node) =>
  node && typeof node.$ref === 'string' ? node.$ref.split('/').pop() : undefined;

/** A human-readable type for one schema node, plus the schema names it points at. */
function typeOf(node, seen = new Set()) {
  if (!node) return { type: 'object', refs: [] };
  const ref = refName(node);
  if (ref) return { type: ref, refs: [ref] };
  if (node.type === 'array') {
    const inner = typeOf(node.items, seen);
    return { type: `${inner.type}[]`, refs: inner.refs };
  }
  const composite = node.oneOf || node.anyOf || node.allOf;
  if (Array.isArray(composite)) {
    const parts = composite.map((n) => typeOf(n, seen));
    return { type: parts.map((p) => p.type).join(' | '), refs: parts.flatMap((p) => p.refs) };
  }
  if (node.additionalProperties && node.type === 'object') {
    const inner = typeOf(node.additionalProperties, seen);
    return { type: `map<string, ${inner.type}>`, refs: inner.refs };
  }
  const base = node.type ?? 'object';
  return { type: node.format ? `${base} (${node.format})` : base, refs: [] };
}

const pending = new Set();
function note(refs) {
  refs.forEach((r) => pending.add(r));
}

/** Flattens one schema into the field rows the reference table renders. */
function fieldsOf(name) {
  const schema = schemas[name];
  if (!schema) return [];
  const required = new Set(schema.required ?? []);
  const props = schema.properties ?? {};
  return Object.entries(props).map(([field, node]) => {
    const { type, refs } = typeOf(node);
    note(refs);
    const row = { name: field, type, required: required.has(field) };
    if (node.description) row.description = node.description.trim();
    if (Array.isArray(node.enum)) row.values = node.enum.map(String);
    return row;
  });
}

/**
 * A representative value for one schema node, used to build the example bodies the reference
 * prints.
 *
 * The reference used to render a response as a flat field table, which is readable for a DTO
 * and unreadable for a paginated one: `PageAlertEventResponse` is eleven rows of Spring's
 * envelope — totalPages, pageable, sort, first, last, empty — with the thing the caller asked
 * for hidden behind `content: AlertEventResponse[]`. An example shows the envelope and the
 * item together, in the shape they arrive in.
 *
 * Values are chosen to be recognisably placeholders and to be the right *type*, since the
 * point is the shape. Enums use their first value; a `$ref` recurses, with a depth limit
 * because a schema may reference itself (a workflow node holding nodes) and would otherwise
 * not terminate.
 */
const EXAMPLE_MAX_DEPTH = 4;

function exampleFor(node, depth = 0) {
  if (!node || depth > EXAMPLE_MAX_DEPTH) return null;

  const ref = refName(node);
  if (ref) return exampleForSchema(ref, depth + 1);

  if (Array.isArray(node.enum) && node.enum.length) return node.enum[0];
  if (node.example !== undefined) return node.example;

  if (node.type === 'array') {
    const item = exampleFor(node.items, depth + 1);
    return item === null ? [] : [item];
  }

  const composite = node.oneOf || node.anyOf || node.allOf;
  if (Array.isArray(composite) && composite.length) return exampleFor(composite[0], depth + 1);

  if (node.type === 'object' || node.properties) {
    if (node.additionalProperties) {
      const inner = exampleFor(node.additionalProperties, depth + 1);
      return { key: inner === null ? 'value' : inner };
    }
    return objectExample(node, depth);
  }

  switch (node.format) {
    case 'uuid': return '3fa85f64-5717-4562-b3fc-2c963f66afa6';
    case 'date-time': return '2026-01-01T12:00:00Z';
    case 'date': return '2026-01-01';
    case 'uri': case 'url': return 'https://api.example.com/webhooks';
    case 'email': return 'you@example.com';
    case 'byte': return 'aGVsbG8=';
    default: break;
  }
  switch (node.type) {
    case 'string': return 'string';
    case 'integer': return 0;
    case 'number': return 0;
    case 'boolean': return true;
    default: return {};
  }
}

function objectExample(schema, depth) {
  const props = schema.properties ?? {};
  const out = {};
  for (const [field, node] of Object.entries(props)) {
    const value = exampleFor(node, depth + 1);
    if (value !== null) out[field] = value;
  }
  return out;
}

function exampleForSchema(name, depth = 0) {
  const schema = schemas[name];
  if (!schema || depth > EXAMPLE_MAX_DEPTH) return {};
  if (Array.isArray(schema.enum) && schema.enum.length) return schema.enum[0];
  return objectExample(schema, depth);
}

function paramsOf(op, pathLevel, path) {
  const out = [];
  for (const raw of [...(pathLevel ?? []), ...(op.parameters ?? [])]) {
    const ref = refName(raw.schema);
    if (ref && SYNTHETIC_PARAM_SCHEMAS.has(ref)) continue;
    if (ref && FLATTENED_PARAM_SCHEMAS.has(ref)) {
      for (const field of fieldsOf(ref)) {
        out.push({ name: field.name, in: raw.in, required: false, type: field.type });
      }
      continue;
    }
    const { type, refs } = typeOf(raw.schema);
    note(refs);
    const row = { name: raw.name, in: raw.in, required: Boolean(raw.required), type };
    if (raw.description) row.description = raw.description.trim();
    if (Array.isArray(raw.schema?.enum)) row.values = raw.schema.enum.map(String);
    out.push(row);
  }
  // springdoc omits a path variable the handler resolves indirectly (e.g. projectId
  // on the endpoint sub-resources). The URL template still requires it, so take the
  // template as authoritative rather than shipping a reference that cannot be called.
  for (const [, name] of path.matchAll(/\{([^}]+)\}/g)) {
    if (!out.some((p) => p.in === 'path' && p.name === name)) {
      out.push({ name, in: 'path', required: true, type: 'string' });
    }
  }
  return out.sort((a, b) => (a.in === b.in ? 0 : a.in === 'path' ? -1 : b.in === 'path' ? 1 : 0));
}

function bodyOf(op) {
  const content = op.requestBody?.content;
  if (!content) return undefined;
  const [contentType, media] =
    Object.entries(content).find(([k]) => k.includes('json')) ?? Object.entries(content)[0];
  const { type, refs } = typeOf(media?.schema);
  note(refs);
  return { contentType, type, required: Boolean(op.requestBody.required) };
}

function responsesOf(op) {
  return Object.entries(op.responses ?? {}).map(([status, res]) => {
    const media = res.content
      ? Object.entries(res.content).find(([k]) => k.includes('json'))?.[1] ??
        Object.values(res.content)[0]
      : undefined;
    const { type, refs } = media ? typeOf(media.schema) : { type: undefined, refs: [] };
    note(refs);
    const row = { status, description: (res.description ?? '').trim() };
    if (type) row.type = type;
    return row;
  });
}

const tagDescriptions = new Map((spec.tags ?? []).map((t) => [t.name, (t.description ?? '').trim()]));
const groups = new Map();
const slug = (s) => s.toLowerCase().replace(/[^a-z0-9]+/g, '-').replace(/^-|-$/g, '');

for (const [path, item] of Object.entries(spec.paths ?? {})) {
  for (const method of METHODS) {
    const op = item[method];
    if (!op) continue;
    const tag = op.tags?.[0] ?? 'Other';
    const operation = {
      id: op.operationId ?? `${method}${slug(path)}`,
      method: method.toUpperCase(),
      path,
      summary: (op.summary ?? '').trim(),
      auth: (op.security ?? spec.security ?? []).flatMap((s) => Object.keys(s)),
      params: paramsOf(op, item.parameters, path),
      responses: responsesOf(op),
    };
    if (op.description) operation.description = op.description.trim();
    if (op.deprecated) operation.deprecated = true;
    const body = bodyOf(op);
    if (body) operation.body = body;

    if (!groups.has(tag)) {
      groups.set(tag, { id: slug(tag), name: tag, description: tagDescriptions.get(tag) ?? '', operations: [] });
    }
    groups.get(tag).operations.push(operation);
  }
}

// Resolve the schemas the operations reach, then the ones those reach, and so on.
const resolved = {};
while (pending.size > 0) {
  const [name] = pending;
  pending.delete(name);
  if (name in resolved || !schemas[name]) continue;
  // fieldsOf() adds whatever this schema points at back into `pending`, so the
  // loop widens until the closure of everything the operations can reach is in.
  resolved[name] = fieldsOf(name);
}

const index = {
  title: spec.info?.title ?? 'API',
  version: spec.info?.version ?? '',
  operationCount: [...groups.values()].reduce((n, g) => n + g.operations.length, 0),
  security: Object.entries(spec.components?.securitySchemes ?? {}).map(([id, s]) => ({
    id,
    kind: s.type === 'http' ? `${s.scheme} ${s.bearerFormat ?? ''}`.trim() : (s.name ?? s.type),
    description: (s.description ?? '').trim(),
  })),
  groups: [...groups.values()].sort((a, b) => a.name.localeCompare(b.name)),
  schemas: Object.fromEntries(Object.entries(resolved).sort(([a], [b]) => a.localeCompare(b))),
  // One example body per schema, so the page can print the shape rather than tabulate it.
  examples: Object.fromEntries(
    Object.keys(resolved)
      .sort((a, b) => a.localeCompare(b))
      .map((name) => [name, exampleForSchema(name)]),
  ),
};

const json = `${JSON.stringify(index, null, 2)}\n`;

if (process.argv.includes('--check')) {
  let current = '';
  try {
    current = readFileSync(OUT, 'utf8');
  } catch {
    /* missing counts as stale */
  }
  if (current !== json) {
    console.error('src/pages/docs/api-index.generated.json is stale. Run: npm run docs:api-index');
    process.exit(1);
  }
  console.log(`api-index.generated.json is up to date (${index.operationCount} operations).`);
} else {
  writeFileSync(OUT, json);
  console.log(
    `Wrote ${OUT} — ${index.operationCount} operations, ${index.groups.length} groups, ` +
      `${Object.keys(index.schemas).length} schemas, ${(json.length / 1024).toFixed(0)} KB.`
  );
}
