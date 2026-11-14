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
import '../../core/widgets/wordmark.dart';
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
      appBar: AppBar(title: const Wordmark(), centerTitle: false),
      body: RefreshIndicator(
        onRefresh: () async {
          ref.invalidate(listingTotalProvider);
          ref.invalidate(smartSearchAvailableProvider);
          await ref.refresh(makesProvider.future).catchError((Object _) => const <Make>[]);
        },
        child: ListView(
          children: [
            const _Hero(),
            if (smart) const _SmartSearch(),
            const _Shortcuts(),
            if (recents.isNotEmpty) _RecentSearches(entries: recents),
            Padding(
              padding: const EdgeInsets.fromLTRB(AppSpace.gutter, AppSpace.s5, AppSpace.gutter, AppSpace.s2),
              child: Text('Marques', style: theme.textTheme.titleMedium),
            ),
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
                for (final make in popularFirst(makes)) ...[
                  ListTile(
                    dense: true,
                    title: Text(make.name ?? ''),
                    trailing: (make.popular ?? false)
                        ? Text('Populaire', style: theme.textTheme.labelMedium?.copyWith(color: AppColors.accent))
                        : const Icon(LucideIcons.chevronRight, size: 18, color: AppColors.inkFaint),
                    onTap: () => context.push(resultsLocation(SearchQuery(make: make.slug))),
                  ),
                  const Divider(),
                ],
              ],
            ),
          ],
        ),
      ),
    );
  }
}

class _Hero extends ConsumerWidget {
  const _Hero();

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final theme = Theme.of(context);
    final total = ref.watch(listingTotalProvider);
    return Container(
      color: AppColors.surface,
      padding: const EdgeInsets.fromLTRB(AppSpace.gutter, AppSpace.s4, AppSpace.gutter, AppSpace.s4),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text('Trouvez votre voiture', style: theme.textTheme.headlineSmall),
          const SizedBox(height: AppSpace.s1),
          Text(
            // The count is secondary: a failure here leaves the rest of the page.
            total.when(data: totalLabel, loading: () => ' ', error: (_, _) => 'Nombre d’annonces indisponible'),
            style: theme.textTheme.bodySmall,
          ),
          const SizedBox(height: AppSpace.s3),
          Semantics(
            button: true,
            label: 'Rechercher une marque, un modèle ou un mot-clé',
            excludeSemantics: true,
            child: InkWell(
              onTap: () => context.push(entryLocation),
              borderRadius: BorderRadius.circular(AppRadius.md),
              child: Container(
                height: kMinTouchTarget,
                padding: const EdgeInsets.symmetric(horizontal: AppSpace.s3),
                decoration: BoxDecoration(
                  border: Border.all(color: AppColors.lineStrong),
                  borderRadius: BorderRadius.circular(AppRadius.md),
                ),
                child: Row(
                  children: [
                    const Icon(LucideIcons.search, size: 18, color: AppColors.inkSoft),
                    const SizedBox(width: AppSpace.s2),
                    Text(
                      'Marque, modèle, mot-clé',
                      style: theme.textTheme.bodyMedium?.copyWith(color: AppColors.inkFaint),
                    ),
                  ],
                ),
              ),
            ),
          ),
        ],
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
    return Container(
      margin: const EdgeInsets.fromLTRB(AppSpace.gutter, AppSpace.s4, AppSpace.gutter, 0),
      padding: const EdgeInsets.all(AppSpace.s3),
      decoration: BoxDecoration(
        color: AppColors.surface,
        border: Border.all(color: AppColors.line),
        borderRadius: BorderRadius.circular(AppRadius.lg),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            children: [
              const Icon(LucideIcons.sparkles, size: 18, color: AppColors.accent),
              const SizedBox(width: AppSpace.s2),
              Text('Recherche intelligente', style: theme.textTheme.titleSmall?.copyWith(fontWeight: AppType.bold)),
            ],
          ),
          const SizedBox(height: AppSpace.s2),
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
              counterText: '',
              isDense: true,
              border: OutlineInputBorder(),
            ),
            onSubmitted: (_) => _submit(),
          ),
          if (_error != null) ...[
            const SizedBox(height: AppSpace.s1),
            Text(_error!, style: theme.textTheme.bodySmall?.copyWith(color: AppColors.danger)),
          ],
          const SizedBox(height: AppSpace.s2),
          SizedBox(
            width: double.infinity,
            child: FilledButton(onPressed: _busy ? null : _submit, child: Text(_busy ? 'Analyse…' : 'Rechercher')),
          ),
        ],
      ),
    );
  }
}

/// Acheter, Louer, Neuf.
class _Shortcuts extends StatelessWidget {
  const _Shortcuts();

  @override
  Widget build(BuildContext context) => Padding(
    padding: const EdgeInsets.fromLTRB(AppSpace.gutter, AppSpace.s4, AppSpace.gutter, 0),
    child: Row(
      children: [
        Expanded(
          child: _Tile(
            icon: LucideIcons.car,
            label: 'Acheter',
            onTap: () => context.push(resultsLocation(SearchQuery.empty)),
          ),
        ),
        const SizedBox(width: AppSpace.s2),
        Expanded(
          child: _Tile(
            icon: LucideIcons.key,
            label: 'Louer',
            onTap: () => context.push(resultsLocation(const SearchQuery(offer: OfferType.LEASING))),
          ),
        ),
        const SizedBox(width: AppSpace.s2),
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
  Widget build(BuildContext context) => Material(
    color: AppColors.surface,
    shape: RoundedRectangleBorder(
      side: const BorderSide(color: AppColors.line),
      borderRadius: BorderRadius.circular(AppRadius.lg),
    ),
    child: InkWell(
      onTap: onTap,
      borderRadius: BorderRadius.circular(AppRadius.lg),
      child: Padding(
        padding: const EdgeInsets.symmetric(vertical: AppSpace.s3),
        child: Column(
          children: [
            Icon(icon, color: AppColors.accent),
            const SizedBox(height: AppSpace.s1),
            Text(label, style: Theme.of(context).textTheme.labelLarge),
          ],
        ),
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
      padding: const EdgeInsets.fromLTRB(AppSpace.gutter, AppSpace.s5, AppSpace.gutter, 0),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text('Vos dernières recherches', style: theme.textTheme.titleMedium),
          const SizedBox(height: AppSpace.s2),
          for (final query in entries) ...[_RecentCard(query: query), const SizedBox(height: AppSpace.s2)],
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
    return Card(
      child: ListTile(
        title: Text(summary, maxLines: 2, overflow: TextOverflow.ellipsis),
        onTap: () => context.push(resultsLocation(query)),
        trailing: IconButton(
          tooltip: 'Oublier cette recherche',
          icon: const Icon(LucideIcons.x, size: 18),
          onPressed: () => ref.read(recentSearchesProvider.notifier).remove(query),
        ),
      ),
    );
  }
}
