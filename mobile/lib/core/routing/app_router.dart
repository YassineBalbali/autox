import 'package:autox_api/autox_api.dart' show ErrorCode;
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:lucide_icons_flutter/lucide_icons.dart';
import 'package:material_ui/material_ui.dart';

import '../../features/account/account_locations.dart';
import '../../features/account/account_screen.dart';
import '../../features/account/forgot_password_screen.dart';
import '../../features/account/login_screen.dart';
import '../../features/account/register_screen.dart';
import '../../features/dealers/dealer_directory_screen.dart';
import '../../features/dealers/dealer_screen.dart';
import '../../features/listing/listing_screen.dart';
import '../../features/search/new_cars_screen.dart';
import '../../features/search/results_screen.dart';
import '../../features/search/search_entry_screen.dart';
import '../../features/search/search_home_screen.dart';
import '../../features/search/search_query.dart';
import '../../features/search/smart_search.dart';
import '../auth/session.dart';
import '../labels/app_labels.dart';
import '../labels/labels.g.dart';
import '../widgets/pending.dart';
import '../widgets/states.dart';

/// The app's paths: French, like the site's (`/recherche`), so a link shared from one can be
/// mapped onto the other when App Links arrive.
abstract final class AppRoutes {
  static const search = '/recherche';
  static const favourites = '/favoris';
  static const messages = '/messages';
  static const account = '/compte';
}

/// One tab of the bottom bar.
class _Tab {
  const _Tab(this.path, this.label, this.icon);

  final String path;
  final String label;
  final IconData icon;
}

const _tabs = [
  _Tab(AppRoutes.search, 'Rechercher', LucideIcons.search),
  _Tab(AppRoutes.favourites, 'Favoris', LucideIcons.heart),
  _Tab(AppRoutes.messages, 'Messages', LucideIcons.messageSquare),
  _Tab(AppRoutes.account, 'Compte', LucideIcons.userRound),
];

/// A new router per call: tests each get their own, with its own navigation state.
///
/// [session] is read on each navigation and [refresh] fires when it changes, so the account's
/// places follow the session ([accountRedirect]).
GoRouter buildRouter({String initialLocation = AppRoutes.search, AuthState Function()? session, Listenable? refresh}) =>
    GoRouter(
      initialLocation: initialLocation,
      refreshListenable: refresh,
      redirect: (_, state) => session == null ? null : accountRedirect(session(), state.uri),
      routes: [
        // Outside the tabs, so they open over the bar, from any tab: a listing reached from the
        // search now and from the favourites later is the same page. The site's own paths.
        GoRoute(
          path: '/annonces/:id',
          builder: (_, state) => ListingScreen(id: state.pathParameters['id']!),
        ),
        GoRoute(
          path: '/professionnels',
          builder: (_, _) => const DealerDirectoryScreen(),
          routes: [
            GoRoute(
              path: ':slug',
              builder: (_, state) => DealerScreen(slug: state.pathParameters['slug']!),
            ),
          ],
        ),
        // One navigator per tab, kept alive: switching tabs returns to where that tab was,
        // scroll position included, as Android users expect from a bottom bar.
        StatefulShellRoute.indexedStack(
          builder: (context, state, shell) => AppShell(shell: shell),
          branches: [
            StatefulShellBranch(
              routes: [
                GoRoute(
                  path: AppRoutes.search,
                  builder: (_, _) => const SearchHomeScreen(),
                  routes: [
                    // The search is in the location, in the site's own parameters
                    // (/recherche/resultats?marque=peugeot&carburant=diesel): parsed like a link.
                    GoRoute(
                      path: 'resultats',
                      builder: (_, state) {
                        final query = parseQuery(state.uri.queryParameters);
                        return ResultsScreen(
                          // A new search is a new screen: its own results, scroll and note.
                          key: ValueKey(state.uri.toString()),
                          query: query,
                          interpretation: state.extra is Interpretation ? state.extra! as Interpretation : null,
                        );
                      },
                    ),
                    GoRoute(
                      path: 'saisie',
                      builder: (_, state) => SearchEntryScreen(
                        base: state.extra is SearchQuery ? state.extra! as SearchQuery : SearchQuery.empty,
                      ),
                    ),
                    GoRoute(path: 'neuf', builder: (_, _) => const NewCarsScreen()),
                  ],
                ),
              ],
            ),
            StatefulShellBranch(
              routes: [
                GoRoute(
                  path: AppRoutes.favourites,
                  builder: (_, _) => const _ComingSoonScreen(
                    title: 'Favoris',
                    icon: LucideIcons.heart,
                    body: 'Les annonces marquées d’un cœur seront gardées ici, sur ce téléphone.',
                  ),
                ),
              ],
            ),
            StatefulShellBranch(
              routes: [GoRoute(path: AppRoutes.messages, builder: (_, _) => const _MessagesScreen())],
            ),
            StatefulShellBranch(
              routes: [
                GoRoute(
                  path: AppRoutes.account,
                  builder: (_, state) => AccountScreen(welcome: state.uri.queryParameters['bienvenue'] == '1'),
                  routes: [GoRoute(path: 'attente', builder: (_, _) => const _AccountWait())],
                ),
                // Signed-out places, the site's paths, in the account's tab.
                GoRoute(path: AccountLocations.signIn, builder: (_, _) => const LoginScreen()),
                GoRoute(path: AccountLocations.register, builder: (_, _) => const RegisterScreen()),
                GoRoute(path: AccountLocations.forgotPassword, builder: (_, _) => const ForgotPasswordScreen()),
              ],
            ),
          ],
        ),
      ],
    );

