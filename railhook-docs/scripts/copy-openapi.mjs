#!/usr/bin/env node
/**
 * Copies the repository's committed `openapi.yaml` into `public/`, where the API reference
 * page loads it from (`/docs/openapi.yaml`).
 *
 * Copied at build rather than committed a second time: the root copy is the one
 * `OpenApiDriftIntegrationTest` holds to the running API, so a duplicate here could only ever
 * be the stale one. `public/openapi.yaml` is gitignored.
 */
import { copyFileSync, mkdirSync } from 'node:fs';
import { fileURLToPath } from 'node:url';

const from = fileURLToPath(new URL('../../openapi.yaml', import.meta.url));
const toDir = fileURLToPath(new URL('../public/', import.meta.url));

mkdirSync(toDir, { recursive: true });
copyFileSync(from, `${toDir}openapi.yaml`);
console.log('Copied openapi.yaml into public/.');
