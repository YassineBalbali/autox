import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:share_plus/share_plus.dart';
import 'package:url_launcher/url_launcher.dart';

/// Opens a link outside the app: the dialler, the browser. Replaced in tests, which record what
/// would have been opened.
final linkOpenerProvider = Provider<Future<bool> Function(Uri uri)>(
  (ref) =>
      (uri) => launchUrl(uri, mode: LaunchMode.externalApplication),
);

/// Hands a text to the Android share sheet: a listing's address, to send to someone. Replaced in
/// tests, which record what would have been shared.
typedef TextSharer = Future<void> Function(String text, {String? subject});

final textSharerProvider = Provider<TextSharer>(
  (ref) =>
      (text, {subject}) => SharePlus.instance.share(ShareParams(text: text, subject: subject)),
);
