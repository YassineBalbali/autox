/**
 * The only import path the application uses for API types and services.
 *
 * <p>Everything under `generated/` is produced by `npm run api:generate` from
 * `openapi.json` and is not committed. Re-exporting through this one file means the rest of
 * the app never reaches into that directory, so the generator's output layout — which
 * changes between generator versions — is not spread across a hundred import statements.
 *
 * <p>Nothing is added here by hand. If the application needs a type the API does not
 * describe, that is a gap in the OpenAPI document to fix on the backend, not an interface
 * to write here.
 */
export * from './generated';
