import 'package:autox_api/autox_api.dart' show Feature, PublicListingDetail, PublicListingSummary;
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:lucide_icons_flutter/lucide_icons.dart';
import 'package:material_ui/material_ui.dart';

import '../../core/api/api_failure.dart';
import '../../core/format/numbers.dart';
import '../../core/theme/tokens.dart';
import '../../core/widgets/states.dart';
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
/// photos, the summary (title, price or leasing offer, market verdict, damage), key facts,
/// Crit'Air, description, specification, equipment, financing, the seller, similar listings.
class ListingScreen extends ConsumerWidget {
  const ListingScreen({super.key, required this.id, this.write = false, this.report = false});

  final String id;

  /// Opened to write to the seller (`?ecrire=1`), or to report the listing (`?signaler=1`).
  final bool write;
  final bool report;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final listing = ref.watch(listingDetailProvider(id));
    return Scaffold(
      appBar: AppBar(title: const Text('Annonce'), centerTitle: false),
      body: listing.when(
        skipLoadingOnRefresh: false,
        loading: () => const LoadingView(label: 'Chargement de l’annonce'),
        error: (error, _) => switch (ApiFailure.from(error)) {
          ServerFailure(status: 404) => const _Gone(),
          _ => FailureView(error: error, onRetry: () => ref.invalidate(listingDetailProvider(id))),
        },
        data: (listing) => _Page(listing: listing, write: write, report: report),
      ),
    );
  }
}

class _Page extends ConsumerWidget {
  const _Page({required this.listing, this.write = false, this.report = false});

  final PublicListingDetail listing;
  final bool write;
  final bool report;

  /// The web's rule: a car for sale, not a leasing offer and not damaged, with a price.
  static bool financeable(PublicListingDetail listing) =>
      listing.leasing == null && listing.damage == null && (listing.priceAmount ?? 0) > 0;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final theme = Theme.of(context);
    final minorUnit = minorUnitOf(ref, listing.currencyCode);
    final currency = listing.currencyCode ?? 'EUR';
    final features = ref.watch(featuresProvider).value ?? const <Feature>[];
    final equipment = groupFeatures(listing.featureCodes, features);
    final specification = specificationRows(listing);
    final basis = marketBasis(listing, minorUnit: minorUnit);
    final published = formatDate(listing.publishedAt);
    final dealerSlug = listing.seller?.dealerSlug;

    return RefreshIndicator(
      onRefresh: () => ref.refresh(listingDetailProvider(listing.id ?? '').future),
      child: ListView(
        children: [
          PhotoGallery(images: galleryImages(listing.images), subject: listing.title ?? ''),
          PageSection(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                if (listing.soldNew ?? false) ...[const _Tag('Neuf'), const SizedBox(height: AppSpace.s1)],
                Semantics(
                  container: true,
                  header: true,
                  child: Text(listing.title ?? '', style: theme.textTheme.headlineSmall),
                ),
                if (published != null) ...[
                  const SizedBox(height: AppSpace.s1),
                  Row(
                    children: [
                      const Icon(LucideIcons.clock, size: 14, color: AppColors.inkFaint),
                      const SizedBox(width: AppSpace.s1),
                      Text('Publiée le $published', style: theme.textTheme.bodySmall),
                    ],
                  ),
                ],
                if (listing.damage != null) ...[
                  const SizedBox(height: AppSpace.s3),
                  DamageNotice(damage: listing.damage!),
                ],
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
                    style: theme.textTheme.headlineMedium?.copyWith(color: AppColors.accent),
                  ),
                  if (listing.priceIndicator?.level != null) ...[
                    const SizedBox(height: AppSpace.s2),
                    PriceBadge(indicator: listing.priceIndicator!),
                    if (basis != null) ...[
                      const SizedBox(height: AppSpace.s1),
                      Text(basis, style: theme.textTheme.bodySmall),
                    ],
                  ],
                  if ((listing.priceNegotiable ?? false) || (listing.vatDeductible ?? false)) ...[
                    const SizedBox(height: AppSpace.s2),
                    Wrap(
                      spacing: AppSpace.s1,
                      children: [
                        if (listing.priceNegotiable ?? false) const _Tag('Prix négociable', quiet: true),
                        if (listing.vatDeductible ?? false) const _Tag('TVA récupérable', quiet: true),
                      ],
                    ),
                  ],
                ],
              ],
            ),
          ),
          // Where the web's summary has it: right under the price.
          PageSection(
            child: ContactSeller(target: ContactTarget.listing(listing), openOnArrival: write),
          ),
          PageSection(child: KeyFacts(listing: listing)),
          if (listing.critAir != null) PageSection(child: CritAirBadge(critAir: listing.critAir!)),
          if ((listing.description ?? '').trim().isNotEmpty)
            PageSection(
              title: 'Description',
              child: Text(listing.description!.trim(), style: theme.textTheme.bodyMedium),
            ),
          // Under the description, where a reader who doubts it is.
          if (listing.id != null)
            PageSection(
              child: ReportListing(listing: listing, openOnArrival: report),
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
          if (financeable(listing))
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
          const SizedBox(height: AppSpace.s5),
        ],
      ),
    );
  }
}

class _Tag extends StatelessWidget {
  const _Tag(this.text, {this.quiet = false});

  final String text;
  final bool quiet;

  @override
  Widget build(BuildContext context) => Container(
    padding: const EdgeInsets.symmetric(horizontal: AppSpace.s2, vertical: 2),
    decoration: BoxDecoration(
      color: quiet ? AppColors.surfaceSunk : AppColors.accentSoft,
      borderRadius: BorderRadius.circular(AppRadius.sm),
    ),
    child: Text(
      text,
      style: TextStyle(
        fontSize: AppType.txs,
        fontWeight: AppType.bold,
        color: quiet ? AppColors.inkSoft : AppColors.accentStrong,
      ),
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
          padding: const EdgeInsets.fromLTRB(AppSpace.gutter, AppSpace.s5, AppSpace.gutter, AppSpace.s2),
          child: Semantics(
            container: true,
            header: true,
            child: Text('Annonces similaires', style: Theme.of(context).textTheme.titleMedium),
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
            const Icon(LucideIcons.searchX, size: 36, color: AppColors.inkFaint),
            const SizedBox(height: AppSpace.s3),
            Text(
              'Cette annonce n’est plus disponible',
              style: theme.textTheme.titleMedium,
              textAlign: TextAlign.center,
            ),
            const SizedBox(height: AppSpace.s1),
            Text(
              'Elle a pu être vendue, retirée par le vendeur ou arriver à expiration. D’autres véhicules vous attendent.',
              style: theme.textTheme.bodySmall,
              textAlign: TextAlign.center,
            ),
            const SizedBox(height: AppSpace.s4),
            OutlinedButton(onPressed: () => context.go(resultsPath), child: const Text('Voir les annonces')),
          ],
        ),
      ),
    );
  }
}
