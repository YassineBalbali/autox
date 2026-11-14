import 'package:autox_api/autox_api.dart' show DealerCard, DealerPage;

import '../../core/labels/labels.g.dart';
import '../listing/listing_text.dart' show countFr;

/// The dealers' words, as the web's `features/dealers` writes them.

/// "33 · Gironde", or the code alone for one the list does not know.
String departementLabel(String? code) {
  if (code == null || code.isEmpty) {
    return '';
  }
  final name = departementNames[code];
  return name == null ? code : '$code · $name';
}

/// "Lyon (69)", or the city alone.
String? dealerPlace(String? city, String? departement) {
  if (city == null || city.isEmpty) {
    return null;
  }
  return departement == null || departement.isEmpty ? city : '$city ($departement)';
}

/// A directory card: "12 annonces · 3 offres de location"; "0 annonce" for a dealer with none.
String cardListings(DealerCard card) {
  final sale = card.saleListings ?? 0;
  final leasing = card.leasingListings ?? 0;
  return [
    if (sale > 0 || leasing == 0) countFr(sale, 'annonce', 'annonces'),
    if (leasing > 0) '${countFr(leasing, 'offre', 'offres')} de location',
  ].join(' · ');
}

/// A dealer's page: "12 annonces en ligne · 3 offres de location".
String pageListings(DealerPage dealer) {
  final text = countFr(dealer.saleListings ?? 0, 'annonce en ligne', 'annonces en ligne');
  final leasing = dealer.leasingListings ?? 0;
  return leasing > 0 ? '$text · ${countFr(leasing, 'offre', 'offres')} de location' : text;
}

/// The website without its scheme or trailing slash: "garage-bellevue.fr".
String websiteLabel(String url) =>
    url.replaceFirst(RegExp('^https?://', caseSensitive: false), '').replaceFirst(RegExp(r'/$'), '');

/// The number as a `tel:` link: digits and the plus only.
Uri? phoneUri(String? phone) {
  final digits = phone?.replaceAll(RegExp(r'[^\d+]'), '') ?? '';
  return digits.isEmpty ? null : Uri(scheme: 'tel', path: digits);
}

/// An http(s) address, or nothing: the server already refuses anything else, and the app does
/// not open what it cannot vouch for.
Uri? websiteUri(String? url) {
  final uri = url == null ? null : Uri.tryParse(url);
  return uri != null && (uri.isScheme('http') || uri.isScheme('https')) && uri.host.isNotEmpty ? uri : null;
}
