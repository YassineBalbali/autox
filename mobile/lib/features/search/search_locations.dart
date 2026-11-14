import 'search_query.dart';

/// The search's places in the app, under the Rechercher tab.
const searchHomeLocation = '/recherche';
const resultsPath = '/recherche/resultats';
const entryLocation = '/recherche/saisie';
const newCarsLocation = '/recherche/neuf';

/// The results of [query], its filters in the site's query parameters.
String resultsLocation(SearchQuery query) {
  final params = toQueryParams(query);
  return Uri(path: resultsPath, queryParameters: params.isEmpty ? null : params).toString();
}
