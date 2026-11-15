import 'dart:convert';
import 'dart:io';

import 'package:autox/core/api/api_failure.dart';
import 'package:autox/core/auth/session.dart';
import 'package:autox/core/auth/token_store.dart';
import 'package:autox/features/account/data_export.dart';
import 'package:dio/dio.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';

import '../../support/fake_auth.dart';
import '../../support/fake_http.dart';

/// "Mes données" in the app, against the fake API and a real temporary directory: what is written,
/// what is shared, and what is left behind.
void main() {
  late FakeAccountApi api;
  late Directory directory;
  late List<({String path, String mimeType, String contents})> shared;
  late bool sheetFails;

  setUp(() {
    api = FakeAccountApi();
    directory = Directory.systemTemp.createTempSync('autox-export-test');
    addTearDown(() => directory.deleteSync(recursive: true));
    shared = [];
    sheetFails = false;
  });

  Future<ProviderContainer> signedIn() async {
    final container = ProviderContainer(
      overrides: [
        ...fakeApiOverrides(api.http(), preferences: await testPreferences(), tokens: MemoryTokenStore('refresh-0')),
        exportDirectoryProvider.overrideWithValue(() async => directory),
        exportClockProvider.overrideWithValue(() => DateTime(2026, 3, 7, 23, 30)),
        // The share sheet, as the plugin would be called: the file is read while it is being shared.
        fileSharerProvider.overrideWithValue((file, {required mimeType}) async {
          if (sheetFails) {
            throw StateError('no activity to share with');
          }
          shared.add((path: file.path, mimeType: mimeType, contents: await file.readAsString()));
        }),
      ],
      retry: (_, _) => null,
    );
    addTearDown(container.dispose);
    container.listen(sessionProvider, (_, _) {});
    for (var i = 0; i < 100 && container.read(sessionProvider) is! SignedIn; i++) {
      await Future<void>.delayed(Duration.zero);
    }
    expect(container.read(sessionProvider), isA<SignedIn>());
    return container;
  }

  test('the export is written under the website’s file name, shared as JSON, then deleted', () async {
    final container = await signedIn();

    await container.read(dataExportProvider)();

    final share = shared.single;
    expect(share.path, '${directory.path}${Platform.pathSeparator}autox-mes-donnees-2026-03-07.json');
    expect(share.mimeType, 'application/json');
    expect(jsonDecode(share.contents), exportJson);
    // Written as the website writes it: two-space indent.
    expect(share.contents, const JsonEncoder.withIndent('  ').convert(exportJson));
    expect(directory.listSync(), isEmpty);
  });

  test('an expired access token is renewed through the one refresh, and the export goes on', () async {
    final container = await signedIn();
    final refreshes = api.bodies['/api/v1/auth/refresh']!.length;
    api.validAccess = 'expired';

    await container.read(dataExportProvider)();

    expect(api.bodies['/api/v1/auth/refresh'], hasLength(refreshes + 1));
    // Refused once with the old token, then answered with the new one.
    expect(api.bodies['/api/v1/me/export'], hasLength(2));
    expect(shared, hasLength(1));
    expect(directory.listSync(), isEmpty);
  });

  test('no connection: an offline failure, nothing shared, no file left behind', () async {
    final container = await signedIn();
    api.answers['/api/v1/me/export'] = const FakeResponse.error(DioExceptionType.connectionError);

    final failure = await container.read(dataExportProvider)().then<Object?>((_) => null, onError: (Object e) => e);

    expect(failure, isA<OfflineFailure>());
    expect((failure! as ApiFailure).message, 'Impossible de joindre AutoX. Vérifiez votre connexion internet.');
    expect(shared, isEmpty);
    expect(directory.listSync(), isEmpty);
  });

  test('the share sheet failing still leaves no file behind', () async {
    final container = await signedIn();
    sheetFails = true;

    await expectLater(container.read(dataExportProvider)(), throwsA(isA<UnexpectedFailure>()));
    expect(directory.listSync(), isEmpty);
  });
}
