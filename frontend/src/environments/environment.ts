/**
 * Production configuration. Replaced at build time for other configurations through
 * `fileReplacements` in angular.json.
 *
 * <p>No secret belongs in this file, or in any file the browser downloads. Everything here
 * is public the moment the bundle ships.
 */
export const environment = {
  production: true,

  /**
   * Base URL of the AutoX API, without a trailing slash.
   *
   * Empty on purpose: in production the app is served from the same origin as the API
   * behind one proxy, so a relative `/api/v1/...` is correct and there is no CORS to
   * configure. Set it to an absolute origin only when the two are deployed apart.
   */
  apiBaseUrl: '',
};
