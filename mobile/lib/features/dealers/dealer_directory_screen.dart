import 'package:autox_api/autox_api.dart' show DealerCard, DealerSort;
import 'package:cached_network_image/cached_network_image.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:lucide_icons_flutter/lucide_icons.dart';
import 'package:material_ui/material_ui.dart';

import '../../core/theme/tokens.dart';
import '../../core/widgets/states.dart';
import '../listing/listing_locations.dart';
import '../listing/listing_text.dart' show countFr, memberSince;
import '../listing/widgets/listing_row.dart' show imageCacheKey;
import 'dealer_providers.dart';
import 'dealer_text.dart';

/// "Annuaire des professionnels": the garages and dealers that sell on AutoX, by name, city or
/// département, most listings first or by name.
class DealerDirectoryScreen extends ConsumerStatefulWidget {
  const DealerDirectoryScreen({super.key});

  @override
  ConsumerState<DealerDirectoryScreen> createState() => _DealerDirectoryScreenState();
}

class _DealerDirectoryScreenState extends ConsumerState<DealerDirectoryScreen> {
  final _name = TextEditingController();
  final _city = TextEditingController();
  final _scroll = ScrollController();
  DealerFilters _filters = const DealerFilters();
  String? _departement;

  @override
  void initState() {
    super.initState();
    _scroll.addListener(() {
      if (_scroll.position.extentAfter < 600) {
        ref.read(dealerDirectoryProvider(_filters).notifier).loadMore();
      }
    });
  }

  @override
  void dispose() {
    _name.dispose();
    _city.dispose();
    _scroll.dispose();
    super.dispose();
  }

  String? _blank(String text) => text.trim().isEmpty ? null : text.trim();

  void _search() {
    FocusScope.of(context).unfocus();
    setState(
      () => _filters = DealerFilters(
        name: _blank(_name.text),
        city: _blank(_city.text),
        departement: _departement,
        sort: _filters.sort,
      ),
    );
  }

  void _clear() {
    _name.clear();
    _city.clear();
    setState(() {
      _departement = null;
      _filters = DealerFilters(sort: _filters.sort);
    });
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final directory = ref.watch(dealerDirectoryProvider(_filters));

    return Scaffold(
      appBar: AppBar(title: const Text('Professionnels'), centerTitle: false),
      body: CustomScrollView(
        controller: _scroll,
        slivers: [
          SliverToBoxAdapter(
            child: Container(
              color: AppColors.surface,
              padding: const EdgeInsets.fromLTRB(AppSpace.gutter, AppSpace.s4, AppSpace.gutter, AppSpace.s4),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.stretch,
                children: [
                  Semantics(
                    container: true,
                    header: true,
                    child: Text('Annuaire des professionnels', style: theme.textTheme.headlineSmall),
                  ),
                  const SizedBox(height: AppSpace.s3),
                  TextField(
                    controller: _name,
                    textInputAction: TextInputAction.search,
                    decoration: const InputDecoration(
                      labelText: 'Nom',
                      hintText: 'Garage, concession…',
                      isDense: true,
                      border: OutlineInputBorder(),
                    ),
                    onSubmitted: (_) => _search(),
                  ),
                  const SizedBox(height: AppSpace.s2),
                  TextField(
                    controller: _city,
                    textInputAction: TextInputAction.search,
                    decoration: const InputDecoration(
                      labelText: 'Ville ou code postal',
                      isDense: true,
                      border: OutlineInputBorder(),
                    ),
                    onSubmitted: (_) => _search(),
                  ),
                  const SizedBox(height: AppSpace.s2),
                  DropdownButtonFormField<String?>(
                    initialValue: _departement,
                    isExpanded: true,
                    decoration: const InputDecoration(
                      labelText: 'Département',
                      isDense: true,
                      border: OutlineInputBorder(),
                    ),
                    items: [
                      const DropdownMenuItem<String?>(child: Text('Tous')),
                      for (final entry in directory.departements)
                        if (entry.code != null)
                          DropdownMenuItem<String?>(
                            value: entry.code,
                            child: Text('${departementLabel(entry.code)} (${entry.dealers ?? 0})'),
                          ),
                      // A département asked for but no longer listed is still shown as chosen.
                      if (_departement != null && !directory.departements.any((entry) => entry.code == _departement))
                        DropdownMenuItem<String?>(value: _departement, child: Text(departementLabel(_departement))),
                    ],
                    onChanged: (code) => setState(() => _departement = code),
                  ),
                  const SizedBox(height: AppSpace.s3),
                  FilledButton(onPressed: _search, child: const Text('Rechercher')),
                ],
              ),
            ),
          ),
          SliverToBoxAdapter(
            child: Padding(
              padding: const EdgeInsets.fromLTRB(AppSpace.gutter, AppSpace.s3, AppSpace.gutter, AppSpace.s2),
              child: Row(
                children: [
                  Expanded(
                    child: Text(
                      directory.total == null
                          ? 'Professionnels'
                          : countFr(directory.total!, 'professionnel', 'professionnels'),
                      style: theme.textTheme.titleMedium,
                    ),
                  ),
                  PopupMenuButton<DealerSort>(
                    tooltip: 'Trier par',
                    initialValue: _filters.sort,
                    onSelected: (sort) => setState(
                      () => _filters = DealerFilters(
                        name: _filters.name,
                        city: _filters.city,
                        departement: _filters.departement,
                        sort: sort,
                      ),
                    ),
                    itemBuilder: (_) => const [
                      PopupMenuItem(value: DealerSort.LISTINGS, child: Text('Nombre d’annonces')),
                      PopupMenuItem(value: DealerSort.NAME, child: Text('Nom')),
                    ],
                    child: Row(
                      children: [
                        const Icon(LucideIcons.arrowDownUp, size: 16),
                        const SizedBox(width: AppSpace.s1),
                        Text(_filters.sort == DealerSort.NAME ? 'Nom' : 'Nombre d’annonces'),
                      ],
                    ),
                  ),
                ],
              ),
            ),
          ),
          ..._body(directory),
        ],
      ),
    );
  }

  List<Widget> _body(DirectoryState directory) {
    if (directory.loading) {
      return const [SliverFillRemaining(hasScrollBody: false, child: LoadingView())];
    }
    if (directory.failure != null) {
      return [
        SliverFillRemaining(
          hasScrollBody: false,
          child: FailureView(
            error: directory.failure!,
            onRetry: () => ref.read(dealerDirectoryProvider(_filters).notifier).retry(),
          ),
        ),
      ];
    }
    if (directory.items.isEmpty) {
      return [
        SliverFillRemaining(
          hasScrollBody: false,
          child: Padding(
            padding: const EdgeInsets.all(AppSpace.s5),
            child: Column(
              children: [
                Text(
                  _filters.isFiltered
                      ? 'Aucun professionnel ne correspond à cette recherche.'
                      : 'Aucun professionnel n’a d’annonce en ligne pour le moment.',
                  textAlign: TextAlign.center,
                ),
                if (_filters.isFiltered) ...[
                  const SizedBox(height: AppSpace.s3),
                  OutlinedButton(onPressed: _clear, child: const Text('Effacer la recherche')),
                ],
              ],
            ),
          ),
        ),
      ];
    }
    return [
      SliverList.separated(
        itemCount: directory.items.length,
        separatorBuilder: (_, _) => const Divider(),
        itemBuilder: (_, index) => DealerCardTile(card: directory.items[index]),
      ),
      SliverToBoxAdapter(
        child: Padding(
          padding: const EdgeInsets.all(AppSpace.s4),
          child: Center(
            child: directory.loadingMore
                ? const LoadingView(label: 'Chargement…')
                : directory.loadMoreFailure != null
                ? OutlinedButton(
                    onPressed: () => ref.read(dealerDirectoryProvider(_filters).notifier).loadMore(),
                    child: const Text('Réessayer'),
                  )
                // Scrolling near the end asks by itself; the button is for a page too short to
                // scroll, and for anyone who does not scroll.
                : directory.nextPage != null
                ? OutlinedButton(
                    onPressed: () => ref.read(dealerDirectoryProvider(_filters).notifier).loadMore(),
                    child: const Text('Voir plus de professionnels'),
                  )
                : const SizedBox.shrink(),
          ),
        ),
      ),
    ];
  }
}

