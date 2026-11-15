import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:material_ui/material_ui.dart';

import 'core/auth/session.dart';
import 'core/routing/app_router.dart';
import 'core/theme/app_theme.dart';

/// The application: French only, the site's theme, go_router for navigation, the account's
/// places following the session.
class AutoxApp extends ConsumerStatefulWidget {
  const AutoxApp({super.key, this.initialLocation = AppRoutes.search});

  final String initialLocation;

  @override
  ConsumerState<AutoxApp> createState() => _AutoxAppState();
}

class _AutoxAppState extends ConsumerState<AutoxApp> {
  /// Tells the router to look again at its redirect whenever the session changes.
  final _sessionChanged = ValueNotifier<int>(0);

  // Built once: a router rebuilt on every frame would lose the navigation state.
  late final GoRouter _router = buildRouter(
    initialLocation: widget.initialLocation,
    session: () => ref.read(sessionProvider),
    refresh: _sessionChanged,
  );

  @override
  void initState() {
    super.initState();
    ref.listenManual(sessionProvider, (_, _) => _sessionChanged.value++);
  }

  @override
  void dispose() {
    _router.dispose();
    _sessionChanged.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) => MaterialApp.router(
    title: 'AutoX',
    debugShowCheckedModeBanner: false,
    theme: AppTheme.light(),
    // Light only, as the site: a dark theme is a design of its own, not a token swap.
    themeMode: ThemeMode.light,
    locale: const Locale('fr', 'FR'),
    supportedLocales: const [Locale('fr', 'FR')],
    // French for the framework's own strings (date pickers, "Retour", text selection),
    // from material_ui: its list includes the widgets and Cupertino delegates Material needs.
    localizationsDelegates: GlobalMaterialLocalizations.delegates,
    routerConfig: _router,
  );
}
