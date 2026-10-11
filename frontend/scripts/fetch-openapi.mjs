#!/usr/bin/env node
/**
 * Refreshes the committed OpenAPI snapshot from a running backend.
 *
 * The snapshot, not the live endpoint, is what the client is generated from. Three reasons:
 * CI has no backend to ask; a developer should be able to build offline; and a commit should
 * record which contract the frontend was built against, so a broken build can be bisected.
 *
 * The cost is that the snapshot can go stale. That is covered on the backend side —
 * OpenApiSnapshotTest compares this file against the live document and fails the backend
 * build when they differ, naming this script as the fix. So the two halves cannot drift
 * without someone being told.
 *
 *   npm run api:fetch                      # against http://localhost:8081
 *   npm run api:fetch -- http://host:port  # against something else
 */
import { writeFileSync, readFileSync, existsSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';

const root = join(dirname(fileURLToPath(import.meta.url)), '..');
const target = join(root, 'openapi.json');

const base = (process.argv[2] ?? 'http://localhost:8081').replace(/\/$/, '');
const url = `${base}/v3/api-docs`;

let response;
try {
  response = await fetch(url);
} catch (cause) {
  console.error(`Could not reach ${url}`);
  console.error('Start the backend first:  docker compose up -d && cd backend && mvn spring-boot:run');
  console.error(`(${cause.message})`);
  process.exit(1);
}

if (!response.ok) {
  console.error(`${url} answered ${response.status} ${response.statusText}`);
  process.exit(1);
}

const document = await response.json();

// Pretty-printed and key-stable, so a refresh produces a reviewable diff rather than one
// enormous changed line. Springdoc already emits keys in a deterministic order.
const serialised = `${JSON.stringify(document, null, 2)}\n`;

const previous = existsSync(target) ? readFileSync(target, 'utf8') : null;
if (previous === serialised) {
  console.log('openapi.json is already up to date.');
  process.exit(0);
}

writeFileSync(target, serialised);

const operations = Object.values(document.paths ?? {}).reduce(
  (total, path) => total + Object.keys(path).length,
  0,
);
const schemas = Object.keys(document.components?.schemas ?? {});
const enums = schemas.filter((name) => document.components.schemas[name].enum);

console.log(
  `openapi.json updated from ${url}\n` +
    `  OpenAPI ${document.openapi}\n` +
    `  ${Object.keys(document.paths ?? {}).length} paths, ${operations} operations\n` +
    `  ${schemas.length} schemas, of which ${enums.length} enums\n` +
    (previous === null ? '' : '\nReview the diff, then run: npm run api:generate'),
);
