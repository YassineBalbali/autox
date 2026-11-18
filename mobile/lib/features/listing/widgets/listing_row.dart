import 'package:autox_api/autox_api.dart'
    show DamageView, LeasingView, PriceIndicator, PriceLevel, PublicListingSummary;
import 'package:cached_network_image/cached_network_image.dart';
import 'package:go_router/go_router.dart';
import 'package:lucide_icons_flutter/lucide_icons.dart';
import 'package:material_ui/material_ui.dart';

import '../../../core/format/listing_text.dart';
import '../../../core/format/numbers.dart';
import '../../../core/labels/labels.g.dart';
import '../../../core/theme/tokens.dart';
import '../listing_locations.dart';

/// One search result: the web's phone row (`shared/listing/listing-row.html`) — photo on the
/// left; then the damage notice, the price (or the monthly payment), the market verdict, the
/// make and model with the version, two short spec lines, the place and the seller.
///
/// The whole row opens the listing's page.
class ListingRow extends StatelessWidget {
  const ListingRow({super.key, required this.listing, this.minorUnit = 2});

  final PublicListingSummary listing;
  final int minorUnit;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final headline = headlineOfListing(listing);
    final place = placeOf(city: listing.city, postalCode: listing.postalCode, countryCode: listing.countryCode);
    final currency = listing.currencyCode ?? 'EUR';

    final id = listing.id;
    // One node for TalkBack: the row is one button, read whole — badges included.
    return MergeSemantics(
      child: Material(
        color: AppColors.surface,
        child: InkWell(
          onTap: id == null ? null : () => context.push(listingLocation(id)),
          child: Padding(
            padding: const EdgeInsets.symmetric(horizontal: AppSpace.gutter, vertical: AppSpace.s3),
            child: Row(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                _Thumb(listing: listing),
                const SizedBox(width: AppSpace.s3),
                Expanded(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      if (listing.damage != null) ...[
                        DamageTag(damage: listing.damage!),
                        const SizedBox(height: AppSpace.s1),
                      ],
                      if (listing.leasing != null)
                        LeasingPrice(leasing: listing.leasing!, currency: currency, minorUnit: minorUnit)
                      else if (listing.priceAmount != null)
                        Text(
                          formatPrice(listing.priceAmount!, currency: currency, minorUnit: minorUnit),
                          style: AppText.price,
                        ),
                      if (listing.priceIndicator?.level != null) ...[
                        const SizedBox(height: AppSpace.s1),
                        PriceBadge(indicator: listing.priceIndicator!),
                      ],
                      const SizedBox(height: AppSpace.s1),
                      Text.rich(
                        TextSpan(
                          text: headline.title,
                          children: [
                            if (headline.subtitle != null)
                              TextSpan(
                                text: '  ${headline.subtitle}',
                                style: theme.textTheme.bodySmall?.copyWith(fontWeight: AppType.regular),
                              ),
                          ],
                        ),
                        style: theme.textTheme.titleSmall?.copyWith(fontWeight: AppType.bold),
                        maxLines: 2,
                        overflow: TextOverflow.ellipsis,
                      ),
                      const SizedBox(height: AppSpace.s0),
                      _SpecLine(items: _firstLine(listing)),
                      _SpecLine(items: _secondLine(listing)),
                      const SizedBox(height: AppSpace.s1),
                      Row(
                        children: [
                          if (place != null) ...[
                            const Icon(LucideIcons.mapPin, size: 14, color: AppColors.inkFaint),
                            const SizedBox(width: AppSpace.s0),
                            Flexible(
                              child: Text(place, style: theme.textTheme.bodySmall, overflow: TextOverflow.ellipsis),
                            ),
                            const SizedBox(width: AppSpace.s2),
                          ],
                          SellerBadge(role: listing.seller?.role),
                        ],
                      ),
                    ],
                  ),
                ),
              ],
            ),
          ),
        ),
      ),
    );
  }

  /// "2019 · 48 000 km", or "2025 · Neuf" for a new car.
  static List<String> _firstLine(PublicListingSummary listing) => [
    if (listing.firstRegistrationYear != null) '${listing.firstRegistrationYear}',
    if (listing.soldNew ?? false) 'Neuf' else if (listing.mileageKm != null) formatKm(listing.mileageKm!),
  ];

  /// "Diesel · Automatique".
  static List<String> _secondLine(PublicListingSummary listing) => [
    labelOf(fuelTypeLabels, listing.fuelType),
    labelOf(gearboxLabels, listing.gearbox),
  ].where((item) => item.isNotEmpty).toList();
}

