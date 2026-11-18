import 'package:autox_api/autox_api.dart' show OfferType, ResultCount, SearchSort;
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:lucide_icons_flutter/lucide_icons.dart';
import 'package:material_ui/material_ui.dart';

import '../../core/format/numbers.dart';
import '../../core/labels/labels.g.dart';
import '../../core/theme/tokens.dart';
import '../../core/widgets/states.dart';
import '../../core/widgets/surfaces.dart';
import '../listing/widgets/listing_card.dart';
import 'filters_screen.dart';
import 'recent_searches.dart';
import 'search_locations.dart';
import 'search_providers.dart';
import 'search_query.dart';
import 'smart_search.dart';

/// The results of a search: `/recherche/resultats?…`, with the site's own query parameters, so
/// the search is in the location and survives the app being put away.
class ResultsScreen extends ConsumerStatefulWidget {
  const ResultsScreen({super.key, required this.query, this.interpretation});

  final SearchQuery query;

  /// Set when the search came from "Recherche intelligente": what was understood.
  final Interpretation? interpretation;

  @override
  ConsumerState<ResultsScreen> createState() => _ResultsScreenState();
}

class _ResultsScreenState extends ConsumerState<ResultsScreen> {
  final _scroll = ScrollController();
  bool _noteDismissed = false;

  /// How close to the end of the list the next page is asked for.
  static const double _loadMoreExtent = 800;

  SearchQuery get query => widget.query;

  @override
  void initState() {
    super.initState();
    _scroll.addListener(_onScroll);
    // Kept once it has been run, as on the web — after the first frame, not during the build.
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (mounted) {
        ref.read(recentSearchesProvider.notifier).record(query);
      }
    });
  }

  @override
  void dispose() {
    _scroll.dispose();
    super.dispose();
  }

  void _onScroll() {
    if (_scroll.position.extentAfter < _loadMoreExtent) {
      ref.read(searchResultsProvider(query).notifier).loadMore();
    }
  }

  void _go(SearchQuery next) => context.replace(resultsLocation(next));

  Future<void> _openFilters() async {
    final next = await Navigator.of(context)
        .push<SearchQuery>(MaterialPageRoute(fullscreenDialog: true, builder: (_) => FiltersScreen(initial: query)));
    if (next != null && next != query && mounted) {
      _go(next);
    }
  }

  @override
  Widget build(BuildContext context) {
    final results = ref.watch(searchResultsProvider(query));
    final count = ref.watch(resultCountProvider(query));
    final names = ref.watch(vehicleNamesProvider((query.make, query.model))).value ?? (make: null, model: null);
    final chips = describeFilters(query, names: names);
    final interpretation = _noteDismissed ? null : widget.interpretation;

    return Scaffold(
      appBar: AppBar(
        title: Text(query.isLeasing ? 'Location' : 'Annonces'),
        centerTitle: false,
        actions: [
          IconButton(
            tooltip: 'Modifier la recherche',
            icon: const Icon(LucideIcons.search),
            onPressed: () => context.push(entryLocation, extra: query),
          ),
        ],
      ),
      body: RefreshIndicator(
        onRefresh: () async {
          ref.invalidate(resultCountProvider(query));
          ref.read(searchResultsProvider(query).notifier).retry();
        },
        // The toolbar scrolls with the results: with large system text it would leave a small
        // phone no room for them.
        child: CustomScrollView(
          controller: _scroll,
          physics: const AlwaysScrollableScrollPhysics(),
          slivers: [
            SliverToBoxAdapter(
              child: _Toolbar(
                query: query,
                count: count.value,
                filterCount: activeFilterCount(query),
                onOffer: (offer) => _go(switchOffer(query, offer)),
                onFilters: _openFilters,
                onSort: (sort) => _go(query.copyWith(sort: sort)),
              ),
            ),
            SliverToBoxAdapter(
              child: _Heading(
                chips: chips,
                interpretation: interpretation,
                onDismissNote: () => setState(() => _noteDismissed = true),
                onRemove: (filter) => _go(filter.without),
                onClear: () => _go(withoutFilters(query)),
              ),
            ),
            ..._results(results),
          ],
        ),
      ),
    );
  }

  List<Widget> _results(ResultsState results) {
    switch (results.status) {
      case ResultsStatus.loading:
        return const [SliverFillRemaining(hasScrollBody: false, child: LoadingView(label: 'Recherche en cours…'))];
      case ResultsStatus.error:
        return [
          SliverFillRemaining(
            hasScrollBody: false,
            child: FailureView(
              error: results.failure!,
              onRetry: () => ref.read(searchResultsProvider(query).notifier).retry(),
            ),
          ),
        ];
      case ResultsStatus.ready:
        if (results.items.isEmpty) {
          return [
            SliverFillRemaining(
              hasScrollBody: false,
              child: _Empty(canClear: activeFilterCount(query) > 0, onClear: () => _go(withoutFilters(query))),
            ),
          ];
        }
        return [
          SliverPadding(
            padding: const EdgeInsets.symmetric(horizontal: AppSpace.gutter),
            sliver: SliverList.separated(
              itemCount: results.items.length,
              separatorBuilder: (_, _) => const SizedBox(height: AppSpace.s3),
              itemBuilder: (_, index) => ListingCard(listing: results.items[index]),
            ),
          ),
          SliverToBoxAdapter(
            child: _Footer(results: results, onRetry: () => ref.read(searchResultsProvider(query).notifier).loadMore()),
          ),
        ];
    }
  }
}

