import 'package:go_router/go_router.dart';
import 'package:lucide_icons_flutter/lucide_icons.dart';
import 'package:material_ui/material_ui.dart';

import '../../features/search/new_cars_screen.dart';
import '../../features/search/results_screen.dart';
import '../../features/search/search_entry_screen.dart';
import '../../features/search/search_home_screen.dart';
import '../../features/search/search_query.dart';
import '../../features/search/smart_search.dart';
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
GoRouter buildRouter({String initialLocation = AppRoutes.search}) => GoRouter(
  initialLocation: initialLocation,
  routes: [
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
          routes: [
            GoRoute(
              path: AppRoutes.messages,
              builder: (_, _) => const _ComingSoonScreen(
                title: 'Messages',
                icon: LucideIcons.messageSquare,
                body: 'Vos conversations avec les vendeurs apparaîtront ici une fois connecté.',
              ),
            ),
          ],
        ),
        StatefulShellBranch(
          routes: [
            GoRoute(
              path: AppRoutes.account,
              builder: (_, _) => const _ComingSoonScreen(
                title: 'Compte',
                icon: LucideIcons.userRound,
                body: 'La connexion et la création de compte arrivent bientôt.',
              ),
            ),
          ],
        ),
      ],
    ),
  ],
);

/// The bottom bar around the current tab.
class AppShell extends StatelessWidget {
  const AppShell({super.key, required this.shell});

  final StatefulNavigationShell shell;

  @override
  Widget build(BuildContext context) => Scaffold(
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