/// The bottom bar around the current tab, and the one place that says "session expirée" — once,
/// when the server ends a session, whatever screen the person is on.
class AppShell extends ConsumerWidget {
  const AppShell({super.key, required this.shell});

  final StatefulNavigationShell shell;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    ref.listen(sessionProvider, (_, next) {
      if (next case SignedOut(expired: true)) {
        ScaffoldMessenger.of(context)
            .showSnackBar(SnackBar(content: Text(errorCodeLabel(ErrorCode.INVALID_REFRESH_TOKEN))));
        ref.read(sessionProvider.notifier).acknowledgeExpiry();
      }
    });
    return _bar(context);
  }

  Widget _bar(BuildContext context) => Scaffold(
    body: shell,
    bottomNavigationBar: DecoratedBox(
      decoration: BoxDecoration(
        border: Border(top: BorderSide(color: Theme.of(context).dividerColor)),
      ),
      child: NavigationBar(
        selectedIndex: shell.currentIndex,
        // Tapping the current tab again goes back to its first screen, the platform habit.
        onDestinationSelected: (index) => shell.goBranch(index, initialLocation: index == shell.currentIndex),
        destinations: [for (final tab in _tabs) NavigationDestination(icon: Icon(tab.icon), label: tab.label)],
      ),
    ),
  );
}

/// Messages arrive in the next milestone; signed out, the tab asks to sign in first.
class _MessagesScreen extends ConsumerWidget {
  const _MessagesScreen();

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final signedIn = ref.watch(sessionProvider) is SignedIn;
    return Scaffold(
      appBar: AppBar(title: const Text('Messages'), centerTitle: false),
      body: signedIn
          ? const ComingSoonView(
              icon: LucideIcons.messageSquare,
              title: 'Bientôt disponible',
              body: 'Vos conversations avec les vendeurs apparaîtront ici.',
            )
          : Center(
              child: Padding(
                padding: const EdgeInsets.all(24),
                child: Column(
                  mainAxisSize: MainAxisSize.min,
                  children: [
                    const ComingSoonView(
                      icon: LucideIcons.messageSquare,
                      title: AppLabels.messagesSignedOut,
                      body: 'Vos conversations avec les vendeurs sont liées à votre compte.',
                    ),
                    FilledButton(
                      onPressed: () => context.go(AccountLocations.signIn),
                      child: const Text('Se connecter'),
                    ),
                  ],
                ),
              ),
            ),
    );
  }
}

/// The account, while the stored session is being checked: a neutral wait.
class _AccountWait extends StatelessWidget {
  const _AccountWait();

  @override
  Widget build(BuildContext context) => Scaffold(
    appBar: AppBar(title: const Text('Mon compte'), centerTitle: false),
    body: const PendingView(label: 'Vérification de la session'),
  );
}

class _ComingSoonScreen extends StatelessWidget {
  const _ComingSoonScreen({required this.title, required this.icon, required this.body});

  final String title;
  final IconData icon;
  final String body;

  @override
  Widget build(BuildContext context) => Scaffold(
    appBar: AppBar(title: Text(title), centerTitle: false),
    body: ComingSoonView(icon: icon, title: 'Bientôt disponible', body: body),
  );
}