/// Acheter / Louer; then the count, Filtres with how many are set, and the sort order.
class _Toolbar extends StatelessWidget {
  const _Toolbar({
    required this.query,
    required this.count,
    required this.filterCount,
    required this.onOffer,
    required this.onFilters,
    required this.onSort,
  });

  final SearchQuery query;
  final ResultCount? count;
  final int filterCount;
  final ValueChanged<OfferType> onOffer;
  final VoidCallback onFilters;
  final ValueChanged<SearchSort> onSort;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final options = query.isLeasing ? leasingSortOptions : saleSortOptions;
    final sort = sortFor(query.sort, query.offer);
    final sortLabel = options.where((option) => option.$1 == sort).firstOrNull?.$2 ?? options.first.$2;

    return Padding(
      padding: const EdgeInsets.fromLTRB(AppSpace.gutter, AppSpace.s1, AppSpace.gutter, AppSpace.s2),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          Semantics(
            label: 'Acheter ou louer',
            container: true,
            child: SegmentedButton<OfferType>(
              showSelectedIcon: false,
              segments: const [
                ButtonSegment(value: OfferType.SALE, label: Text('Acheter')),
                ButtonSegment(value: OfferType.LEASING, label: Text('Louer')),
              ],
              selected: {query.offer},
              onSelectionChanged: (selection) {
                if (selection.first != query.offer) {
                  onOffer(selection.first);
                }
              },
            ),
          ),
          const SizedBox(height: AppSpace.s3),
          // The count, and the buttons at the end; under it when large text leaves no room beside.
          Wrap(
            alignment: WrapAlignment.spaceBetween,
            crossAxisAlignment: WrapCrossAlignment.center,
            spacing: AppSpace.s2,
            runSpacing: AppSpace.s2,
            children: [
              Text(count == null ? ' ' : resultCountLabel(count!), style: theme.textTheme.titleLarge),
              Row(
                mainAxisSize: MainAxisSize.min,
                children: [
                  Flexible(
                    child: _FiltersButton(count: filterCount, onPressed: onFilters),
                  ),
                  const SizedBox(width: AppSpace.s2),
                  _sortButton(options, sort, sortLabel),
                ],
              ),
            ],
          ),
        ],
      ),
    );
  }

  Widget _sortButton(List<(SearchSort, String)> options, SearchSort sort, String sortLabel) =>
      PopupMenuButton<SearchSort>(
        tooltip: 'Trier par : $sortLabel',
        initialValue: sort,
        onSelected: onSort,
        icon: const Icon(LucideIcons.arrowDownUp, size: AppSizes.icon),
        style: roundIconButtonStyle,
        itemBuilder: (_) => [for (final (value, label) in options) PopupMenuItem(value: value, child: Text(label))],
      );
}

