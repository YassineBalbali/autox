import 'dart:convert';
import 'dart:io';

import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:path_provider/path_provider.dart';
import 'package:share_plus/share_plus.dart';

import '../../core/api/api_failure.dart';
import '../../core/api/api_providers.dart';

/// Hands a file to the Android share sheet (save to Files, Drive, send by mail…) and returns once
/// the sheet has taken it. Overridden in tests.
typedef FileSharer = Future<void> Function(File file, {required String mimeType});

final fileSharerProvider = Provider<FileSharer>(
  (ref) =>
      (file, {required mimeType}) => SharePlus.instance.share(
        ShareParams(
          files: [XFile(file.path, mimeType: mimeType)],
          title: 'Mes données AutoX',
        ),
      ),
);

/// Where the export is written before it is shared: the app's cache, which no other app reads.
final exportDirectoryProvider = Provider<Future<Directory> Function()>((ref) => getTemporaryDirectory);

/// The export, as the web asks for it (`ProfileService.exportData`).
const _exportPath = '/api/v1/me/export';

/// The clock, for the file's name. Overridden in tests.
final exportClockProvider = Provider<DateTime Function()>((ref) => DateTime.now);

/// The website's name for the same download (`exportFileName` in my-data-page.ts): today's date,
/// so two downloads do not overwrite each other.
String exportFileName(DateTime now) {
  String two(int value) => value.toString().padLeft(2, '0');
  return 'autox-mes-donnees-${now.year}-${two(now.month)}-${two(now.day)}.json';
}

/// "Mes données", inside the app: `GET /me/export` with the app's session (through the normal
/// client, so an expired access token is renewed on the way), written as the website writes it
/// (JSON, two-space indent) to a file in the cache, shared, then deleted.
///
/// The file exists only between the answer and the end of the share. share_plus copies it into its
/// own cache folder before handing it to the receiving app, and clears that folder at the next
/// share. On a failure — no answer, a refusal, a full disk — nothing is left behind.
final dataExportProvider = Provider<Future<void> Function()>((ref) {
  return () async {
    final Object? document;
    try {
      // On the app's Dio, with its auth interceptor, but not through the generated
      // `ProfileApi.exportData`: its serializer cannot read a free-form object ("Cannot
      // deserialize"), and the document is written back out as it came anyway.
      document = (await ref.read(dioProvider).get<Object?>(_exportPath)).data;
    } on Object catch (error) {
      throw ApiFailure.from(error);
    }
    if (document is! Map) {
      throw const UnexpectedFailure();
    }

    final directory = await ref.read(exportDirectoryProvider)();
    final file = File('${directory.path}${Platform.pathSeparator}${exportFileName(ref.read(exportClockProvider)())}');
    try {
      await file.writeAsString(const JsonEncoder.withIndent('  ').convert(document), flush: true);
      await ref.read(fileSharerProvider)(file, mimeType: 'application/json');
    } on ApiFailure {
      rethrow;
    } on Object {
      throw const UnexpectedFailure();
    } finally {
      try {
        if (file.existsSync()) {
          file.deleteSync();
        }
      } on FileSystemException {
        // Left in the cache, which Android clears when it needs the space.
      }
    }
  };
});
