import 'package:autox_api/autox_api.dart' show PublicListingSummary;
import 'package:cached_network_image/cached_network_image.dart';
import 'package:go_router/go_router.dart';
import 'package:lucide_icons_flutter/lucide_icons.dart';
import 'package:material_ui/material_ui.dart';

import '../../../core/format/listing_text.dart';
import '../../../core/format/numbers.dart';
import '../../../core/labels/labels.g.dart';
import '../../../core/theme/tokens.dart';
import '../../../core/widgets/surfaces.dart';
import '../../favourites/favourite_button.dart';
import '../listing_locations.dart';
import 'listing_tags.dart';

/// One search result as a card: the photo, large and rounded; then the damage notice, the price
/// (or the monthly payment), the market verdict, the make and model with the version, the key
/// facts as small chips, the place and the seller.
///
/// The whole card opens the listing's page, and TalkBack reads it as one button. The heart on the
/// photo is a button of its own, after it.
class ListingCard extends StatelessWidget {
  const ListingCard({super.key, required this.listing, this.minorUnit = 2});

  final PublicListingSummary listing;
  final int minorUnit;

  /// Where the heart sits: its disc [AppSpace.s2] from the photo's corner, the photo
  /// [AppSpace.s3] inside the card, the disc centred in its 48 dp target.
  static const double _heartInset = AppSpace.s3 + AppSpace.s2 - (AppSizes.touch - AppSizes.overPhotoButton) / 2;

  @override
  Widget build(BuildContext context) => Stack(
    children: [
      _card(context),
      if (listing.id != null)
        Positioned(
          top: _heartInset,
          right: _heartInset,
          child: FavouriteButton(listing: listing),
        ),
    ],
  );

  Widget _card(BuildContext context) {
    final theme = Theme.of(context);
    final headline = headlineOfListing(listing);
    final place = placeOf(city: listing.city, postalCode: listing.postalCode, countryCode: listing.countryCode);
    final currency = listing.currencyCode ?? 'EUR';
    final id = listing.id;

    return MergeSemantics(
      child: AppCard(
        onTap: id == null ? null : () => context.push(listingLocation(id)),
        padding: const EdgeInsets.all(AppSpace.s3),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            _Photo(listing: listing),
            Padding(
              padding: const EdgeInsets.fromLTRB(AppSpace.s1, AppSpace.s3, AppSpace.s1, AppSpace.s1),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  if (listing.damage != null) ...[
                    DamageTag(damage: listing.damage!),
                    const SizedBox(height: AppSpace.s2),
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
                  const SizedBox(height: AppSpace.s2),
                  Text(
                    headline.title,
                    style: theme.textTheme.titleMedium,
                    maxLines: 1,
                    overflow: TextOverflow.ellipsis,
                  ),
                  if (headline.subtitle != null)
                    Text(
                      headline.subtitle!,
                      style: theme.textTheme.bodySmall,
                      maxLines: 1,
                      overflow: TextOverflow.ellipsis,
                    ),
                  if (specsOf(listing).isNotEmpty) ...[
                    const SizedBox(height: AppSpace.s2),
                    Wrap(
                      spacing: AppSpace.s1,
                      runSpacing: AppSpace.s1,
                      children: [for (final spec in specsOf(listing)) SpecChip(spec)],
                    ),
                  ],
                  const SizedBox(height: AppSpace.s3),
                  Row(
                    children: [
                      if (place != null) ...[
                        const Icon(LucideIcons.mapPin, size: AppSizes.iconSmall, color: AppColors.inkSoft),
                        const SizedBox(width: AppSpace.s1),
                        Expanded(
                          child: Text(place, style: theme.textTheme.bodySmall, overflow: TextOverflow.ellipsis),
                        ),
                      ] else
                        const Spacer(),
                      const SizedBox(width: AppSpace.s2),
                      SellerBadge(role: listing.seller?.role),
                    ],
                  ),
                ],
              ),
            ),
          ],
        ),
      ),
    );
  }

  /// The facts shown as chips: year, mileage ("Neuf" for a new car), fuel, gearbox.
  static List<String> specsOf(PublicListingSummary listing) => [
    if (listing.firstRegistrationYear != null) '${listing.firstRegistrationYear}',
    if (listing.soldNew ?? false) 'Neuf' else if (listing.mileageKm != null) formatKm(listing.mileageKm!),
    labelOf(fuelTypeLabels, listing.fuelType),
    labelOf(gearboxLabels, listing.gearbox),
  ].where((item) => item.isNotEmpty).toList();
}

/// The photo, wide, with the photo count; a placeholder when there is none.
class _Photo extends StatelessWidget {
  const _Photo({required this.listing});

  final PublicListingSummary listing;

  @override
  Widget build(BuildContext context) {
    final cover = coverOf(listing);
    final photos = listing.photoCount ?? 0;
    final placeholder = Container(
      color: AppColors.surfaceSunk,
      alignment: Alignment.center,
      child: const Icon(LucideIcons.car, color: AppColors.inkFaint, size: AppSizes.iconTile),
    );
    return ClipRRect(
      borderRadius: BorderRadius.circular(AppRadius.md),
      child: AspectRatio(
        aspectRatio: AppSizes.cardPhotoAspect,
        child: LayoutBuilder(
          builder: (context, constraints) => Stack(
            fit: StackFit.expand,
            children: [
              if (cover == null)
                placeholder
              else
                CachedNetworkImage(
                  imageUrl: cover.url!,
                  cacheKey: imageCacheKey(cover.url!),
                  fit: BoxFit.cover,
                  memCacheWidth: (constraints.maxWidth * MediaQuery.devicePixelRatioOf(context)).round(),
                  placeholder: (_, _) => placeholder,
                  errorWidget: (_, _, _) => placeholder,
                ),
              // One photo with its cover says nothing; one photo and no cover says it exists.
              if (photos > 1 || (photos > 0 && cover == null))
                Positioned(
                  left: AppSpace.s2,
                  bottom: AppSpace.s2,
                  child: Container(
                    padding: const EdgeInsets.symmetric(horizontal: AppSpace.s2, vertical: AppSpace.s0),
                    decoration: const BoxDecoration(
                      color: AppColors.scrim,
                      borderRadius: BorderRadius.all(Radius.circular(AppRadius.pill)),
                    ),
                    child: Row(
                      mainAxisSize: MainAxisSize.min,
                      children: [
                        const Icon(LucideIcons.camera, size: AppSizes.iconTiny, color: AppColors.surface),
                        const SizedBox(width: AppSpace.s1),
                        Text('$photos', style: AppText.tag.copyWith(color: AppColors.surface)),
                      ],
                    ),
                  ),
                ),
            ],
          ),
        ),
      ),
    );
  }
}
