import 'dart:convert';

import 'package:autox_api/autox_api.dart' show ImageVariantView, PublicListingDetail, PublicListingSummary, SellerCard;
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../search/recent_searches.dart' show sharedPreferencesProvider;

/// The storage key, the web's (`autox.favourites`), though the two never share storage.
const favouritesKey = 'autox.favourites';

/// Most favourites one phone keeps: the web's `FAVOURITES_LIMIT`, which
/// `test/features/favourites/favourites_test.dart` reads from `favourites.ts`. Past it, the
/// oldest goes, as on the web.
const favouritesLimit = 200;

/// One saved listing: when, and the listing as it was then — what the Favoris tab shows at once,
/// and still shows without a network, while it asks for the listing as it is now.
class Favourite {
  const Favourite({required this.id, required this.savedAt, required this.listing});

  final String id;
  final DateTime savedAt;
  final PublicListingSummary listing;

  Map<String, Object?> toJson() => {
    'id': id,
    'savedAt': savedAt.toUtc().toIso8601String(),
    'listing': listing.toJson(),
  };

  /// Null for anything that is not an entry this code wrote.
  static Favourite? fromJson(Object? json) {
    if (json is! Map) {
      return null;
    }
    final id = json['id'];
    final savedAt = json['savedAt'] is String ? DateTime.tryParse(json['savedAt'] as String) : null;
    final listing = json['listing'];
    if (id is! String || id.isEmpty || savedAt == null || listing is! Map) {
      return null;
    }
    try {
      return Favourite(
        id: id,
        savedAt: savedAt,
        listing: PublicListingSummary.fromJson(listing.cast<String, dynamic>()),
      );
    } on Object {
      return null;
    }
  }
}

/// The listings marked with the heart, on this phone only — the web's `Favourites`, which keeps
/// them in the browser. Most recently saved first. No account behind it, nothing sent to the API.
class Favourites extends Notifier<List<Favourite>> {
  @override
  List<Favourite> build() => _read();

  bool has(String id) => state.any((favourite) => favourite.id == id);

  /// Saves [listing], or forgets it if it was saved. Returns whether it is saved now.
  bool toggle(PublicListingSummary listing, {DateTime? now}) {
    final id = listing.id;
    if (id == null || id.isEmpty) {
      return false;
    }
    if (has(id)) {
      remove(id);
      return false;
    }
    final entry = Favourite(id: id, savedAt: now ?? DateTime.now(), listing: listing);
    _write([entry, ...state].take(favouritesLimit).toList());
    return true;
  }

  void remove(String id) => _write(state.where((favourite) => favourite.id != id).toList());

  List<Favourite> _read() {
    final raw = ref.read(sharedPreferencesProvider).getString(favouritesKey);
    if (raw == null) {
      return const [];
    }
    try {
      final parsed = jsonDecode(raw);
      if (parsed is! List) {
        return const [];
      }
      // Whatever is stored was written by an earlier version, or by hand: validated, not trusted.
      final seen = <String>{};
      final entries = [
        for (final item in parsed.map(Favourite.fromJson))
          if (item != null && seen.add(item.id)) item,
      ]..sort((a, b) => b.savedAt.compareTo(a.savedAt));
      return List.unmodifiable(entries.take(favouritesLimit));
    } on FormatException {
      return const [];
    }
  }

  void _write(List<Favourite> entries) {
    state = List.unmodifiable(entries);
    // Not awaited: the hearts are already right, and a failed write only costs the memory of it.
    ref
        .read(sharedPreferencesProvider)
        .setString(favouritesKey, jsonEncode([for (final entry in entries) entry.toJson()]))
        .ignore();
  }
}

final favouritesProvider = NotifierProvider<Favourites, List<Favourite>>(Favourites.new);

/// Whether the listing [id] is saved, for one heart.
final isFavouriteProvider = Provider.family<bool, String>(
  (ref, id) => ref.watch(favouritesProvider).any((favourite) => favourite.id == id),
);

/// A listing's page as a search result: what the Favoris tab keeps of a listing saved from its
/// page, and how it shows a saved listing as it is now.
PublicListingSummary summaryOfDetail(PublicListingDetail listing) {
  final vehicle = listing.vehicle;
  final location = listing.location;
  final seller = listing.seller;
  final images = [...?listing.images]..sort((a, b) => (a.position ?? 0).compareTo(b.position ?? 0));
  final cover = images
      .map((image) => image.variants ?? const {})
      .firstWhere(
        (variants) => variants['CARD']?.url != null || variants['THUMBNAIL']?.url != null,
        orElse: () => const {},
      );
  return PublicListingSummary(
    id: listing.id,
    title: listing.title,
    priceAmount: listing.priceAmount,
    currencyCode: listing.currencyCode,
    priceNegotiable: listing.priceNegotiable,
    make: vehicle?.make,
    model: vehicle?.model,
    version: vehicle?.version,
    firstRegistrationYear: vehicle?.firstRegistrationYear,
    firstRegistrationMonth: vehicle?.firstRegistrationMonth,
    mileageKm: listing.mileageKm,
    fuelType: vehicle?.fuelType?.value,
    gearbox: vehicle?.gearbox?.value,
    bodyType: vehicle?.bodyType?.value,
    drivetrain: vehicle?.drivetrain?.value,
    powerHp: vehicle?.powerHp,
    condition: listing.condition,
    countryCode: location?.countryCode,
    city: location?.city,
    postalCode: location?.postalCode,
    seller: seller == null ? null : SellerCard(id: seller.id, displayName: seller.displayName, role: seller.role),
    coverImage: {
      for (final size in const ['CARD', 'THUMBNAIL'])
        if (cover[size]?.url != null)
          size: ImageVariantView(url: cover[size]!.url, width: cover[size]!.width, height: cover[size]!.height),
    },
    photoCount: images.length,
    priceIndicator: listing.priceIndicator,
    publishedAt: listing.publishedAt,
    damage: listing.damage,
    leasing: listing.leasing,
    soldNew: listing.soldNew,
  );
}
