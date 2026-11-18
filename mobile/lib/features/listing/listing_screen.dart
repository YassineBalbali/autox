import 'package:autox_api/autox_api.dart' show Feature, PublicListingDetail, PublicListingSummary;
import 'package:flutter/services.dart' show SystemUiOverlayStyle;
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:lucide_icons_flutter/lucide_icons.dart';
import 'package:material_ui/material_ui.dart';

import '../../core/api/api_failure.dart';
import '../../core/api/api_providers.dart';
import '../../core/format/numbers.dart';
import '../../core/links.dart';
import '../../core/theme/tokens.dart';
import '../../core/widgets/states.dart';
import '../../core/widgets/surfaces.dart';
import '../favourites/favourite_button.dart';
import '../favourites/favourites.dart';
import '../messages/contact_seller.dart';
import '../search/search_locations.dart';
import 'listing_locations.dart';
import 'listing_providers.dart';
import 'listing_text.dart';
import 'report_listing.dart';
import 'widgets/financing_simulator.dart';
import 'widgets/listing_card.dart';
import 'widgets/listing_parts.dart';
import 'widgets/listing_tags.dart';
import 'widgets/photo_gallery.dart';

/// A listing's page, `/annonces/:id` — the web's `listing-page.html` in the phone's order:
/// photos, the summary (title, price or leasing offer, market verdict, damage) with the way to
/// write to the seller, key facts, Crit'Air, description, specification, equipment, financing,
/// the seller, similar listings.
class ListingScreen extends ConsumerWidget {
  const ListingScreen({super.key, required this.id, this.write = false, this.report = false});

  final String id;

  /// Opened to write to the seller (`?ecrire=1`), or to report the listing (`?signaler=1`).
  final bool write;
  final bool report;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final listing = ref.watch(listingDetailProvider(id));
    // The page has its photos for a header; until it has them, a plain bar.
    Widget plain(Widget body) => Scaffold(
      appBar: AppBar(title: const Text('Annonce'), centerTitle: false),
      body: body,
    );
    return listing.when(
      skipLoadingOnRefresh: false,
      loading: () => plain(const LoadingView(label: 'Chargement de l’annonce')),
      error: (error, _) => plain(switch (ApiFailure.from(error)) {
        ServerFailure(status: 404) => const _Gone(),
        _ => FailureView(error: error, onRetry: () => ref.invalidate(listingDetailProvider(id))),
      }),
      data: (listing) => _Page(listing: listing, write: write, report: report),
    );
  }
}

class _Page extends ConsumerStatefulWidget {
  const _Page({required this.listing, this.write = false, this.report = false});

  final PublicListingDetail listing;
  final bool write;
  final bool report;

  /// The web's rule: a car for sale, not a leasing offer and not damaged, with a price.
  static bool financeable(PublicListingDetail listing) =>
      listing.leasing == null && listing.damage == null && (listing.priceAmount ?? 0) > 0;

  @override
  ConsumerState<_Page> createState() => _PageState();
}

class _PageState extends ConsumerState<_Page> {
  final _scroll = ScrollController();

  /// The photos scrolled away: the bar turns white, with its title and dark status bar icons.
  bool _collapsed = false;

  @override
  void initState() {
    super.initState();
    _scroll.addListener(() {
      final collapsed = _scroll.hasClients && _scroll.offset > _photoHeight - kToolbarHeight - AppSpace.s5;
      if (collapsed != _collapsed) {
        setState(() => _collapsed = collapsed);
      }
    });
  }

  @override
  void dispose() {
    _scroll.dispose();
    super.dispose();
  }

  double get _photoHeight => MediaQuery.sizeOf(context).width / AppSizes.galleryAspect;

  void _back() => context.canPop() ? context.pop() : context.go(resultsPath);

  Future<void> _share(PublicListingDetail listing) => ref
      .read(textSharerProvider)
      .call(
        '${listing.title ?? 'Annonce AutoX'}\n${ref.read(appConfigProvider).site(listingLocation(listing.id!))}',
        subject: listing.title,
      );