class _SpecLine extends StatelessWidget {
  const _SpecLine({required this.items});

  final List<String> items;

  @override
  Widget build(BuildContext context) {
    if (items.isEmpty) {
      return const SizedBox.shrink();
    }
    return Text(items.join(' · '), style: Theme.of(context).textTheme.bodySmall, maxLines: 1);
  }
}

/// The photo, 4:3, with the photo count; a placeholder when there is none.
class _Thumb extends StatelessWidget {
  const _Thumb({required this.listing});

  final PublicListingSummary listing;

  static const double width = 128;

  @override
  Widget build(BuildContext context) {
    final cover = coverOf(listing);
    final photos = listing.photoCount ?? 0;
    final placeholder = Container(
      color: AppColors.surfaceSunk,
      alignment: Alignment.center,
      child: const Icon(LucideIcons.car, color: AppColors.inkFaint, size: 32),
    );
    return ClipRRect(
      borderRadius: BorderRadius.circular(AppRadius.md),
      child: SizedBox(
        width: width,
        height: width * 3 / 4,
        child: Stack(
          fit: StackFit.expand,
          children: [
            if (cover == null)
              placeholder
            else
              CachedNetworkImage(
                imageUrl: cover.url!,
                // Without a CDN the URLs are pre-signed and change every few hours; the path
                // without its query string is the same image, so it is the cache key.
                cacheKey: imageCacheKey(cover.url!),
                fit: BoxFit.cover,
                memCacheWidth: (width * MediaQuery.devicePixelRatioOf(context)).round(),
                placeholder: (_, _) => placeholder,
                errorWidget: (_, _, _) => placeholder,
              ),
            // One photo with its cover says nothing; one photo and no cover says it exists.
            if (photos > 1 || (photos > 0 && cover == null))
              Positioned(
                left: AppSpace.s1,
                bottom: AppSpace.s1,
                child: Container(
                  padding: const EdgeInsets.symmetric(horizontal: AppSpace.s1, vertical: 1),
                  decoration: BoxDecoration(color: AppColors.scrim, borderRadius: BorderRadius.circular(AppRadius.sm)),
                  child: Row(
                    mainAxisSize: MainAxisSize.min,
                    children: [
                      const Icon(LucideIcons.camera, size: 12, color: AppColors.surface),
                      const SizedBox(width: 3),
                      Text(
                        '$photos',
                        style: const TextStyle(
                          fontSize: AppType.txs,
                          color: AppColors.surface,
                          fontWeight: AppType.semibold,
                        ),
                      ),
                    ],
                  ),
                ),
              ),
          ],
        ),
      ),
    );
  }
}

/// The image's address without its query string: stable across the re-signing of its URL.
String imageCacheKey(String url) {
  final uri = Uri.tryParse(url);
  return uri == null ? url : uri.replace(query: '').toString().replaceFirst(RegExp(r'\?$'), '');
}

/// "à partir de 289 €/mois", with the terms under it.
class LeasingPrice extends StatelessWidget {
  const LeasingPrice({super.key, required this.leasing, this.currency = 'EUR', this.minorUnit = 2});

  final LeasingView leasing;
  final String currency;
  final int minorUnit;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Text('à partir de', style: theme.textTheme.bodySmall?.copyWith(fontWeight: AppType.semibold)),
        Text.rich(
          TextSpan(
            text: formatPrice(leasing.monthlyPayment ?? 0, currency: currency, minorUnit: minorUnit),
            style: AppText.price,
            children: [
              TextSpan(
                text: '/mois',
                style: AppText.tag.copyWith(color: AppColors.ink),
              ),
            ],
          ),
        ),
        Text(leasingTerms(leasing), style: theme.textTheme.labelMedium),
      ],
    );
  }
}

/// The market verdict: three segments, the listing's lit, and the word.
class PriceBadge extends StatelessWidget {
  const PriceBadge({super.key, required this.indicator});

