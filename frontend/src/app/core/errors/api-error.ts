import { HttpErrorResponse } from '@angular/common/http';

import { ApiError, ErrorCode } from '../api';

/**
 * Narrows whatever `HttpClient` threw into the API's error contract.
 *
 * <p>`ApiError` is the generated model, so the shape is not hand-written — but the *check*
 * has to be, because an `HttpErrorResponse` body can be anything: a proxy's HTML error
 * page, a CORS failure with no body at all, a timeout. The one thing the API guarantees is
 * that when it answers, the body carries a `code` from {@link ErrorCode}.
 */
export function asApiError(error: unknown): ApiError | null {
  if (!(error instanceof HttpErrorResponse)) {
    return null;
  }
  const body: unknown = error.error;
  if (typeof body !== 'object' || body === null) {
    return null;
  }
  const code = (body as { code?: unknown }).code;
  if (typeof code !== 'string' || !isErrorCode(code)) {
    return null;
  }
  return body as ApiError;
}

/**
 * Whether a string is one of the codes this build knows about.
 *
 * <p>Guards against the deployment skew that type generation cannot: a backend released
 * ahead of the frontend can send a code this bundle has never heard of. Rejecting it here
 * routes the error to the generic message rather than looking up `undefined` and rendering
 * it.
 */
export function isErrorCode(value: string): value is ErrorCode {
  return Object.values(ErrorCode).includes(value as ErrorCode);
}

/**
 * True when the request never reached the API — offline, DNS failure, CORS refusal, or a
 * connection the browser dropped.
 *
 * <p>Worth distinguishing, because the advice differs: a server error means "try again",
 * a transport error means "check your connection". The browser reports status 0 for all of
 * them and tells us no more than that.
 */
export function isTransportFailure(error: unknown): boolean {
  return error instanceof HttpErrorResponse && error.status === 0;
}
