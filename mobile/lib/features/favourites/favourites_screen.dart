import 'package:autox_api/autox_api.dart' show PublicListingSummary;
import 'package:cached_network_image/cached_network_image.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:lucide_icons_flutter/lucide_icons.dart';
import 'package:material_ui/material_ui.dart';

import '../../core/api/api_failure.dart';
import '../../core/format/listing_text.dart';
import '../../core/theme/tokens.dart';
import '../../core/widgets/surfaces.dart';
import '../listing/listing_providers.dart';
import '../listing/widgets/listing_card.dart';
import '../listing/widgets/listing_tags.dart' show imageCacheKey;
import 'favourites.dart';

/// The Favoris tab: the listings saved with the heart on this phone, newest saved first, as the
/// result cards.
///
/// Each card shows the listing as it was saved at once, then as it is now once the API has
/// answered — a card at a time, as it comes on screen. A listing that went offline (404) is shown
/// washed out, "Annonce retirée", with a button to remove it; any other failure keeps the saved
/// version: the network will be back, the listing may well still be online.
class FavouritesScreen extends ConsumerWidget {
  const FavouritesScreen({super.key});

  static String countLabel(int count) =>
      count == 1 ? '1 annonce gardée sur ce téléphone' : '$count annonces gardées sur ce téléphone';

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final favourites = ref.watch(favouritesProvider);
    return Scaffold(
      body: SafeArea(
        bottom: false,
        child: CustomScrollView(
          slivers: [
            SliverPadding(
              padding: const EdgeInsets.fromLTRB(AppSpace.gutter, AppSpace.s5, AppSpace.gutter, AppSpace.s4),
              sliver: SliverToBoxAdapter(
                child: ScreenTitle('Favoris', subtitle: favourites.isEmpty ? null : countLabel(favourites.length)),
              ),
            ),
            if (favourites.isEmpty)
              const SliverFillRemaining(hasScrollBody: false, child: _Empty())
            else
              SliverPadding(
                padding: const EdgeInsets.fromLTRB(AppSpace.gutter, 0, AppSpace.gutter, AppSpace.s5),
                sliver: SliverList.separated(
                  itemCount: favourites.length,
                  separatorBuilder: (_, _) => const SizedBox(height: AppSpace.s3),
                  itemBuilder: (_, index) => _Entry(key: ValueKey(favourites[index].id), favourite: favourites[index]),
                ),
              ),
          ],
        ),
      ),
    );
  }
}

/// One saved listing: as saved, as it is now, or gone.
class _Entry extends ConsumerWidget {
  const _Entry({super.key, required this.favourite});

  final Favourite favourite;

  static bool _gone(Object error) => switch (ApiFailure.from(error)) {
    ServerFailure(status: 404) => true,
    _ => false,
  };

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final now = ref.watch(listingDetailProvider(favourite.id));
    final minorUnit = minorUnitOf(ref, favourite.listing.currencyCode);
    return switch (now) {
      AsyncData(:final value) => ListingCard(listing: summaryOfDetail(value), minorUnit: minorUnit),
      AsyncError(:final error) when _gone(error) => _Retired(listing: favourite.listing),
      _ => ListingCard(listing: favourite.listing, minorUnit: minorUnit),
    };
  }
}

/// A saved listing that is no longer online: what it was, washed out, and the way to let it go.
class _Retired extends ConsumerWidget {
  const _Retired({required this.listing});

