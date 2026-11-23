import '../labels/labels.g.dart';

/// The account forms' rules, the web's (`frontend/src/app/shared/forms/validation.ts`), with its
/// messages from `frontend/labels.fr.json`. Checked against the web's own results by
/// `frontend/fixtures/forms.json`.
///
/// Each validator returns the first message that applies, or null: one sentence at a time, the
/// most useful first — required beats too short beats malformed, as on the web.

const passwordMinLength = 12;
const passwordMaxLength = 128;
const emailMaxLength = 255;
const displayNameMaxLength = 120;
const nameMaxLength = 80;

/// Angular's `Validators.email`, character for character: permissive by design, ASCII only.
final _email = RegExp(
  r"^(?=.{1,254}$)(?=.{1,64}@)[a-zA-Z0-9!#$%&'*+/=?^_`{|}~-]+(?:\.[a-zA-Z0-9!#$%&'*+/=?^_`{|}~-]+)*"
  r'@[a-zA-Z0-9](?:[a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?(?:\.[a-zA-Z0-9](?:[a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?)*$',
);

/// International format, as the API requires: "+33612345678".
final _phone = RegExp(r'^\+[1-9][0-9]{6,18}$');

/// "{length} caractères au maximum." with its placeholders replaced.
String fill(String template, Map<String, String> values) =>
    template.replaceAllMapped(RegExp(r'\{(\w+)\}'), (match) => values[match[1]] ?? match[0]!);

String _message(String key, [Map<String, String> values = const {}]) =>
    fill(validationMessages[key] ?? validationMessages['invalid']!, values);

String? _required(String value) => value.trim().isEmpty ? _message('required') : null;

/// Angular's length rules skip an empty value: "required" speaks for it.
String? _minLength(String value, int min) =>
    value.isNotEmpty && value.length < min ? _message('minlength', {'length': '$min'}) : null;

String? _maxLength(String value, int max) => value.length > max ? _message('maxlength', {'length': '$max'}) : null;

String? validateEmail(String value) =>
    _required(value) ??
    (value.isNotEmpty && !_email.hasMatch(value) ? _message('email') : null) ??
    _maxLength(value, emailMaxLength);

String? validatePassword(String value) =>
    _required(value) ?? _minLength(value, passwordMinLength) ?? _maxLength(value, passwordMaxLength);

String? validatePasswordConfirmation(String confirmation, String password) =>
    _required(confirmation) ??
    (confirmation.isNotEmpty && confirmation != password ? _message('passwordMismatch') : null);

String? validateDisplayName(String value) => _required(value) ?? _maxLength(value, displayNameMaxLength);

/// First name and last name: optional, 80 characters at most.
String? validateName(String value) => _maxLength(value, nameMaxLength);

String? validatePhone(String value) => value.isNotEmpty && !_phone.hasMatch(value) ? _message('pattern') : null;

/// The wait after a throttle, rounded up: "Réessayez dans 2 minutes."
String waitSentence(num seconds) {
  if (seconds <= 1) {
    return clientMessages['waitInstant']!;
  }
  if (seconds < 60) {
    return fill(clientMessages['waitSeconds']!, {'seconds': '${seconds.ceil()}'});
  }
  if (seconds < 3600) {
    final minutes = (seconds / 60).ceil();
    return minutes == 1 ? clientMessages['waitMinute']! : fill(clientMessages['waitMinutes']!, {'minutes': '$minutes'});
  }
  // The assistant's daily quota frees in hours.
  final hours = (seconds / 3600).ceil();
  return hours == 1 ? clientMessages['waitHour']! : fill(clientMessages['waitHours']!, {'hours': '$hours'});
}

/// The message for a validator key and its details, as the web's `firstErrorFr` words one.
String messageForKey(String key, {int? length, String? min, String? max}) => switch (key) {
  'minlength' || 'maxlength' => _message(key, {'length': '${length ?? 0}'}),
  'range' => _message(key, {'min': min ?? '0', 'max': max ?? '0'}),
  _ => _message(key),
};