/// One dealer in the directory: logo, name, place, what they have online, since when.
class DealerCardTile extends StatelessWidget {
  const DealerCardTile({super.key, required this.card});

  final DealerCard card;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final place = dealerPlace(card.city, card.departement);
    final since = memberSince(card.memberSince);
    return Material(
      color: AppColors.surface,
      child: InkWell(
        onTap: card.slug == null ? null : () => context.push(dealerLocation(card.slug!)),
        child: Padding(
          padding: const EdgeInsets.symmetric(horizontal: AppSpace.gutter, vertical: AppSpace.s3),
          child: Row(
            children: [
              DealerLogo(url: card.logoUrl),
              const SizedBox(width: AppSpace.s3),
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(card.name ?? '', style: theme.textTheme.titleSmall?.copyWith(fontWeight: AppType.bold)),
                    if (place != null) Text(place, style: theme.textTheme.bodySmall),
                    Text(cardListings(card), style: theme.textTheme.bodySmall?.copyWith(color: AppColors.ink)),
                    if (since != null) Text(since, style: theme.textTheme.labelSmall),
                  ],
                ),
              ),
              const Icon(LucideIcons.chevronRight, size: 18, color: AppColors.inkFaint),
            ],
          ),
        ),
      ),
    );
  }
}

/// The dealer's logo, or a shop icon.
class DealerLogo extends StatelessWidget {
  const DealerLogo({super.key, required this.url, this.size = 48});

  final String? url;
  final double size;

  @override
  Widget build(BuildContext context) {
    final placeholder = Container(
      color: AppColors.surfaceSunk,
      alignment: Alignment.center,
      child: Icon(LucideIcons.store, color: AppColors.inkFaint, size: size / 2),
    );
    return ClipRRect(
      borderRadius: BorderRadius.circular(AppRadius.md),
      child: SizedBox.square(
        dimension: size,
        child: (url ?? '').isEmpty
            ? placeholder
            : CachedNetworkImage(
                imageUrl: url!,
                cacheKey: imageCacheKey(url!),
                fit: BoxFit.contain,
                placeholder: (_, _) => placeholder,
                errorWidget: (_, _, _) => placeholder,
              ),
      ),
    );
  }
}
