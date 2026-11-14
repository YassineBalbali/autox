import 'package:autox_api/autox_api.dart' show InterpretRequest, ListingSearchCriteria, Make, Model;
import 'package:flutter/foundation.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../core/api/api_failure.dart';
import '../../core/reference/reference_providers.dart';
import 'search_query.dart';

/// "Recherche intelligente": a description of a car in the person's own words, turned into the
/// filters the ordinary search takes by `POST /search/interpret`. The app then searches with
/// them as if they had been chosen by hand, and says what was understood.

/// The longest description sent, as on the web.
const maxDescription = 200;

/// What to show above the results of an interpreted search.
@immutable
class Interpretation {
  const Interpretation({required this.text, this.notUnderstood = const []});

  /// The description as sent.
  final String text;

  /// The parts the server could not turn into a filter.
  final List<String> notUnderstood;
}

typedef Interpreted = ({SearchQuery query, Interpretation interpretation});

/// Sends [description] and returns it as a search. Throws an [ApiFailure]: 503 when the model
/// is unavailable, 429 when this address has asked too often, 404 when the feature is off.
Future<Interpreted> interpret(Ref ref, String description) async {
  final text = description.trim().replaceAll(RegExp(r'\s+'), ' ');
  final sent = text.length > maxDescription ? text.substring(0, maxDescription) : text;
  try {
    final response = await ref
        .read(naturalLanguageApiProvider)
        .interpret(interpretRequest: InterpretRequest(query: sent));
    final criteria = response.data?.criteria ?? ListingSearchCriteria();
    final makes = await ref.read(makesProvider.future).catchError((Object _) => const <Make>[]);
    final models = criteria.makeId == null
        ? const <Model>[]
        : await ref.read(modelsProvider(criteria.makeId!).future).catchError((Object _) => const <Model>[]);
    return (
      query: queryFromCriteria(criteria, makes: makes, models: models),
      interpretation: Interpretation(text: sent, notUnderstood: response.data?.notUnderstood ?? const []),
    );
  } on Object catch (error) {
    throw ApiFailure.from(error);
  }
}

/// The French for why an interpretation failed. The ordinary search sits beside the box, so
/// none of these need say how else to search.
String interpretFailureMessage(ApiFailure failure) {
  if (failure is ServerFailure) {
    if (failure.status == 429) {
      return 'Trop de recherches intelligentes en peu de temps. Réessayez dans quelques minutes.';
    }
    if (failure.status == 503 || failure.status == 404) {
      return 'La recherche intelligente est indisponible pour le moment.';
    }
  }
  return failure.message;
}

/// [interpret], for widgets, which hold a `WidgetRef` rather than a `Ref`.
final interpreterProvider = Provider<Future<Interpreted> Function(String description)>(
  (ref) =>
      (description) => interpret(ref, description),
);
