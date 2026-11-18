import 'package:autox_api/autox_api.dart' show DealerPage, OfferType;
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:lucide_icons_flutter/lucide_icons.dart';
import 'package:material_ui/material_ui.dart';

import '../../core/api/api_failure.dart';
import '../../core/links.dart';
import '../../core/theme/tokens.dart';
import '../../core/widgets/states.dart';
import '../listing/listing_text.dart' show memberSince;
import '../listing/widgets/listing_card.dart';
import '../listing/widgets/listing_parts.dart' show PageSection;
import '../messages/contact_seller.dart';
import '../search/search_providers.dart';
import '../search/search_query.dart';
import 'dealer_directory_screen.dart' show DealerLogo;
import 'dealer_providers.dart';
import 'dealer_text.dart';

/// A dealer's page, `/professionnels/:slug`: who they are, how to reach them, and their listings
/// — the site's search, limited to this seller.
class DealerScreen extends ConsumerStatefulWidget {
  const DealerScreen({super.key, required this.slug, this.write = false});

  final String slug;

  /// Opened to write to the dealer (`?ecrire=1`, back from signing in on another path).
  final bool write;

  @override
  ConsumerState<DealerScreen> createState() => _DealerScreenState();
}

class _DealerScreenState extends ConsumerState<DealerScreen> {
  final _scroll = ScrollController();
  OfferType _offer = OfferType.SALE;

  @override
  void initState() {
    super.initState();
    _scroll.addListener(() {
      final query = _query;
      if (query != null && _scroll.position.extentAfter < 800) {
        ref.read(searchResultsProvider(query).notifier).loadMore();
      }
    });
  }

  @override
  void dispose() {
    _scroll.dispose();
    super.dispose();
  }

  SearchQuery? get _query {
    final sellerId = ref.read(dealerPageProvider(widget.slug)).value?.sellerId;
    return sellerId == null ? null : SearchQuery(sellerId: sellerId, offer: _offer);
  }

  @override
  Widget build(BuildContext context) {
    final page = ref.watch(dealerPageProvider(widget.slug));
    return Scaffold(
      appBar: AppBar(title: Text(page.value?.name ?? 'Professionnel'), centerTitle: false),
      body: page.when(
        skipLoadingOnRefresh: false,
        loading: () => const LoadingView(),
        error: (error, _) => switch (ApiFailure.from(error)) {
          ServerFailure(status: 404) => const ComingSoonView(
            icon: LucideIcons.store,
            title: 'Cette page n’existe pas ou plus.',
            body: 'Le professionnel l’a peut-être retirée.',
          ),
          _ => FailureView(error: error, onRetry: () => ref.invalidate(dealerPageProvider(widget.slug))),
        },
        data: _page,
      ),
    );
  }