  final PublicListingSummary listing;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final theme = Theme.of(context);
    final headline = headlineOfListing(listing);
    final cover = coverOf(listing);
    return AppCard(
      padding: const EdgeInsets.all(AppSpace.s3),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          ExcludeSemantics(
            child: ClipRRect(
              borderRadius: BorderRadius.circular(AppRadius.sm),
              child: SizedBox(
                width: AppSizes.thumbWidth,
                height: AppSizes.thumbHeight,
                child: Stack(
                  fit: StackFit.expand,
                  children: [
                    if (cover == null)
                      const ColoredBox(
                        color: AppColors.surfaceSunk,
                        child: Icon(LucideIcons.car, color: AppColors.inkFaint, size: AppSizes.icon),
                      )
                    else
                      ColorFiltered(
                        colorFilter: const ColorFilter.matrix(_greyscale),
                        child: CachedNetworkImage(
                          imageUrl: cover.url!,
                          cacheKey: imageCacheKey(cover.url!),
                          fit: BoxFit.cover,
                          errorWidget: (_, _, _) => const ColoredBox(color: AppColors.surfaceSunk),
                        ),
                      ),
                    const ColoredBox(color: AppColors.retired),
                  ],
                ),
              ),
            ),
          ),
          const SizedBox(width: AppSpace.s3),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                // Read as one: "Annonce retirée, Peugeot 208, 1.2 PureTech".
                MergeSemantics(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Container(
                        padding: const EdgeInsets.symmetric(horizontal: AppSpace.s2, vertical: AppSpace.s0),
                        decoration: const BoxDecoration(
                          color: AppColors.surfaceSunk,
                          borderRadius: BorderRadius.all(Radius.circular(AppRadius.pill)),
                        ),
                        child: Text('Annonce retirée', style: AppText.tag.copyWith(fontWeight: AppType.bold)),
                      ),
                      const SizedBox(height: AppSpace.s1),
                      Text(
                        headline.title,
                        style: theme.textTheme.titleMedium?.copyWith(color: AppColors.inkSoft),
                        maxLines: 2,
                        overflow: TextOverflow.ellipsis,
                      ),
                      if (headline.subtitle != null)
                        Text(
                          headline.subtitle!,
                          style: theme.textTheme.bodySmall,
                          maxLines: 1,
                          overflow: TextOverflow.ellipsis,
                        ),
                    ],
                  ),
                ),
                const SizedBox(height: AppSpace.s1),
                Semantics(
                  container: true,
                  button: true,
                  label: 'Retirer ${headline.title} des favoris',
                  excludeSemantics: true,
                  child: TextButton.icon(
                    style: TextButton.styleFrom(padding: const EdgeInsets.symmetric(horizontal: AppSpace.s2)),
                    onPressed: () => ref.read(favouritesProvider.notifier).remove(listing.id!),
                    icon: const Icon(LucideIcons.trash2, size: AppSizes.iconSmall),
                    label: const Text('Retirer'),
                  ),
                ),
              ],
            ),
          ),
        ],
      ),
    );
  }

  /// Luminance weights: the photo in greys.
  static const _greyscale = <double>[
    0.2126, 0.7152, 0.0722, 0, 0, //
    0.2126, 0.7152, 0.0722, 0, 0, //
    0.2126, 0.7152, 0.0722, 0, 0, //
    0, 0, 0, 1, 0,
  ];
}

class _Empty extends StatelessWidget {
  const _Empty();

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return Padding(
      padding: const EdgeInsets.fromLTRB(AppSpace.s5, 0, AppSpace.s5, AppSpace.s7),
      child: Column(
        mainAxisAlignment: MainAxisAlignment.center,
        children: [
          const IconTile(icon: LucideIcons.heart, size: AppSizes.touch + AppSpace.s4),
          const SizedBox(height: AppSpace.s4),
          Text('Aucun favori pour l’instant', style: theme.textTheme.titleLarge, textAlign: TextAlign.center),
          const SizedBox(height: AppSpace.s2),
          Text(
            'Touchez le cœur d’une annonce pour la garder ici, sur ce téléphone.',
            style: theme.textTheme.bodyMedium?.copyWith(color: AppColors.inkSoft),
            textAlign: TextAlign.center,
          ),
          const SizedBox(height: AppSpace.s5),
          FilledButton.icon(
            style: FilledButton.styleFrom(minimumSize: const Size(0, AppSizes.control)),
            onPressed: () => context.go('/recherche'),
            icon: const Icon(LucideIcons.search, size: AppSizes.icon),
            label: const Text('Rechercher une voiture'),
          ),
        ],
      ),
    );
  }
}