  @override
  Widget build(BuildContext context) {
    final listing = widget.listing;
    final theme = Theme.of(context);
    final features = ref.watch(featuresProvider).value ?? const <Feature>[];
    final equipment = groupFeatures(listing.featureCodes, features);
    final specification = specificationRows(listing);
    final currency = listing.currencyCode ?? 'EUR';
    final minorUnit = minorUnitOf(ref, listing.currencyCode);
    final dealerSlug = listing.seller?.dealerSlug;

    return Scaffold(
      body: RefreshIndicator(
        // Below the bar's buttons, not behind them.
        edgeOffset: MediaQuery.paddingOf(context).top + kToolbarHeight,
        onRefresh: () => ref.refresh(listingDetailProvider(listing.id ?? '').future),
        child: CustomScrollView(
          controller: _scroll,
          slivers: [
            SliverAppBar(
              pinned: true,
              expandedHeight: _photoHeight,
              backgroundColor: AppColors.surface,
              surfaceTintColor: Colors.transparent,
              systemOverlayStyle: _collapsed ? SystemUiOverlayStyle.dark : SystemUiOverlayStyle.light,
              automaticallyImplyLeading: false,
              leading: Center(
                child: IconButton(
                  style: overPhotoButtonStyle,
                  tooltip: 'Retour',
                  onPressed: _back,
                  icon: const Icon(LucideIcons.arrowLeft, size: AppSizes.icon),
                ),
              ),
              title: AnimatedOpacity(
                opacity: _collapsed ? 1 : 0,
                duration: const Duration(milliseconds: 150),
                child: const Text('Annonce'),
              ),
              centerTitle: false,
              actions: [
                if (listing.id != null) ...[
                  IconButton(
                    style: overPhotoButtonStyle,
                    tooltip: 'Partager',
                    onPressed: () => _share(listing),
                    icon: const Icon(LucideIcons.share2, size: AppSizes.icon),
                  ),
                  FavouriteButton(listing: summaryOfDetail(listing)),
                ],
                const SizedBox(width: AppSpace.s2),
              ],
              flexibleSpace: FlexibleSpaceBar(
                collapseMode: CollapseMode.pin,
                background: Stack(
                  fit: StackFit.expand,
                  children: [
                    PhotoGallery(images: galleryImages(listing.images), subject: listing.title ?? '', fill: true),
                    // The status bar and the buttons readable on any photo.
                    const Positioned(
                      left: 0,
                      right: 0,
                      top: 0,
                      height: AppSizes.touch * 3,
                      child: IgnorePointer(
                        child: DecoratedBox(
                          decoration: BoxDecoration(
                            gradient: LinearGradient(
                              begin: Alignment.topCenter,
                              end: Alignment.bottomCenter,
                              colors: [AppColors.photoShade, Color(0x00111111)],
                            ),
                          ),
                        ),
                      ),
                    ),
                  ],
                ),
              ),
            ),
            SliverPadding(
              padding: const EdgeInsets.only(top: AppSpace.s3),
              sliver: SliverList.list(
                children: [
                  PageSection(
                    child: _Summary(listing: listing, currency: currency, minorUnit: minorUnit, write: widget.write),
                  ),
                  if (KeyFacts.factsOf(listing).isNotEmpty) PageSection(child: KeyFacts(listing: listing)),
                  if (listing.critAir != null) PageSection(child: CritAirBadge(critAir: listing.critAir!)),
                  if ((listing.description ?? '').trim().isNotEmpty)
                    PageSection(
                      title: 'Description',
                      child: Text(listing.description!.trim(), style: theme.textTheme.bodyMedium),
                    ),
                  // Under the description, where a reader who doubts it is.
                  if (listing.id != null)
                    Padding(
                      padding: const EdgeInsets.fromLTRB(AppSpace.gutter, 0, AppSpace.gutter, AppSpace.s3),
                      child: ReportListing(listing: listing, openOnArrival: widget.report),
                    ),
                  if (specification.isNotEmpty)
                    PageSection(
                      title: 'Caractéristiques',
                      child: SpecTable(rows: specification),
                    ),
                  if (equipment.isNotEmpty)
                    PageSection(
                      title: 'Équipements',
                      child: EquipmentList(groups: equipment),
                    ),
                  if (_Page.financeable(listing))
                    PageSection(
                      title: 'Simuler un financement',
                      child: FinancingSimulator(price: listing.priceAmount!, currency: currency, minorUnit: minorUnit),
                    ),
                  PageSection(
                    title: 'Vendeur',
                    child: SellerCard(
                      listing: listing,
                      onOpenDealer: dealerSlug == null ? null : () => context.push(dealerLocation(dealerSlug)),
                    ),
                  ),
                  if (listing.id != null) _Similar(id: listing.id!),
                  SizedBox(height: AppSpace.s5 + MediaQuery.paddingOf(context).bottom),
                ],
              ),
            ),
          ],
        ),
      ),
    );
  }
}

/// The first card: what it is, what it costs and what the market says of it, then the way to
/// write to the seller — where the web's summary has it, right under the price.
class _Summary extends StatelessWidget {
  const _Summary({required this.listing, required this.currency, required this.minorUnit, required this.write});