  Widget _page(DealerPage dealer) {
    final theme = Theme.of(context);
    final open = ref.read(linkOpenerProvider);
    final phone = phoneUri(dealer.phone);
    final website = websiteUri(dealer.websiteUrl);
    final place = dealerPlace(dealer.city, dealer.departement);
    final since = memberSince(dealer.memberSince);
    final query = dealer.sellerId == null ? null : SearchQuery(sellerId: dealer.sellerId, offer: _offer);
    final results = query == null ? null : ref.watch(searchResultsProvider(query));

    return CustomScrollView(
      controller: _scroll,
      slivers: [
        SliverToBoxAdapter(
          child: PageSection(
            child: Row(
              children: [
                DealerLogo(url: dealer.logoUrl, size: 64),
                const SizedBox(width: AppSpace.s3),
                Expanded(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Semantics(
                        container: true,
                        header: true,
                        child: Text(dealer.name ?? '', style: theme.textTheme.titleLarge),
                      ),
                      // Own nodes, so TalkBack reads them after the name, not before it.
                      if (place != null)
                        Semantics(container: true, child: Text(place, style: theme.textTheme.bodySmall)),
                      Semantics(
                        container: true,
                        child: Text(
                          pageListings(dealer),
                          style: theme.textTheme.bodySmall?.copyWith(color: AppColors.ink),
                        ),
                      ),
                      if (since != null)
                        Semantics(container: true, child: Text(since, style: theme.textTheme.labelSmall)),
                    ],
                  ),
                ),
              ],
            ),
          ),
        ),
        // "Contacter ce professionnel", before the details, as on the web.
        SliverToBoxAdapter(
          child: Semantics(
            container: true,
            label: 'Contacter ce professionnel',
            explicitChildNodes: true,
            child: Container(
              color: AppColors.surface,
              margin: const EdgeInsets.only(top: AppSpace.s2),
              padding: const EdgeInsets.all(AppSpace.gutter),
              child: ContactSeller(target: ContactTarget.dealer(dealer), openOnArrival: widget.write),
            ),
          ),
        ),
        if (phone != null || website != null || (dealer.openingHours ?? '').trim().isNotEmpty)
          SliverToBoxAdapter(
            child: PageSection(
              child: Semantics(
                container: true,
                label: 'Contacter ce professionnel',
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.stretch,
                  children: [
                    if (phone != null)
                      _Contact(
                        icon: LucideIcons.phone,
                        label: 'Téléphone',
                        value: dealer.phone!,
                        onTap: () => open(phone),
                      ),
                    if (website != null)
                      _Contact(
                        icon: LucideIcons.globe,
                        label: 'Site web',
                        value: websiteLabel(dealer.websiteUrl!),
                        onTap: () => open(website),
                      ),
                    if ((dealer.openingHours ?? '').trim().isNotEmpty)
                      _Contact(icon: LucideIcons.clock, label: 'Horaires', value: dealer.openingHours!.trim()),
                  ],
                ),
              ),
            ),
          ),
        if ((dealer.description ?? '').trim().isNotEmpty)
          SliverToBoxAdapter(
            child: PageSection(child: Text(dealer.description!.trim(), style: theme.textTheme.bodyMedium)),
          ),
        SliverToBoxAdapter(
          child: Padding(
            padding: const EdgeInsets.fromLTRB(AppSpace.gutter, AppSpace.s4, AppSpace.gutter, AppSpace.s2),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.stretch,
              children: [
                Semantics(
                  container: true,
                  header: true,
                  child: Text('Annonces de ce professionnel', style: theme.textTheme.titleMedium),
                ),
                if ((dealer.leasingListings ?? 0) > 0) ...[
                  const SizedBox(height: AppSpace.s2),
                  SegmentedButton<OfferType>(
                    showSelectedIcon: false,
                    segments: const [
                      ButtonSegment(value: OfferType.SALE, label: Text('Acheter')),
                      ButtonSegment(value: OfferType.LEASING, label: Text('Louer')),
                    ],
                    selected: {_offer},
                    onSelectionChanged: (selection) => setState(() => _offer = selection.first),
                  ),
                ],
              ],
            ),
          ),
        ),
        if (results != null) ..._listings(results, query!),
      ],
    );
  }

  List<Widget> _listings(ResultsState results, SearchQuery query) => switch (results.status) {
    ResultsStatus.loading => const [SliverToBoxAdapter(child: LoadingView())],
    ResultsStatus.error => [
      SliverToBoxAdapter(
        child: FailureView(
          error: results.failure!,
          onRetry: () => ref.read(searchResultsProvider(query).notifier).retry(),
        ),
      ),
    ],
    ResultsStatus.ready when results.items.isEmpty => const [
      SliverToBoxAdapter(
        child: Padding(
          padding: EdgeInsets.all(AppSpace.s5),
          child: Text('Aucune annonce en ligne pour le moment.', textAlign: TextAlign.center),
        ),
      ),
    ],
    ResultsStatus.ready => [
      SliverPadding(
        padding: const EdgeInsets.symmetric(horizontal: AppSpace.gutter),
        sliver: SliverList.separated(
          itemCount: results.items.length,
          separatorBuilder: (_, _) => const SizedBox(height: AppSpace.s3),
          itemBuilder: (_, index) => ListingCard(listing: results.items[index]),
        ),
      ),
      SliverToBoxAdapter(
        child: Padding(
          padding: const EdgeInsets.all(AppSpace.s4),
          child: Center(
            child: results.loadingMore
                ? const LoadingView(label: 'Chargement…')
                : results.hasMore
                ? OutlinedButton(
                    onPressed: () => ref.read(searchResultsProvider(query).notifier).loadMore(),
                    child: const Text('Voir plus d’annonces'),
                  )
                : const SizedBox.shrink(),
          ),
        ),
      ),
    ],
  };
}

class _Contact extends StatelessWidget {
  const _Contact({required this.icon, required this.label, required this.value, this.onTap});

  final IconData icon;
  final String label;
  final String value;
  final VoidCallback? onTap;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return InkWell(
      onTap: onTap,
      child: Padding(
        padding: const EdgeInsets.symmetric(vertical: AppSpace.s2),
        child: Row(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Icon(icon, size: 18, color: AppColors.inkSoft),
            const SizedBox(width: AppSpace.s3),
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(label, style: theme.textTheme.labelMedium),
                  Text(
                    value,
                    style: theme.textTheme.bodyMedium?.copyWith(
                      color: onTap == null ? AppColors.ink : AppColors.accent,
                      fontWeight: AppType.semibold,
                    ),
                  ),
                ],
              ),
            ),
          ],
        ),
      ),
    );
  }
}
