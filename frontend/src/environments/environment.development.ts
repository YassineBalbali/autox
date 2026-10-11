/**
 * Local development. Swapped in for {@link environment} by `ng serve` and by
 * `ng build --configuration development`.
 */
export const environment = {
  production: false,

  /**
   * The backend as docker-compose and `mvn spring-boot:run` expose it.
   *
   * Port 8081 rather than 8080: a locally installed Oracle XML DB answers on 8080 on at
   * least one developer machine, and `AUTOX_PORT` in `.env` moves the API. Keep this value
   * and that variable in step.
   *
   * Because this is a different origin from `ng serve` on 4200, the browser sends a
   * preflight — `AUTOX_CORS_ALLOWED_ORIGINS` already lists `http://localhost:4200`.
   */
  apiBaseUrl: 'http://localhost:8081',
};
