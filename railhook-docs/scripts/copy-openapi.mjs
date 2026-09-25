#!/usr/bin/env node
// Copied, not committed twice: a second copy could only ever be the stale one.
import { copyFileSync, mkdirSync } from 'node:fs';
import { fileURLToPath } from 'node:url';

const from = fileURLToPath(new URL('../../openapi.yaml', import.meta.url));
const toDir = fileURLToPath(new URL('../public/', import.meta.url));

mkdirSync(toDir, { recursive: true });
copyFileSync(from, `${toDir}openapi.yaml`);
console.log('Copied openapi.yaml into public/.');