  final PublicListingDetail listing;
  final String currency;
  final int minorUnit;
  final bool write;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final basis = marketBasis(listing, minorUnit: minorUnit);
    final published = formatDate(listing.publishedAt);
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        if (listing.soldNew ?? false) ...[const _Tag('Neuf'), const SizedBox(height: AppSpace.s2)],
        Semantics(
          container: true,
          header: true,
          child: Text(listing.title ?? '', style: theme.textTheme.headlineSmall),
        ),
        if (published != null) ...[
          const SizedBox(height: AppSpace.s1),
          Row(
            children: [
              const Icon(LucideIcons.clock, size: AppSizes.iconSmall, color: AppColors.inkFaint),
              const SizedBox(width: AppSpace.s1),
              Flexible(child: Text('Publiée le $published', style: theme.textTheme.bodySmall)),
            ],
          ),
        ],
        if (listing.damage != null) ...[const SizedBox(height: AppSpace.s3), DamageNotice(damage: listing.damage!)],
        const SizedBox(height: AppSpace.s3),
        if (listing.leasing != null)
          LeasingOffer(
            leasing: listing.leasing!,
            priceAmount: listing.priceAmount,
            currency: currency,
            minorUnit: minorUnit,
          )
        else if (listing.priceAmount != null) ...[
          Text(
            formatPrice(listing.priceAmount!, currency: currency, minorUnit: minorUnit),
            style: theme.textTheme.headlineMedium,
          ),
          if (listing.priceIndicator?.level != null) ...[
            const SizedBox(height: AppSpace.s2),
            PriceBadge(indicator: listing.priceIndicator!),
            if (basis != null) ...[const SizedBox(height: AppSpace.s1), Text(basis, style: theme.textTheme.bodySmall)],
          ],
          if ((listing.priceNegotiable ?? false) || (listing.vatDeductible ?? false)) ...[
            const SizedBox(height: AppSpace.s2),
            Wrap(
              spacing: AppSpace.s1,
              runSpacing: AppSpace.s1,
              children: [
                if (listing.priceNegotiable ?? false) const _Tag('Prix négociable', quiet: true),
                if (listing.vatDeductible ?? false) const _Tag('TVA récupérable', quiet: true),
              ],
            ),
          ],
        ],
        const SizedBox(height: AppSpace.s4),
        ContactSeller(target: ContactTarget.listing(listing), openOnArrival: write),
      ],
    );
  }
}

class _Tag extends StatelessWidget {
  const _Tag(this.text, {this.quiet = false});

  final String text;
  final bool quiet;

  @override
  Widget build(BuildContext context) => Container(
    padding: const EdgeInsets.symmetric(horizontal: AppSpace.s2, vertical: AppSpace.s0),
    decoration: BoxDecoration(
      color: quiet ? AppColors.surfaceSunk : AppColors.accentSoft,
      borderRadius: const BorderRadius.all(Radius.circular(AppRadius.pill)),
    ),
    child: Text(
      text,
      style: AppText.tag.copyWith(fontWeight: AppType.bold, color: quiet ? AppColors.inkSoft : AppColors.accentStrong),
    ),
  );
}

/// "Annonces similaires": nothing at all when there are none.
class _Similar extends ConsumerWidget {
  const _Similar({required this.id});

  final String id;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final similar = ref.watch(similarListingsProvider(id)).value ?? const <PublicListingSummary>[];
    if (similar.isEmpty) {
      return const SizedBox.shrink();
    }
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Padding(
          padding: const EdgeInsets.fromLTRB(AppSpace.gutter, AppSpace.s4, AppSpace.gutter, AppSpace.s3),
          child: Semantics(
            container: true,
            header: true,
            child: Text('Annonces similaires', style: Theme.of(context).textTheme.titleLarge),
          ),
        ),
        for (final listing in similar)
          Padding(
            padding: const EdgeInsets.fromLTRB(AppSpace.gutter, 0, AppSpace.gutter, AppSpace.s3),
            child: ListingCard(listing: listing),
          ),
      ],
    );
  }
}

/// 404: the listing was sold, withdrawn or expired.
class _Gone extends StatelessWidget {
  const _Gone();

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return Center(
      child: Padding(
        padding: const EdgeInsets.all(AppSpace.s5),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            const IconTile(icon: LucideIcons.searchX, size: AppSizes.touch + AppSpace.s4),
            const SizedBox(height: AppSpace.s4),
            Text('Cette annonce n’est plus disponible', style: theme.textTheme.titleLarge, textAlign: TextAlign.center),
            const SizedBox(height: AppSpace.s2),
            Text(
              'Elle a pu être vendue, retirée par le vendeur ou arriver à expiration. D’autres véhicules vous attendent.',
              style: theme.textTheme.bodyMedium?.copyWith(color: AppColors.inkSoft),
              textAlign: TextAlign.center,
            ),
            const SizedBox(height: AppSpace.s5),
            FilledButton(onPressed: () => context.go(resultsPath), child: const Text('Voir les annonces')),
          ],
        ),
      ),
    );
  }
}
