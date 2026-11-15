import 'package:autox/app.dart';
import 'package:autox/core/auth/token_store.dart';
import 'package:autox/core/links.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_riverpod/misc.dart' show Override;
import 'package:flutter_test/flutter_test.dart';
import 'package:material_ui/material_ui.dart' show Size;

import 'fake_http.dart';

/// The whole app on [http], opened at [location], with [stored] as the phone's preferences,
/// [tokens] where the refresh token is kept (signed out by default), and every link the app
/// would open outside recorded in [opened]; [overrides] after those, for what a test replaces.
Future<void> pumpApp(
  WidgetTester tester,
  FakeHttp http, {
  String location = '/recherche',
  Map<String, Object> stored = const {},
  TokenStore? tokens,
  List<Uri>? opened,
  List<Override> overrides = const [],
}) async {
  final preferences = await testPreferences(stored);
  await tester.pumpWidget(
    ProviderScope(
      overrides: [
        ...fakeApiOverrides(http, preferences: preferences, tokens: tokens),
        linkOpenerProvider.overrideWithValue((uri) async {
          opened?.add(uri);
          return true;
        }),
        ...overrides,
      ],
      retry: (_, _) => null,
      child: AutoxApp(initialLocation: location),
    ),
  );
}

/// Lets the debounced requests (suggestions, the filter sheet's count) go out and settle.
///
/// A plain pump first: `pump(duration)` moves the clock before it builds, so a debounce timer
/// started by that build would begin after the wait instead of during it.
Future<void> settleDebounced(WidgetTester tester) async {
  await tester.pump();
  await tester.pump(const Duration(milliseconds: 400));
  await tester.pumpAndSettle();
}

/// A phone tall enough for a whole lazy list (the filter sheet) to be built at once.
void useTallScreen(WidgetTester tester) {
  tester.view.physicalSize = const Size(1080, 6000);
  tester.view.devicePixelRatio = 2.5;
  addTearDown(tester.view.reset);
}
