/// The listing and dealer pages' places in the app. The paths are the site's (`/annonces/:id`,
/// `/professionnels/:slug`), so App Links can later open the site's links here as they are.
const dealersLocation = '/professionnels';

String listingLocation(String id) => '/annonces/${Uri.encodeComponent(id)}';

String dealerLocation(String slug) => '/professionnels/${Uri.encodeComponent(slug)}';
