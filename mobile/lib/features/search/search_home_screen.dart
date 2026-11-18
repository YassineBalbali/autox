import 'package:autox_api/autox_api.dart' show ListingSearchCriteria, Make, OfferType, ResultCount;
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:lucide_icons_flutter/lucide_icons.dart';
import 'package:material_ui/material_ui.dart';

import '../../core/api/api_failure.dart';
import '../../core/api/api_providers.dart';
import '../../core/format/numbers.dart';
import '../../core/reference/reference_providers.dart';
import '../../core/theme/tokens.dart';
import '../../core/widgets/states.dart';
import '../../core/widgets/surfaces.dart';
import '../../core/widgets/wordmark.dart';
import '../listing/listing_locations.dart';
import 'recent_searches.dart';
import 'search_locations.dart';
import 'search_providers.dart';
import 'search_query.dart';
import 'smart_search.dart';

/// How many listings are on AutoX: the count for an empty search.
final listingTotalProvider = FutureProvider<ResultCount>((ref) async {
  try {
    return await ref.watch(searchApiProvider).count(ListingSearchCriteria());
  } on Object catch (error) {
    throw ApiFailure.from(error);
  }
});

/// The Rechercher tab: the way into a search. The search field, "Recherche intelligente" when
/// the server offers it, shortcuts to buying, leasing and new cars, the last searches, and the
/// catalogue's makes.
class SearchHomeScreen extends ConsumerWidget {
  const SearchHomeScreen({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final makes = ref.watch(makesProvider);
    final recents = ref.watch(recentSearchesProvider);
    final smart = ref.watch(smartSearchAvailableProvider).value ?? false;
    final theme = Theme.of(context);

    return Scaffold(
      body: SafeArea(
        bottom: false,
        child: RefreshIndicator(
          onRefresh: () async {
            ref.invalidate(listingTotalProvider);
            ref.invalidate(smartSearchAvailableProvider);
            await ref.refresh(makesProvider.future).catchError((Object _) => const <Make>[]);
          },
          child: ListView(
            padding: const EdgeInsets.fromLTRB(AppSpace.gutter, AppSpace.s4, AppSpace.gutter, AppSpace.s5),
            children: [
              const _Hero(),
              if (smart) ...[const SizedBox(height: AppSpace.s4), const _SmartSearch()],
              const SizedBox(height: AppSpace.s4),
              const _Shortcuts(),
              if (recents.isNotEmpty) _RecentSearches(entries: recents),
              const SizedBox(height: AppSpace.s5),
              const _DealersCard(),
              const SizedBox(height: AppSpace.s5),
              Text('Marques', style: theme.textTheme.titleLarge),
              const SizedBox(height: AppSpace.s3),
              ...makes.when(
                skipLoadingOnRefresh: false,
                loading: () => const [LoadingView(label: 'Chargement des marques')],
                error: (error, _) => [
                  FailureView(
                    error: error,
                    onRetry: () {
                      ref.invalidate(makesProvider);
                      ref.invalidate(listingTotalProvider);
                    },
                  ),
                ],
                data: (makes) => [
                  AppCard(
                    padding: EdgeInsets.zero,
                    child: Column(
                      children: [
                        for (final (index, make) in popularFirst(makes).indexed) ...[
                          if (index > 0) const Divider(indent: AppSpace.gutter, endIndent: AppSpace.gutter),
                          ListTile(
                            title: Text(make.name ?? ''),
                            trailing: (make.popular ?? false)
                                ? Text(
                                    'Populaire',
                                    style: theme.textTheme.labelMedium?.copyWith(color: AppColors.accentStrong),
                                  )
                                : const Icon(LucideIcons.chevronRight, size: AppSizes.icon, color: AppColors.inkFaint),
                            onTap: () => context.push(resultsLocation(SearchQuery(make: make.slug))),
                          ),
                        ],
                      ],
                    ),
                  ),
                ],
              ),
            ],
          ),
        ),
      ),
    );
  }
}

/// The wordmark, the big title, the count, and the search field.
class _Hero extends ConsumerWidget {
  const _Hero();

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final theme = Theme.of(context);
    final total = ref.watch(listingTotalProvider);
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        const Wordmark(),
        const SizedBox(height: AppSpace.s5),
        Text('Trouvez votre voiture', style: theme.textTheme.headlineLarge),
        const SizedBox(height: AppSpace.s1),
        Text(
          // The count is secondary: a failure here leaves the rest of the page.
          total.when(data: totalLabel, loading: () => ' ', error: (_, _) => 'Nombre d’annonces indisponible'),
          style: theme.textTheme.bodySmall,
        ),
        const SizedBox(height: AppSpace.s4),
        Semantics(
          container: true,
          button: true,
          label: 'Rechercher une marque, un modèle ou un mot-clé',
          excludeSemantics: true,
          child: DecoratedBox(
            decoration: const BoxDecoration(
              borderRadius: BorderRadius.all(Radius.circular(AppRadius.pill)),
              boxShadow: AppShadows.floating,
            ),
            child: Material(
              color: AppColors.surface,
              shape: const StadiumBorder(),
              clipBehavior: Clip.antiAlias,
              child: InkWell(
                onTap: () => context.push(entryLocation),
                child: ConstrainedBox(
                  constraints: const BoxConstraints(minHeight: AppSizes.control),
                  child: Padding(
                    padding: const EdgeInsets.fromLTRB(AppSpace.s5, AppSpace.s2, AppSpace.s2, AppSpace.s2),
                    child: Row(
                      children: [
                        const Icon(LucideIcons.search, size: AppSizes.icon, color: AppColors.ink),
                        const SizedBox(width: AppSpace.s3),
                        // Shrinks rather than overflows: a narrow phone, or large system text.
                        Expanded(
                          child: Text(
                            'Marque, modèle, mot-clé',
                            overflow: TextOverflow.ellipsis,
                            style: theme.textTheme.bodyLarge?.copyWith(color: AppColors.inkFaint),
                          ),
                        ),
                        const SizedBox(width: AppSpace.s2),
                        Container(
                          width: AppSizes.iconTile,
                          height: AppSizes.iconTile,
                          decoration: const BoxDecoration(color: AppColors.accent, shape: BoxShape.circle),
                          child: const Icon(LucideIcons.arrowRight, size: AppSizes.icon, color: AppColors.accentInk),
                        ),
                      ],
                    ),
                  ),
                ),
              ),
            ),
          ),
        ),
      ],
    );
  }
}

