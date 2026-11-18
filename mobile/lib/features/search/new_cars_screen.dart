import 'package:autox_api/autox_api.dart' show VehicleCondition;
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:material_ui/material_ui.dart';

import '../../core/format/numbers.dart';
import '../../core/theme/tokens.dart';
import '../../core/widgets/states.dart';
import '../listing/widgets/listing_card.dart';
import 'search_locations.dart';
import 'search_providers.dart';
import 'search_query.dart';

/// "Neuf": new and zero-kilometre cars from professionals — the ordinary search preset to new
/// cars, as on the web (docs/new-cars.md). The makes with new stock, each with its count, then
/// the latest arrivals; everything leads to the results with `etat=new`.
class NewCarsScreen extends ConsumerWidget {
  const NewCarsScreen({super.key});

  static const query = SearchQuery(condition: [VehicleCondition.NEW]);

  /// How many of the latest arrivals the page shows before "Voir toutes".
  static const latest = 6;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final theme = Theme.of(context);
    final makes = ref.watch(makeCountsProvider(query));
    final arrivals = ref.watch(browseResultsProvider(query));

    return Scaffold(
      appBar: AppBar(title: const Text('Neuf'), centerTitle: false),
      body: ListView(
        children: [
          Container(
            color: AppColors.surface,
            padding: const EdgeInsets.fromLTRB(AppSpace.gutter, AppSpace.s4, AppSpace.gutter, AppSpace.s4),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text('Voitures neuves', style: theme.textTheme.headlineSmall),
                const SizedBox(height: AppSpace.s3),
                FilledButton(
                  onPressed: () => context.push(resultsLocation(query)),
                  child: const Text('Voir toutes les voitures neuves'),
                ),
              ],
            ),
          ),
          const _Heading('Marques en stock neuf'),
          makes.when(
            loading: () => const LoadingView(),
            error: (error, _) => FailureView(error: error, onRetry: () => ref.invalidate(makeCountsProvider(query))),
            data: (makes) => Padding(
              padding: const EdgeInsets.symmetric(horizontal: AppSpace.gutter),
              child: Wrap(
                spacing: AppSpace.s1,
                runSpacing: AppSpace.s1,
                children: [
                  for (final make in makes)
                    ActionChip(
                      label: Text('${make.name} · ${formatInteger(make.count)}'),
                      onPressed: () => context.push(resultsLocation(query.copyWith(make: make.slug))),
                    ),
                ],
              ),
            ),
          ),
          const _Heading('Dernières arrivées'),
          ...switch (arrivals.status) {
            ResultsStatus.loading => [const LoadingView()],
            ResultsStatus.error => [
              FailureView(
                error: arrivals.failure!,
                onRetry: () => ref.read(browseResultsProvider(query).notifier).retry(),
              ),
            ],
            ResultsStatus.ready => [
              for (final listing in arrivals.items.take(latest))
                Padding(
                  padding: const EdgeInsets.fromLTRB(AppSpace.gutter, 0, AppSpace.gutter, AppSpace.s3),
                  child: ListingCard(listing: listing),
                ),
            ],
          },
        ],
      ),
    );
  }
}

class _Heading extends StatelessWidget {
  const _Heading(this.text);

  final String text;

  @override
  Widget build(BuildContext context) => Padding(
    padding: const EdgeInsets.fromLTRB(AppSpace.gutter, AppSpace.s5, AppSpace.gutter, AppSpace.s2),
    child: Text(text, style: Theme.of(context).textTheme.titleLarge),
  );
}
