import 'package:autox_api/autox_api.dart' show ErrorCode, LastMessage, ListingReportReason, MessageReportReason;
import 'package:intl/intl.dart';

import '../../core/api/api_failure.dart';
import '../../core/forms/validation.dart' show fill, waitSentence;
import '../../core/labels/labels.g.dart';

/// The messages and report screens' rules and words, as the web has them
/// (`features/messages/messaging.ts`, `message-thread.ts`): the words from `labels.fr.json`, and
/// both held to `frontend/fixtures/messaging.json`.

/// The longest message the API accepts.
const messageMaxLength = 2000;

/// The longest note the API accepts with a report, on a message and on a listing.
const messageReportNoteMaxLength = 500;
const listingReportNoteMaxLength = 1000;

String _word(String key) => messagingTexts[key]!;

/// Whether a message can be sent: something besides spaces, and no longer than the API takes.
bool canSendMessage(String body) => body.trim().isNotEmpty && body.length <= messageMaxLength;

/// Whether a report can be sent: a reason, and a note when the reason is "other".
bool canSubmitReport(String? reason, String note) => reason != null && (reason != 'OTHER' || note.trim().isNotEmpty);

/// The note field's hint, which says when the note is required.
String reportNotePlaceholder(String? reason) =>
    reason == 'OTHER' ? _word('reportNoteRequired') : _word('reportNoteOptional');

/// The reasons offered when reporting a message, and a listing, in the web's order.
List<(MessageReportReason, String)> get messageReportReasons => [
  for (final MapEntry(:key, :value) in messageReportReasonLabels.entries)
    (MessageReportReason.values.firstWhere((reason) => reason.name == key), value),
];

List<(ListingReportReason, String)> get listingReportReasons => [
  for (final MapEntry(:key, :value) in listingReportReasonLabels.entries)
    (ListingReportReason.values.firstWhere((reason) => reason.name == key), value),
];

/// The first line of a message to a listing's seller, or (no title) to a dealer from their page.
String contactGreeting(String? listingTitle) =>
    listingTitle == null ? _word('greetingDealer') : fill(_word('greetingListing'), {'title': listingTitle});

/// The inbox's last line: "Vous : …", "Message masqué par la modération", or the other side's words.
String inboxPreview(LastMessage? last) {
  if (last == null) {
    return '';
  }
  final text = (last.hidden ?? false)
      ? _word('previewHidden')
      : (last.erased ?? false)
      ? _word('previewErased')
      : (last.preview ?? '');
  return (last.sentByMe ?? false) ? fill(_word('previewMine'), {'text': text}) : text;
}

/// "1 message non lu", "3 messages non lus".
String unreadLabel(int count) => count == 1 ? _word('unreadOne') : fill(_word('unreadMany'), {'count': '$count'});

/// Why a message could not be sent, in words that fit sending one: a writer's view of a rate
/// limit, a closed conversation, a listing gone; otherwise the general sentence.
String sendFailure(ApiFailure failure) {
  if (failure is ServerFailure) {
    switch (failure.code) {
      case ErrorCode.RATE_LIMITED:
        final wait = failure.retryAfterSeconds;
        return wait == null ? _word('sendRateLimited') : '${_word('sendRateLimited')} ${waitSentence(wait)}';
      case ErrorCode.CONFLICT:
        return _word('sendClosed');
      case ErrorCode.NOT_FOUND:
        return _word('sendListingGone');
      default:
        break;
    }
  }
  return failure.message;
}

int _dayNumber(DateTime local) => DateTime.utc(local.year, local.month, local.day).millisecondsSinceEpoch ~/ 86400000;

/// When a message was sent, as the inbox writes it: "14:32" today, "hier", "12 oct." this year,
/// "12 oct. 2025" before. In the phone's own zone.
String sentAtShort(DateTime? sentAt, {DateTime? now}) {
  if (sentAt == null) {
    return '';
  }
  final date = sentAt.toLocal();
  final today = (now ?? DateTime.now()).toLocal();
  final days = _dayNumber(today) - _dayNumber(date);
  if (days == 0) {
    return DateFormat('HH:mm', 'fr_FR').format(date);
  }
  if (days == 1) {
    return _word('yesterdayShort');
  }
  return date.year == today.year
      ? DateFormat('d MMM', 'fr_FR').format(date)
      : DateFormat('d MMM y', 'fr_FR').format(date);
}

/// A day's heading in a conversation: "Aujourd’hui", "Hier", or "mardi 21 octobre 2026".
String dayLabel(DateTime day, {DateTime? now}) {
  final date = day.toLocal();
  final days = _dayNumber((now ?? DateTime.now()).toLocal()) - _dayNumber(date);
  if (days == 0) {
    return _word('today');
  }
  if (days == 1) {
    return _word('yesterday');
  }
  return DateFormat('EEEE d MMMM y', 'fr_FR').format(date);
}

/// A message's time, "14:32".
String timeOf(DateTime? sentAt) => sentAt == null ? '' : DateFormat('HH:mm', 'fr_FR').format(sentAt.toLocal());

/// "mardi 21 octobre 2026 à 14:32", for TalkBack: the web's tooltip on a message's time.
String sentAtFull(DateTime? sentAt) {
  if (sentAt == null) {
    return '';
  }
  final date = sentAt.toLocal();
  return '${DateFormat('EEEE d MMMM y', 'fr_FR').format(date)} à ${DateFormat('HH:mm', 'fr_FR').format(date)}';
}