/// "Annuaire des professionnels", as a card.
class _DealersCard extends StatelessWidget {
  const _DealersCard();

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return MergeSemantics(
      child: Semantics(
        button: true,
        child: AppCard(
          onTap: () => context.push(dealersLocation),
          child: Row(
            children: [
              const IconTile(icon: LucideIcons.store),
              const SizedBox(width: AppSpace.s3),
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text('Annuaire des professionnels', style: theme.textTheme.titleMedium),
                    Text(
                      'Garages et concessions, leurs coordonnées et leurs annonces',
                      style: theme.textTheme.bodySmall,
                    ),
                  ],
                ),
              ),
              const SizedBox(width: AppSpace.s2),
              const Icon(LucideIcons.chevronRight, size: AppSizes.icon, color: AppColors.inkFaint),
            ],
          ),
        ),
      ),
    );
  }
}

/// "12 480 annonces", or "Plus de 10 000 annonces" when the server capped the count.
String totalLabel(ResultCount count) {
  final n = count.count ?? 0;
  if (count.capped ?? false) {
    return 'Plus de ${formatInteger(n)} annonces';
  }
  return switch (n) {
    0 => 'Aucune annonce',
    1 => '1 annonce',
    _ => '${formatInteger(n)} annonces',
  };
}

/// "Recherche intelligente": a description, interpreted by the server, then the results.
class _SmartSearch extends ConsumerStatefulWidget {
  const _SmartSearch();

  @override
  ConsumerState<_SmartSearch> createState() => _SmartSearchState();
}

class _SmartSearchState extends ConsumerState<_SmartSearch> {
  final _controller = TextEditingController();
  bool _busy = false;
  String? _error;

  @override
  void dispose() {
    _controller.dispose();
    super.dispose();
  }

  Future<void> _submit() async {
    if (_controller.text.trim().isEmpty || _busy) {
      return;
    }
    setState(() {
      _busy = true;
      _error = null;
    });
    try {
      final result = await ref.read(interpreterProvider)(_controller.text);
      if (mounted) {
        setState(() => _busy = false);
        await context.push(resultsLocation(result.query), extra: result.interpretation);
      }
    } on ApiFailure catch (failure) {
      if (mounted) {
        setState(() {
          _busy = false;
          _error = interpretFailureMessage(failure);
        });
      }
    }
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return AppCard(
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            children: [
              const IconTile(icon: LucideIcons.sparkles),
              const SizedBox(width: AppSpace.s3),
              Expanded(child: Text('Recherche intelligente', style: theme.textTheme.titleMedium)),
            ],
          ),
          const SizedBox(height: AppSpace.s3),
          TextField(
            controller: _controller,
            enabled: !_busy,
            maxLength: maxDescription,
            minLines: 1,
            maxLines: 3,
            textInputAction: TextInputAction.search,
            decoration: const InputDecoration(
              hintText: 'Décrivez la voiture que vous cherchez',
              helperText: 'Par exemple : « SUV diesel automatique moins de 15 000 € »',
              helperMaxLines: 2,
              counterText: '',
              fillColor: AppColors.surfaceSunk,
            ),
            onSubmitted: (_) => _submit(),
          ),
          if (_error != null) ...[
            const SizedBox(height: AppSpace.s1),
            Text(_error!, style: theme.textTheme.bodySmall?.copyWith(color: AppColors.danger)),
          ],
          const SizedBox(height: AppSpace.s3),
          SizedBox(
            width: double.infinity,
            child: FilledButton(onPressed: _busy ? null : _submit, child: Text(_busy ? 'Analyse…' : 'Rechercher')),
          ),
        ],
      ),
    );
  }
}