/// "Filtres", with how many are set in a blue dot; TalkBack hears "Filtres, 2 actifs".
class _FiltersButton extends StatelessWidget {
  const _FiltersButton({required this.count, required this.onPressed});

  final int count;
  final VoidCallback onPressed;

  @override
  Widget build(BuildContext context) => Semantics(
    button: true,
    label: switch (count) {
      0 => 'Filtres',
      1 => 'Filtres, 1 actif',
      _ => 'Filtres, $count actifs',
    },
    excludeSemantics: true,
    child: OutlinedButton(
      onPressed: onPressed,
      child: Row(
        mainAxisSize: MainAxisSize.min,
        children: [
          const Icon(LucideIcons.slidersHorizontal, size: AppSizes.icon),
          const SizedBox(width: AppSpace.s2),
          const Flexible(child: Text('Filtres', overflow: TextOverflow.ellipsis, softWrap: false)),
          if (count > 0) ...[
            const SizedBox(width: AppSpace.s2),
            Container(
              constraints: const BoxConstraints(minWidth: AppSizes.icon),
              padding: const EdgeInsets.symmetric(horizontal: AppSpace.s1),
              decoration: const BoxDecoration(
                color: AppColors.accent,
                borderRadius: BorderRadius.all(Radius.circular(AppRadius.pill)),
              ),
              alignment: Alignment.center,
              child: Text(
                '$count',
                style: AppText.tag.copyWith(color: AppColors.accentInk, fontWeight: AppType.bold),
              ),
            ),
          ],
        ],
      ),
    ),
  );
}

/// The smart-search note, and the filters that are set, as a row of chips to remove.
class _Heading extends StatelessWidget {
  const _Heading({
    required this.chips,
    required this.interpretation,
    required this.onDismissNote,
    required this.onRemove,
    required this.onClear,
  });

  final List<AppliedFilter> chips;
  final Interpretation? interpretation;
  final VoidCallback onDismissNote;
  final ValueChanged<AppliedFilter> onRemove;
  final VoidCallback onClear;

  @override
  Widget build(BuildContext context) => Padding(
    padding: const EdgeInsets.only(bottom: AppSpace.s3),
    child: Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        if (interpretation != null)
          Padding(
            padding: const EdgeInsets.fromLTRB(AppSpace.gutter, 0, AppSpace.gutter, AppSpace.s2),
            child: InterpretationNote(interpretation: interpretation!, onDismiss: onDismissNote),
          ),
        if (chips.isNotEmpty)
          // One line that scrolls sideways, every chip built, so each can be reached and removed.
          SingleChildScrollView(
            scrollDirection: Axis.horizontal,
            padding: const EdgeInsets.symmetric(horizontal: AppSpace.gutter),
            child: Row(
              children: [
                for (final chip in chips) ...[
                  InputChip(
                    label: Text(chip.label),
                    backgroundColor: AppColors.accentSoft,
                    side: const BorderSide(color: AppColors.accentSoft),
                    labelStyle: AppText.tag.copyWith(fontSize: AppType.tsm, color: AppColors.accentStrong),
                    onDeleted: () => onRemove(chip),
                    deleteIcon: const Icon(LucideIcons.x, size: AppSizes.iconSmall),
                    deleteIconColor: AppColors.accentStrong,
                    deleteButtonTooltipMessage: 'Retirer le filtre ${chip.label}',
                  ),
                  const SizedBox(width: AppSpace.s2),
                ],
                if (chips.length > 1) TextButton(onPressed: onClear, child: const Text('Tout effacer')),
              ],
            ),
          ),
      ],
    ),
  );
}

