import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:intl/date_symbol_data_local.dart';
import 'package:intl/intl.dart';
import 'package:material_ui/material_ui.dart';

import 'app.dart';
import 'core/api/api_providers.dart';
import 'core/config/app_config.dart';

Future<void> main() async {
  WidgetsFlutterBinding.ensureInitialized();
  Intl.defaultLocale = 'fr_FR';
  await initializeDateFormatting('fr_FR');

  // Fails a release build that was not given its API address, at once and in plain words,
  // rather than with a network error on the first screen.
  final config = AppConfig.fromEnvironment();

  runApp(
    ProviderScope(
      overrides: [appConfigProvider.overrideWithValue(config)],
      // No automatic retries. Riverpod 3 retries a failed provider by default, which would turn
      // a 404 into ten requests and hide "le serveur démarre" behind a spinner; each screen
      // offers "Réessayer" instead.
      retry: (_, _) => null,
      child: const AutoxApp(),
    ),
  );
}