/// Acheter, Louer, Neuf: three large tiles.
class _Shortcuts extends StatelessWidget {
  const _Shortcuts();

  @override
  Widget build(BuildContext context) => IntrinsicHeight(
    child: Row(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        Expanded(
          child: _Tile(
            icon: LucideIcons.car,
            label: 'Acheter',
            onTap: () => context.push(resultsLocation(SearchQuery.empty)),
          ),
        ),
        const SizedBox(width: AppSpace.s3),
        Expanded(
          child: _Tile(
            icon: LucideIcons.key,
            label: 'Louer',
            onTap: () => context.push(resultsLocation(const SearchQuery(offer: OfferType.LEASING))),
          ),
        ),
        const SizedBox(width: AppSpace.s3),
        Expanded(
          child: _Tile(icon: LucideIcons.sparkle, label: 'Neuf', onTap: () => context.push(newCarsLocation)),
        ),
      ],
    ),
  );
}

class _Tile extends StatelessWidget {
  const _Tile({required this.icon, required this.label, required this.onTap});

  final IconData icon;
  final String label;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) => Semantics(
    button: true,
    child: AppCard(
      onTap: onTap,
      padding: const EdgeInsets.fromLTRB(AppSpace.s3, AppSpace.s4, AppSpace.s3, AppSpace.s4),
      // The icon above the title, both centred in the tile.
      child: Column(
        mainAxisAlignment: MainAxisAlignment.center,
        children: [
          IconTile(icon: icon),
          const SizedBox(height: AppSpace.s3),
          Text(
            label,
            style: Theme.of(context).textTheme.titleMedium,
            textAlign: TextAlign.center,
            maxLines: 1,
            overflow: TextOverflow.ellipsis,
          ),
        ],
      ),
    ),
  );
}

/// "Vos dernières recherches": each as its chips, to open again or forget.
class _RecentSearches extends ConsumerWidget {
  const _RecentSearches({required this.entries});

  final List<SearchQuery> entries;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final theme = Theme.of(context);
    return Padding(
      padding: const EdgeInsets.only(top: AppSpace.s5),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text('Vos dernières recherches', style: theme.textTheme.titleLarge),
          const SizedBox(height: AppSpace.s3),
          for (final (index, query) in entries.indexed) ...[
            if (index > 0) const SizedBox(height: AppSpace.s2),
            _RecentCard(query: query),
          ],
        ],
      ),
    );
  }
}

class _RecentCard extends ConsumerWidget {
  const _RecentCard({required this.query});

  final SearchQuery query;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final names = ref.watch(vehicleNamesProvider((query.make, query.model))).value ?? (make: null, model: null);
    final labels = describeFilters(query, names: names).map((filter) => filter.label).toList();
    final summary = [if (query.isLeasing) 'Location', ...labels].join(' · ');
    return AppCard(
      onTap: () => context.push(resultsLocation(query)),
      padding: const EdgeInsets.fromLTRB(AppSpace.card, AppSpace.s2, AppSpace.s1, AppSpace.s2),
      child: Row(
        children: [
          const IconTile(icon: LucideIcons.history),
          const SizedBox(width: AppSpace.s3),
          Expanded(
            child: Semantics(
              button: true,
              child: Text(
                summary,
                maxLines: 2,
                overflow: TextOverflow.ellipsis,
                style: Theme.of(context).textTheme.titleSmall,
              ),
            ),
          ),
          IconButton(
            tooltip: 'Oublier cette recherche',
            icon: const Icon(LucideIcons.x, size: AppSizes.icon),
            color: AppColors.inkSoft,
            onPressed: () => ref.read(recentSearchesProvider.notifier).remove(query),
          ),
        ],
      ),
    );
  }
}
