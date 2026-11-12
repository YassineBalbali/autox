import 'package:autox_api/autox_api.dart' show Make, ResultCount;
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:material_ui/material_ui.dart';

import '../../core/format/numbers.dart';
import '../../core/theme/tokens.dart';
import '../../core/widgets/states.dart';
import '../../core/widgets/wordmark.dart';
import 'search_home_providers.dart';

/// The "Rechercher" tab. In milestone 1 it proves the whole chain on real data: the total
/// number of listings (the hand-written search client) and the catalogue's makes (the
/// generated one). The search itself, with its filters, is milestone 2.
class SearchHomeScreen extends ConsumerWidget {
  const SearchHomeScreen({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final makes = ref.watch(makesProvider);

    return Scaffold(
      appBar: AppBar(title: const Wordmark(), centerTitle: false),
      body: makes.when(
        skipLoadingOnRefresh: false,
        loading: () => const LoadingView(label: 'Chargement des marques'),
        error: (error, _) => FailureView(
          error: error,
          onRetry: () {
            ref.invalidate(makesProvider);
            ref.invalidate(listingTotalProvider);
          },
        ),
        data: (makes) => RefreshIndicator(
          onRefresh: () {
            ref.invalidate(listingTotalProvider);
            return ref.refresh(makesProvider.future);
          },
          child: ListView.separated(
            itemCount: makes.length + 1,
            separatorBuilder: (_, index) => index == 0 ? const SizedBox.shrink() : const Divider(),
            itemBuilder: (context, index) => index == 0 ? const _Header() : _MakeTile(make: makes[index - 1]),
          ),
        ),
      ),
    );
  }
}

class _Header extends ConsumerWidget {
  const _Header();

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final theme = Theme.of(context);
    final total = ref.watch(listingTotalProvider);

    return Padding(
      padding: const EdgeInsets.fromLTRB(AppSpace.gutter, AppSpace.s4, AppSpace.gutter, AppSpace.s3),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text('Trouvez votre voiture', style: theme.textTheme.headlineSmall),
          const SizedBox(height: AppSpace.s1),
          Text(
            // The count is secondary: a failure here leaves the makes on screen.
            total.when(data: totalLabel, loading: () => ' ', error: (_, _) => 'Nombre d’annonces indisponible'),
            style: theme.textTheme.bodySmall,
          ),
          const SizedBox(height: AppSpace.s4),
          Text('Marques', style: theme.textTheme.titleMedium),
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

class _MakeTile extends StatelessWidget {
  const _MakeTile({required this.make});

  final Make make;

  @override
  Widget build(BuildContext context) => ListTile(
    dense: true,
    title: Text(make.name ?? ''),
    trailing: (make.popular ?? false)
        ? Text('Populaire', style: Theme.of(context).textTheme.labelMedium?.copyWith(color: AppColors.accent))
        : null,
  );
}
