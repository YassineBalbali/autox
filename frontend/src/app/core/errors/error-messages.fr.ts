import { ErrorCode } from '../api';
import LABELS from '../../../../labels.fr.json';

/**
 * French text for every error the API can return.
 *
 * <p><b>Typed as `Record<ErrorCode, string>` on purpose.</b> `ErrorCode` is generated from
 * the OpenAPI document, so when the backend adds an error code this file stops compiling
 * until someone writes the French for it. That compile error is the whole point: the
 * alternative is a lookup that silently misses and shows a user the fallback — or worse,
 * the API's English `detail`.
 *
 * <p>These are written for the person reading them, not as translations of the API's
 * `detail`. The API's text is developer-facing ("Refresh token is invalid, expired or has
 * already been used."); the user needs to know what to do ("Votre session a expiré.
 * Reconnectez-vous."). They are deliberately different sentences.
 *
 * <p>Not in an Angular i18n XLIFF file, and that is a considered split rather than an
 * oversight. Angular's i18n is a build-time mechanism for text that appears in templates;
 * this is a runtime lookup keyed on a value that arrives over the network. Putting it
 * through `$localize` would work but would scatter the table across extraction files and
 * lose the exhaustive `Record` type, which is the part earning its keep. When a second
 * locale arrives, this becomes one file per locale selected by `LOCALE_ID` — the shape does
 * not change.
 */
//
// The sentences are in frontend/labels.fr.json (`enums.ErrorCode`), shared with the Android
// app. A JSON file holds no comments, so the reasons behind a few of them are here:
//
// - INVALID_CREDENTIALS says nothing about which of the two was wrong. The API does not tell
//   us, by design, and guessing in the interface would undo that.
// - INVALID_TOKEN and INVALID_REFRESH_TOKEN should never be seen: the interceptor renews the
//   session on a 401, and only shows an error if the renewal itself fails. If one does appear,
//   the session is genuinely over.
// - TOO_MANY_ATTEMPTS and RATE_LIMITED both carry retryAfterSeconds; ErrorMessageService
//   appends the wait when it is known, which is why neither sentence names a duration.
// - INVALID_CURSOR is only reachable by editing a URL by hand, or by holding a cursor across
//   a sort change.
// - INTERPRETER_UNAVAILABLE: the page offers the ordinary search beside this message, so it
//   need not say how.
export const ERROR_MESSAGES_FR: Record<ErrorCode, string> = LABELS.enums.ErrorCode;

/**
 * French labels for the request fields the API names in a validation failure.
 *
 * <p>Keyed on the `field` member of `errors[]`, which is the request property path —
 * `email`, `priceAmount`, `professional.vatNumber`. Not exhaustive and cannot be: `field`
 * is a free-form string, so a field added to a request DTO appears here as a cache miss
 * rather than a compile error. {@link fieldLabelFr} falls back to the raw name, which is
 * ugly in the interface and therefore noticed.
 */
export const FIELD_LABELS_FR: Readonly<Record<string, string>> = LABELS.fields;

/**
 * What to say about a field whose value the API rejected.
 *
 * <p><b>This is the weak point of the error layer, and it is a contract limitation rather
 * than a shortcut.</b> The API reports `{field, message}` where `message` is untranslated
 * developer text — "must be a well-formed email address" — and its wording is explicitly
 * not a contract, so it can be neither shown nor matched on. What arrives is therefore
 * "this field is wrong" and nothing about *why*.
 *
 * <p>Which means the real answer is for the client to validate before submitting, with its
 * own French messages, and to treat a server-side validation failure as a backstop: mark
 * the fields, say they are invalid, and let the form's own rules explain. That is what this
 * returns.
 *
 * <p>The better fix belongs in the API: a machine-readable constraint per violation —
 * `{field: "email", constraint: "EMAIL"}` — would let the client say exactly what is wrong
 * in the user's language. Worth raising when forms are built.
 */
export function fieldLabelFr(field: string): string {
  return FIELD_LABELS_FR[field] ?? field;
}