/// "22 annonces", "1 annonce", "Plus de 10 000 annonces" — the web's wording.
String resultCountLabel(ResultCount count) {
  final n = count.count ?? 0;
  if (count.capped ?? false) {
    return 'Plus de ${formatInteger(n)} annonces';
  }
  return '${formatInteger(n)} ${n < 2 ? 'annonce' : 'annonces'}';
}

/// "Recherche intelligente : « … »", and what it could not use.
class InterpretationNote extends StatelessWidget {
  const InterpretationNote({super.key, required this.interpretation, required this.onDismiss});

  final Interpretation interpretation;
  final VoidCallback onDismiss;

  @override
  Widget build(BuildContext context) {
    final style = Theme.of(context).textTheme.bodySmall;
    return Container(
      padding: const EdgeInsets.fromLTRB(AppSpace.s3, AppSpace.s2, AppSpace.s1, AppSpace.s2),
      decoration: BoxDecoration(color: AppColors.accentSoft, borderRadius: BorderRadius.circular(AppRadius.md)),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          const Padding(
            padding: EdgeInsets.only(top: 2),
            child: Icon(LucideIcons.sparkles, size: 16, color: AppColors.accent),
          ),
          const SizedBox(width: AppSpace.s2),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text('Recherche intelligente : « ${interpretation.text} »', style: style),
                if (interpretation.notUnderstood.isNotEmpty)
                  Text('Non pris en compte : ${interpretation.notUnderstood.join(', ')}', style: style),
              ],
            ),
          ),
          IconButton(tooltip: 'Masquer ce rappel', icon: const Icon(LucideIcons.x, size: 16), onPressed: onDismiss),
        ],
      ),
    );
  }
}

class _Empty extends StatelessWidget {
  const _Empty({required this.canClear, required this.onClear});

  final bool canClear;
  final VoidCallback onClear;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return Padding(
      padding: const EdgeInsets.all(AppSpace.s5),
      child: Column(
        mainAxisAlignment: MainAxisAlignment.center,
        children: [
          const Icon(LucideIcons.searchX, size: 36, color: AppColors.inkFaint),
          const SizedBox(height: AppSpace.s3),
          Text(
            'Aucune annonce ne correspond à votre recherche',
            style: theme.textTheme.titleMedium,
            textAlign: TextAlign.center,
          ),
          const SizedBox(height: AppSpace.s1),
          Text(
            'Élargissez la fourchette de prix ou de kilométrage, ou retirez un filtre.',
            style: theme.textTheme.bodySmall,
            textAlign: TextAlign.center,
          ),
          if (canClear) ...[
            const SizedBox(height: AppSpace.s4),
            OutlinedButton(onPressed: onClear, child: const Text('Effacer tous les filtres')),
          ],
        ],
      ),
    );
  }
}

/// Under the last row: the next page loading, its failure, a button for it, or the end of the list.
/// The spinner only while a page is really on its way: an idle one would animate for ever.
class _Footer extends StatelessWidget {
  const _Footer({required this.results, required this.onRetry});

  final ResultsState results;
  final VoidCallback onRetry;

  @override
  Widget build(BuildContext context) {
    final Widget child;
    if (results.loadingMore) {
      child = const LoadingView(label: 'Chargement…');
    } else if (results.loadMoreFailure != null) {
      child = Column(
        children: [
          Text(results.loadMoreFailure!.message, textAlign: TextAlign.center),
          const SizedBox(height: AppSpace.s2),
          OutlinedButton(onPressed: onRetry, child: const Text('Voir plus d’annonces')),
        ],
      );
    } else if (results.hasMore) {
      // Scrolling near the end asks for the next page by itself; the button is for a first
      // page too short to scroll, and for anyone who does not scroll (TalkBack, switch access).
      child = OutlinedButton(onPressed: onRetry, child: const Text('Voir plus d’annonces'));
    } else {
      child = Text('Vous avez vu toutes les annonces.', style: Theme.of(context).textTheme.bodySmall);
    }
    return Padding(
      padding: const EdgeInsets.symmetric(horizontal: AppSpace.gutter, vertical: AppSpace.s4),
      child: Center(child: child),
    );
  }
}