  final PriceIndicator indicator;

  static const _levels = [PriceLevel.GOOD_DEAL, PriceLevel.MARKET, PriceLevel.ABOVE_MARKET];

  static Color colorOf(PriceLevel level) => switch (level) {
    PriceLevel.GOOD_DEAL => AppColors.success,
    PriceLevel.ABOVE_MARKET => AppColors.warn,
    _ => AppColors.accent,
  };

  @override
  Widget build(BuildContext context) {
    final level = indicator.level!;
    if (level == PriceLevel.unknownDefaultOpenApi) {
      return const SizedBox.shrink();
    }
    final color = colorOf(level);
    return Semantics(
      container: true,
      label: priceLevelLabel(level),
      excludeSemantics: true,
      child: Row(
        mainAxisSize: MainAxisSize.min,
        children: [
          for (final segment in _levels)
            Container(
              width: AppSizes.verdictSegmentWidth,
              height: AppSizes.verdictSegmentHeight,
              margin: const EdgeInsets.only(right: AppSpace.s0),
              decoration: BoxDecoration(
                color: segment == level ? color : AppColors.line,
                borderRadius: BorderRadius.circular(AppRadius.pill),
              ),
            ),
          const SizedBox(width: AppSpace.s1),
          Flexible(
            child: Text(
              priceLevelLabel(level),
              style: AppText.tag.copyWith(fontWeight: AppType.bold, color: color),
            ),
          ),
        ],
      ),
    );
  }
}

/// "Pro" or "Particulier"; nothing for any other role.
class SellerBadge extends StatelessWidget {
  const SellerBadge({super.key, required this.role});

  final String? role;

  @override
  Widget build(BuildContext context) {
    final (icon, label) = switch (role) {
      'PROFESSIONNEL' => (LucideIcons.store, 'Pro'),
      'PARTICULIER' => (LucideIcons.user, 'Particulier'),
      _ => (null, null),
    };
    if (label == null) {
      return const SizedBox.shrink();
    }
    final pro = role == 'PROFESSIONNEL';
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: AppSpace.s2, vertical: AppSpace.s0),
      decoration: BoxDecoration(
        color: pro ? AppColors.accentSoft : AppColors.surfaceSunk,
        borderRadius: BorderRadius.circular(AppRadius.pill),
      ),
      child: Row(
        mainAxisSize: MainAxisSize.min,
        children: [
          Icon(icon, size: AppSizes.iconTiny, color: pro ? AppColors.accentStrong : AppColors.inkSoft),
          const SizedBox(width: AppSpace.s1),
          Text(label, style: AppText.tag.copyWith(color: pro ? AppColors.accentStrong : AppColors.inkSoft)),
        ],
      ),
    );
  }
}

/// "Véhicule endommagé" and what was declared: "Carrosserie, Grêle · Roulant".
class DamageTag extends StatelessWidget {
  const DamageTag({super.key, required this.damage});

  final DamageView damage;

  /// The facts after the tag, as the web's compact notice writes them.
  static String factsOf(DamageView damage) {
    final types = (damage.types ?? const []).map(damageTypeLabel).where((label) => label.isNotEmpty).join(', ');
    final state = switch (damage.running) {
      true => 'Roulant',
      false => 'Non roulant',
      null => null,
    };
    return [types, ?state].where((part) => part.isNotEmpty).join(' · ');
  }

  @override
  Widget build(BuildContext context) {
    final facts = factsOf(damage);
    return Wrap(
      spacing: AppSpace.s1,
      crossAxisAlignment: WrapCrossAlignment.center,
      children: [
        Container(
          padding: const EdgeInsets.symmetric(horizontal: AppSpace.s2, vertical: AppSpace.s0),
          decoration: BoxDecoration(color: AppColors.dangerSoft, borderRadius: BorderRadius.circular(AppRadius.pill)),
          child: Text(
            'Véhicule endommagé',
            style: AppText.tag.copyWith(fontWeight: AppType.bold, color: AppColors.danger),
          ),
        ),
        if (facts.isNotEmpty) Text(facts, style: Theme.of(context).textTheme.bodySmall),
      ],
    );
  }
}
