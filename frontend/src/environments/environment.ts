/**
 * Production configuration. Replaced at build time for other configurations through
 * `fileReplacements` in angular.json.
 *
 * <p>No secret belongs in this file, or in any file the browser downloads. Everything here
 * is public the moment the bundle ships.
 */
/**
 * Replaced with a string literal by `ng build --define`, which `scripts/build.mjs` passes
 * from the AUTOX_API_BASE_URL environment variable. Declared, never assigned: without the
 * flag the identifier does not exist at runtime, which is what the `typeof` below is for.
 */
declare const AUTOX_API_BASE_URL: string | undefined;

export const environment = {
  production: true,

  /**
   * Base URL of the AutoX API, without a trailing slash.
   *
   * Empty unless the build was given AUTOX_API_BASE_URL. Empty is right when the app is
   * served from the same origin as the API behind one proxy: a relative `/api/v1/...` is
   * correct and there is no CORS to configure. When the two are deployed apart — a static
   * host for this, a container host for the API — the build is told where the API is.
   *
   * A static site has no server to ask at runtime, so this is fixed at build time. It is
   * also named in the Content-Security-Policy the same script writes, which is why the
   * two are set by one variable and cannot disagree.
   */
  apiBaseUrl: typeof AUTOX_API_BASE_URL === 'string' ? AUTOX_API_BASE_URL : '',
};
