import 'package:autox_api/autox_api.dart'
    show
        BodyType,
        DamageFilter,
        FuelType,
        Gearbox,
        LeasingType,
        ListingSearchCriteria,
        Make,
        Model,
        OfferType,
        PriceLevel,
        SearchSort,
        VehicleCondition;
import 'package:flutter/foundation.dart';

import '../../core/format/numbers.dart';
import '../../core/labels/labels.g.dart';

/// A search, as the person built it: the app's port of the web's `SearchQuery`
/// (`frontend/src/app/features/search/search-query.ts`).
///
/// **The rules are the web's, checked by the same test vectors**: `frontend/fixtures/
/// search-query.json` runs against both implementations (`src/app/shared/fixtures.spec.ts` and
/// `test/shared_fixtures_test.dart`). Change a rule on the web, regenerate the vectors, and this
/// file must follow. Make and model are catalogue slugs, as in the site's URLs; prices are whole
/// units of the currency, converted to minor units by [toCriteria].
@immutable
class SearchQuery {
  const SearchQuery({
    this.text,
    this.make,
    this.model,
    this.yearMin,
    this.yearMax,
    this.priceMin,
    this.priceMax,
    this.mileageMin,
    this.mileageMax,
    this.fuel = const [],
    this.gearbox = const [],
    this.body = const [],
    this.condition = const [],
    this.damage = DamageFilter.UNDAMAGED,
    this.location,
    this.goodDeals = false,
    this.offer = OfferType.SALE,
    this.leasingType = const [],
    this.monthlyMin,
    this.monthlyMax,
    this.durationMin,
    this.durationMax,
    this.allowanceMin,
    this.critAirMax,
    this.sort = defaultSort,
    this.sellerId,
  });

  static const SearchQuery empty = SearchQuery();
  static const SearchSort defaultSort = SearchSort.NEWEST;

  /// Free text: every word must match the title, make, model or version.
  final String? text;
  final String? make;
  final String? model;
  final int? yearMin;
  final int? yearMax;
  final int? priceMin;
  final int? priceMax;
  final int? mileageMin;
  final int? mileageMax;
  final List<FuelType> fuel;
  final List<Gearbox> gearbox;
  final List<BodyType> body;
  final List<VehicleCondition> condition;

  /// Damaged vehicles are out of a search unless it asks for them.
  final DamageFilter damage;
  final String? location;
  final bool goodDeals;

  /// Sales or leasing offers: a search is for one or the other, never both.
  final OfferType offer;
  final List<LeasingType> leasingType;
  final int? monthlyMin;
  final int? monthlyMax;
  final int? durationMin;
  final int? durationMax;
  final int? allowanceMin;

  /// "Crit'Air 2 ou mieux": the highest class wanted, 0 to 5.
  final int? critAirMax;
  final SearchSort sort;

  /// One seller's listings only: a dealer's page. App-only, and deliberately not a URL
  /// parameter: the site scopes a search to a dealer by its page, not by a parameter, so
  /// [toQueryParams] and [parseQuery] leave it out.
  final String? sellerId;

  bool get isLeasing => offer == OfferType.LEASING;

  SearchQuery copyWith({
    Object? text = _unset,
    Object? make = _unset,
    Object? model = _unset,
    Object? yearMin = _unset,
    Object? yearMax = _unset,
    Object? priceMin = _unset,
    Object? priceMax = _unset,
    Object? mileageMin = _unset,
    Object? mileageMax = _unset,
    List<FuelType>? fuel,
    List<Gearbox>? gearbox,
    List<BodyType>? body,
    List<VehicleCondition>? condition,
    DamageFilter? damage,
    Object? location = _unset,
    bool? goodDeals,
    OfferType? offer,
    List<LeasingType>? leasingType,
    Object? monthlyMin = _unset,
    Object? monthlyMax = _unset,
    Object? durationMin = _unset,
    Object? durationMax = _unset,
    Object? allowanceMin = _unset,
    Object? critAirMax = _unset,
    SearchSort? sort,
    Object? sellerId = _unset,
  }) => SearchQuery(
    text: _pick(text, this.text),
    make: _pick(make, this.make),
    model: _pick(model, this.model),
    yearMin: _pick(yearMin, this.yearMin),
    yearMax: _pick(yearMax, this.yearMax),
    priceMin: _pick(priceMin, this.priceMin),
    priceMax: _pick(priceMax, this.priceMax),
    mileageMin: _pick(mileageMin, this.mileageMin),
    mileageMax: _pick(mileageMax, this.mileageMax),
    fuel: fuel ?? this.fuel,
    gearbox: gearbox ?? this.gearbox,
    body: body ?? this.body,
    condition: condition ?? this.condition,
    damage: damage ?? this.damage,
    location: _pick(location, this.location),
    goodDeals: goodDeals ?? this.goodDeals,
    offer: offer ?? this.offer,
    leasingType: leasingType ?? this.leasingType,
    monthlyMin: _pick(monthlyMin, this.monthlyMin),
    monthlyMax: _pick(monthlyMax, this.monthlyMax),
    durationMin: _pick(durationMin, this.durationMin),
    durationMax: _pick(durationMax, this.durationMax),
    allowanceMin: _pick(allowanceMin, this.allowanceMin),
    critAirMax: _pick(critAirMax, this.critAirMax),
    sort: sort ?? this.sort,
    sellerId: _pick(sellerId, this.sellerId),
  );

  /// The web's field names and wire values: what the shared fixtures describe a query with.
  Map<String, Object?> toJson() => {
    'text': text,
    'make': make,
    'model': model,
    'yearMin': yearMin,
    'yearMax': yearMax,
    'priceMin': priceMin,
    'priceMax': priceMax,
    'mileageMin': mileageMin,
    'mileageMax': mileageMax,
    'fuel': fuel.map((value) => value.value).toList(),
    'gearbox': gearbox.map((value) => value.value).toList(),
    'body': body.map((value) => value.value).toList(),
    'condition': condition.map((value) => value.value).toList(),
    'damage': damage.value,
    'location': location,
    'goodDeals': goodDeals,
    'offer': offer.value,
    'leasingType': leasingType.map((value) => value.value).toList(),
    'monthlyMin': monthlyMin,
    'monthlyMax': monthlyMax,
    'durationMin': durationMin,
    'durationMax': durationMax,
    'allowanceMin': allowanceMin,
    'critAirMax': critAirMax,
    'sort': sort.value,
    'sellerId': sellerId,
  };

  /// The inverse of [toJson], for a partial description (what differs from [empty]).
  factory SearchQuery.fromJson(Map<String, Object?> json) {
    List<T> list<T extends Enum>(String key, List<T> values) => [
      for (final raw in (json[key] as List<Object?>?) ?? const <Object?>[]) _enumByValue(values, raw as String)!,
    ];
    int? number(String key) => json[key] as int?;
    return SearchQuery(
      text: json['text'] as String?,
      make: json['make'] as String?,
      model: json['model'] as String?,
      yearMin: number('yearMin'),
      yearMax: number('yearMax'),
      priceMin: number('priceMin'),
      priceMax: number('priceMax'),
      mileageMin: number('mileageMin'),
      mileageMax: number('mileageMax'),
      fuel: list('fuel', FuelType.values),
      gearbox: list('gearbox', Gearbox.values),
      body: list('body', BodyType.values),
      condition: list('condition', VehicleCondition.values),
      damage: _enumByValue(DamageFilter.values, json['damage'] as String?) ?? DamageFilter.UNDAMAGED,
      location: json['location'] as String?,
      goodDeals: json['goodDeals'] as bool? ?? false,
      offer: _enumByValue(OfferType.values, json['offer'] as String?) ?? OfferType.SALE,
      leasingType: list('leasingType', LeasingType.values),
      monthlyMin: number('monthlyMin'),
      monthlyMax: number('monthlyMax'),
      durationMin: number('durationMin'),
      durationMax: number('durationMax'),
      allowanceMin: number('allowanceMin'),
      critAirMax: number('critAirMax'),
      sort: _enumByValue(SearchSort.values, json['sort'] as String?) ?? defaultSort,
      sellerId: json['sellerId'] as String?,
    );
  }

  @override
  bool operator ==(Object other) => other is SearchQuery && mapEquals(_key, other._key);

  @override
  int get hashCode => Object.hashAll(_key.entries.map((entry) => Object.hash(entry.key, entry.value)));

  /// Lists as strings, so equality and the hash compare contents.
  Map<String, Object?> get _key => toJson().map((key, value) => MapEntry(key, value is List ? value.join(',') : value));

  @override
  String toString() => 'SearchQuery(${toQueryParams(this)})';
}

const Object _unset = Object();

T? _pick<T>(Object? given, T? current) => identical(given, _unset) ? current : given as T?;

E? _enumByValue<E extends Enum>(List<E> values, String? value) {
  for (final candidate in values) {
    if (_wire(candidate) == value) {
      return candidate;
    }
  }
  return null;
}

/// Wire value of a generated enum: their `toString` returns it ('PLUGIN_HYBRID').
String _wire(Enum value) => value.toString();

// ---- the rules -----------------------------------------------------------------------------

/// The sorts that only mean something for a leasing offer, and for a sale.
const _leasingSorts = {
  SearchSort.MONTHLY_ASC,
  SearchSort.MONTHLY_DESC,
  SearchSort.DURATION_ASC,
  SearchSort.MILEAGE_ALLOWANCE_DESC,
};
const _saleSorts = {SearchSort.PRICE_ASC, SearchSort.PRICE_DESC, SearchSort.MILEAGE_ASC};

/// A sort that means nothing in this search is the default one, rather than an error from the API.
SearchSort sortFor(SearchSort sort, OfferType offer) {
  final offTopic = offer == OfferType.LEASING ? _saleSorts : _leasingSorts;
  return offTopic.contains(sort) || sort == SearchSort.unknownDefaultOpenApi ? SearchQuery.defaultSort : sort;
}

/// Whether "bonnes affaires" can find anything: never among damaged vehicles alone, never for a
/// leasing offer. The server's interpreter holds the same rule.
bool allowsGoodDeals({required DamageFilter damage, required OfferType offer}) =>
    damage != DamageFilter.DAMAGED_ONLY && offer != OfferType.LEASING;

/// The other search, from this one: what describes the car stays, what only means something in
/// the search being left goes.
SearchQuery switchOffer(SearchQuery query, OfferType offer) => query.copyWith(
  offer: offer,
  priceMin: null,
  priceMax: null,
  goodDeals: false,
  damage: DamageFilter.UNDAMAGED,
  leasingType: const [],
  monthlyMin: null,
  monthlyMax: null,
  durationMin: null,
  durationMax: null,
  allowanceMin: null,
  sort: SearchQuery.defaultSort,
);

/// The same search with every filter cleared, the offer and the sort kept.
SearchQuery withoutFilters(SearchQuery query) =>
    SearchQuery(offer: query.offer, sort: query.sort, sellerId: query.sellerId);

/// How many filters are set — the number on the "Filtres" button. Sort is not a filter.
int activeFilterCount(SearchQuery query) => <Object?>[
  query.text,
  query.make,
  query.model,
  query.yearMin ?? query.yearMax,
  query.priceMin ?? query.priceMax,
  query.mileageMin ?? query.mileageMax,
  query.fuel.isEmpty ? null : 1,
  query.gearbox.isEmpty ? null : 1,
  query.body.isEmpty ? null : 1,
  query.condition.isEmpty ? null : 1,
  query.damage == DamageFilter.UNDAMAGED ? null : 1,
  query.location,
  query.goodDeals ? 1 : null,
  query.leasingType.isEmpty ? null : 1,
  query.monthlyMin ?? query.monthlyMax,
  query.durationMin ?? query.durationMax,
  query.allowanceMin,
  query.critAirMax,
].where((value) => value != null).length;

// ---- URL parameters ------------------------------------------------------------------------

/// The site's query-string keys, French. The app stores recent searches with them, and App
/// Links will map the site's search URLs onto the app with them.
abstract final class Param {
  static const text = 'q';
  static const make = 'marque';
  static const model = 'modele';
  static const yearMin = 'annee_min';
  static const yearMax = 'annee_max';
  static const priceMin = 'prix_min';
  static const priceMax = 'prix_max';
  static const mileageMin = 'km_min';
  static const mileageMax = 'km_max';
  static const fuel = 'carburant';
  static const gearbox = 'boite';
  static const body = 'carrosserie';
  static const condition = 'etat';
  static const damage = 'etat_vehicule';
  static const location = 'lieu';
  static const goodDeals = 'bonnes_affaires';
  static const offer = 'offre';
  static const leasingType = 'type_location';
  static const monthlyMin = 'mensualite_min';
  static const monthlyMax = 'mensualite_max';
  static const durationMin = 'duree_min';
  static const durationMax = 'duree_max';
  static const allowanceMin = 'km_an_min';
  static const sort = 'tri';
  static const critAirMax = 'critair_max';
}

const _offerLeasingSlug = 'location';

const _sortSlugs = {
  SearchSort.NEWEST: 'recentes',
  SearchSort.PRICE_ASC: 'prix-croissant',
  SearchSort.PRICE_DESC: 'prix-decroissant',
  SearchSort.MILEAGE_ASC: 'km-croissant',
  SearchSort.MONTHLY_ASC: 'mensualite-croissante',
  SearchSort.MONTHLY_DESC: 'mensualite-decroissante',
  SearchSort.DURATION_ASC: 'duree-croissante',
  SearchSort.MILEAGE_ALLOWANCE_DESC: 'km-annuel-decroissant',
};

const _damageSlugs = {DamageFilter.DAMAGED_ONLY: 'endommages', DamageFilter.ALL: 'tous'};

// The bounds the API validates against (ListingSearchCriteria on the backend).
const _yearRange = (1900, 2100);
const _maxPrice = 10000000;
const _maxMileage = 3000000;
const _maxMonthly = 1000000;
const _maxLeaseMonths = 120;
const _maxAllowance = 200000;
const _maxText = 80;
const _maxQuery = 120;

/// A search out of the site's query parameters. Untrusted, like an address bar: numbers parsed
/// and bounded, enum values checked, anything unknown dropped — a bad parameter costs that one
/// filter, never the search.
SearchQuery parseQuery(Map<String, String> params) {
  final offer = params[Param.offer] == _offerLeasingSlug ? OfferType.LEASING : OfferType.SALE;
  final leasing = offer == OfferType.LEASING;
  // Nobody leases a damaged car, so a link asking for both asks for the lease.
  final damage = leasing ? DamageFilter.UNDAMAGED : _damageFromParams(params);

  final (monthlyMin, monthlyMax) = _ordered(
    _integer(params[Param.monthlyMin], 0, _maxMonthly),
    _integer(params[Param.monthlyMax], 0, _maxMonthly),
  );
  final (durationMin, durationMax) = _ordered(
    _integer(params[Param.durationMin], 0, _maxLeaseMonths),
    _integer(params[Param.durationMax], 0, _maxLeaseMonths),
  );
  final (yearMin, yearMax) = _ordered(
    _integer(params[Param.yearMin], _yearRange.$1, _yearRange.$2),
    _integer(params[Param.yearMax], _yearRange.$1, _yearRange.$2),
  );
  final (priceMin, priceMax) = _ordered(
    _integer(params[Param.priceMin], 0, _maxPrice),
    _integer(params[Param.priceMax], 0, _maxPrice),
  );
  final (mileageMin, mileageMax) = _ordered(
    _integer(params[Param.mileageMin], 0, _maxMileage),
    _integer(params[Param.mileageMax], 0, _maxMileage),
  );
  final make = _text(params[Param.make]);

  return SearchQuery(
    text: _text(params[Param.text], _maxQuery),
    make: make,
    // A model means nothing without its make.
    model: make != null ? _text(params[Param.model]) : null,
    yearMin: yearMin,
    yearMax: yearMax,
    priceMin: leasing ? null : priceMin,
    priceMax: leasing ? null : priceMax,
    mileageMin: mileageMin,
    mileageMax: mileageMax,
    fuel: _enumList(params[Param.fuel], FuelType.values),
    gearbox: _enumList(params[Param.gearbox], Gearbox.values),
    body: _enumList(params[Param.body], BodyType.values),
    condition: _enumList(params[Param.condition], VehicleCondition.values),
    damage: damage,
    location: _text(params[Param.location]),
    // "1" and nothing else: a flag in a link is on or absent.
    goodDeals: params[Param.goodDeals] == '1' && allowsGoodDeals(damage: damage, offer: offer),
    offer: offer,
    leasingType: leasing ? _enumList(params[Param.leasingType], LeasingType.values) : const [],
    monthlyMin: leasing ? monthlyMin : null,
    monthlyMax: leasing ? monthlyMax : null,
    durationMin: leasing ? durationMin : null,
    durationMax: leasing ? durationMax : null,
    allowanceMin: leasing ? _integer(params[Param.allowanceMin], 0, _maxAllowance) : null,
    critAirMax: _integer(params[Param.critAirMax], 0, 5),
    sort: sortFor(_sortFromSlug(params[Param.sort]), offer),
  );
}

/// A search as the site's query parameters: canonical — defaults omitted, lists sorted — so
/// the same search is always the same parameters.
Map<String, String> toQueryParams(SearchQuery query) {
  final params = <String, String>{};
  void put(String key, Object? value) {
    if (value != null && value != '') {
      params[key] = '$value';
    }
  }

  final leasing = query.isLeasing;
  put(Param.text, query.text);
  put(Param.make, query.make);
  put(Param.model, query.make != null ? query.model : null);
  put(Param.yearMin, query.yearMin);
  put(Param.yearMax, query.yearMax);
  put(Param.priceMin, leasing ? null : query.priceMin);
  put(Param.priceMax, leasing ? null : query.priceMax);
  put(Param.mileageMin, query.mileageMin);
  put(Param.mileageMax, query.mileageMax);
  put(Param.fuel, _enumSlugs(query.fuel));
  put(Param.gearbox, _enumSlugs(query.gearbox));
  put(Param.body, _enumSlugs(query.body));
  put(Param.condition, _enumSlugs(query.condition));
  put(Param.damage, leasing ? null : _damageSlugs[query.damage]);
  put(Param.location, query.location);
  put(Param.goodDeals, query.goodDeals && allowsGoodDeals(damage: query.damage, offer: query.offer) ? '1' : null);
  if (leasing) {
    put(Param.offer, _offerLeasingSlug);
    put(Param.leasingType, _enumSlugs(query.leasingType));
    put(Param.monthlyMin, query.monthlyMin);
    put(Param.monthlyMax, query.monthlyMax);
    put(Param.durationMin, query.durationMin);
    put(Param.durationMax, query.durationMax);
    put(Param.allowanceMin, query.allowanceMin);
  }
  put(Param.critAirMax, query.critAirMax);
  final sort = sortFor(query.sort, query.offer);
  put(Param.sort, sort == SearchQuery.defaultSort ? null : _sortSlugs[sort]);
  return params;
}

int? _integer(String? raw, int min, int max) {
  final trimmed = raw?.trim();
  if (trimmed == null || !RegExp(r'^\d{1,9}$').hasMatch(trimmed)) {
    return null;
  }
  final value = int.parse(trimmed);
  return value >= min && value <= max ? value : null;
}

(int?, int?) _ordered(int? min, int? max) => min != null && max != null && min > max ? (max, min) : (min, max);

String? _text(String? raw, [int max = _maxText]) {
  final value = raw?.trim().replaceAll(RegExp(r'\s+'), ' ') ?? '';
  if (value.isEmpty) {
    return null;
  }
  return value.length > max ? value.substring(0, max) : value;
}

DamageFilter _damageFromParams(Map<String, String> params) {
  final slug = params[Param.damage];
  for (final MapEntry(key: filter, value: value) in _damageSlugs.entries) {
    if (value == slug) {
      return filter;
    }
  }
  // An older link: "damaged" used to be a value of `etat`.
  final legacy = (params[Param.condition] ?? '').toUpperCase().split(',');
  return legacy.contains('DAMAGED') ? DamageFilter.DAMAGED_ONLY : DamageFilter.UNDAMAGED;
}

/// `plugin-hybrid,diesel` to `[DIESEL, PLUGIN_HYBRID]`: known values only, once each, sorted.
List<E> _enumList<E extends Enum>(String? raw, List<E> values) {
  if (raw == null || raw.isEmpty) {
    return const [];
  }
  final found = <String, E>{};
  for (final part in raw.split(',')) {
    final candidate = part.trim().toUpperCase().replaceAll('-', '_');
    final known = _enumByValue(values, candidate);
    if (known != null && candidate != 'unknown_default_open_api'.toUpperCase()) {
      found[candidate] = known;
    }
  }
  final keys = found.keys.toList()..sort();
  return [for (final key in keys) found[key]!];
}

String? _enumSlugs(List<Enum> values) {
  if (values.isEmpty) {
    return null;
  }
  final wire = values.map(_wire).toList()..sort();
  return wire.map((value) => value.toLowerCase().replaceAll('_', '-')).join(',');
}

SearchSort _sortFromSlug(String? raw) {
  for (final MapEntry(key: sort, value: slug) in _sortSlugs.entries) {
    if (slug == raw) {
      return sort;
    }
  }
  return SearchQuery.defaultSort;
}

// ---- to the API, and back ------------------------------------------------------------------

/// What the make and model in a query turned out to be, once the catalogue was consulted.
@immutable
class ResolvedVehicle {
  const ResolvedVehicle({this.makeId, this.makeText, this.modelId, this.modelText});

  final String? makeId;
  final String? makeText;
  final String? modelId;
  final String? modelText;
}

/// The price filters' currency. The site's searches are in euros; a price bound is meaningless
/// without its currency.
const priceFilterCurrency = 'EUR';

/// The query as what the API takes. [minorUnit] is the currency's decimal places: prices in a
/// query are whole units, the API compares minor units.
ListingSearchCriteria toCriteria(
  SearchQuery query,
  ResolvedVehicle vehicle, {
  String currency = priceFilterCurrency,
  int minorUnit = 2,
}) {
  var scale = 1;
  for (var i = 0; i < minorUnit; i++) {
    scale *= 10;
  }
  final leasing = query.isLeasing;
  final hasPrice = !leasing && (query.priceMin != null || query.priceMax != null);
  final hasMonthly = leasing && (query.monthlyMin != null || query.monthlyMax != null);
  List<T>? nonEmpty<T>(List<T> values) => values.isEmpty ? null : [...values];

  return ListingSearchCriteria(
    q: query.text,
    makeId: vehicle.makeId,
    make: vehicle.makeText,
    modelId: vehicle.modelId,
    model: vehicle.modelText,
    yearFrom: query.yearMin,
    yearTo: query.yearMax,
    priceMin: leasing || query.priceMin == null ? null : query.priceMin! * scale,
    priceMax: leasing || query.priceMax == null ? null : query.priceMax! * scale,
    // Only alongside a price bound: "under 15 000" must not match 15 000 of something else, but
    // with no price filter there is no reason to hide other currencies.
    currency: hasPrice || hasMonthly ? currency : null,
    mileageMin: query.mileageMin,
    mileageMax: query.mileageMax,
    fuelType: nonEmpty(query.fuel),
    gearbox: nonEmpty(query.gearbox),
    bodyType: nonEmpty(query.body),
    condition: nonEmpty(query.condition),
    // Left out for the default, which is also the API's.
    damage: leasing || query.damage == DamageFilter.UNDAMAGED ? null : query.damage,
    offer: leasing ? OfferType.LEASING : null,
    leasingType: leasing ? nonEmpty(query.leasingType) : null,
    monthlyMin: !leasing || query.monthlyMin == null ? null : query.monthlyMin! * scale,
    monthlyMax: !leasing || query.monthlyMax == null ? null : query.monthlyMax! * scale,
    durationMin: leasing ? query.durationMin : null,
    durationMax: leasing ? query.durationMax : null,
    mileageAllowanceMin: leasing ? query.allowanceMin : null,
    location: query.location,
    priceLevel: query.goodDeals ? const [PriceLevel.GOOD_DEAL] : null,
    critAirMax: query.critAirMax,
    sellerId: query.sellerId,
  );
}

/// A natural-language search's criteria as a query (the web's `queryFromCriteria`). Catalogue
/// ids become slugs through [makes] and [models]; a price only when it is in euros.
SearchQuery queryFromCriteria(
  ListingSearchCriteria criteria, {
  required List<Make> makes,
  required List<Model> models,
  int minorUnit = 2,
}) {
  final make = makes.where((candidate) => candidate.id == criteria.makeId && (candidate.slug ?? '').isNotEmpty);
  final model = make.isEmpty
      ? const <Model>[]
      : models.where((candidate) => candidate.id == criteria.modelId && (candidate.slug ?? '').isNotEmpty);
  final euros = criteria.currency == priceFilterCurrency;
  var scale = 1;
  for (var i = 0; i < minorUnit; i++) {
    scale *= 10;
  }
  int? whole(int? minor) => euros && minor != null ? (minor / scale).round() : null;
  List<E> sorted<E extends Enum>(List<E>? values) =>
      [...?values?.where((value) => _wire(value) != 'unknown_default_open_api')]
        ..sort((a, b) => _wire(a).compareTo(_wire(b)));

  return SearchQuery(
    make: make.isEmpty ? null : make.first.slug,
    model: model.isEmpty ? null : model.first.slug,
    yearMin: criteria.yearFrom,
    yearMax: criteria.yearTo,
    priceMin: whole(criteria.priceMin),
    priceMax: whole(criteria.priceMax),
    mileageMin: criteria.mileageMin,
    mileageMax: criteria.mileageMax,
    fuel: sorted(criteria.fuelType),
    gearbox: sorted(criteria.gearbox),
    body: sorted(criteria.bodyType),
    condition: sorted(criteria.condition),
    damage: criteria.damage == null || criteria.damage == DamageFilter.unknownDefaultOpenApi
        ? DamageFilter.UNDAMAGED
        : criteria.damage!,
    offer: criteria.offer == OfferType.LEASING ? OfferType.LEASING : OfferType.SALE,
    leasingType: sorted(criteria.leasingType),
    monthlyMin: whole(criteria.monthlyMin),
    monthlyMax: whole(criteria.monthlyMax),
    durationMin: criteria.durationMin,
    durationMax: criteria.durationMax,
    allowanceMin: criteria.mileageAllowanceMin,
    location: criteria.location,
    goodDeals: (criteria.priceLevel ?? const []).contains(PriceLevel.GOOD_DEAL),
  );
}

// ---- in words ------------------------------------------------------------------------------

/// A filter that is set, as a chip: its label, and the search without it.
@immutable
class AppliedFilter {
  const AppliedFilter(this.key, this.label, this.without);

  final String key;
  final String label;
  final SearchQuery without;
}

/// The catalogue names for the query's make and model slugs, when they are known.
typedef VehicleNames = ({String? make, String? model});

/// The filters that are set, as chips — the web's `describeFilters`, the same words.
List<AppliedFilter> describeFilters(SearchQuery query, {VehicleNames names = (make: null, model: null)}) {
  final filters = <AppliedFilter>[];
  void add(String key, String label, SearchQuery without) => filters.add(AppliedFilter(key, label, without));

  if (query.text != null) {
    add('text', '« ${query.text} »', query.copyWith(text: null));
  }
  if (query.make != null) {
    add('make', names.make ?? query.make!, query.copyWith(make: null, model: null));
  }
  if (query.make != null && query.model != null) {
    add('model', names.model ?? query.model!, query.copyWith(model: null));
  }
  final year = _range(query.yearMin, query.yearMax, (value) => '$value', '');
  if (year != null) {
    add('year', year, query.copyWith(yearMin: null, yearMax: null));
  }
  final price = _range(query.priceMin, query.priceMax, formatInteger, ' €');
  if (price != null) {
    add('price', price, query.copyWith(priceMin: null, priceMax: null));
  }
  final mileage = _range(query.mileageMin, query.mileageMax, formatInteger, ' km');
  if (mileage != null) {
    add('mileage', mileage, query.copyWith(mileageMin: null, mileageMax: null));
  }
  for (final value in query.fuel) {
    add('fuel:${value.value}', fuelTypeLabel(value), query.copyWith(fuel: [...query.fuel]..remove(value)));
  }
  for (final value in query.gearbox) {
    add(
      'gearbox:${value.value}',
      'Boîte ${gearboxLabel(value).toLowerCase()}',
      query.copyWith(gearbox: [...query.gearbox]..remove(value)),
    );
  }
  for (final value in query.body) {
    add('body:${value.value}', bodyTypeLabel(value), query.copyWith(body: [...query.body]..remove(value)));
  }
  for (final value in query.condition) {
    add(
      'condition:${value.value}',
      vehicleConditionLabel(value),
      query.copyWith(condition: [...query.condition]..remove(value)),
    );
  }
  if (query.damage != DamageFilter.UNDAMAGED) {
    add(
      'damage',
      query.damage == DamageFilter.ALL ? 'Endommagés inclus' : 'Endommagés uniquement',
      query.copyWith(damage: DamageFilter.UNDAMAGED),
    );
  }
  if (query.critAirMax != null) {
    add(
      'critAir',
      query.critAirMax == 0 ? 'Crit’Air 0' : 'Crit’Air ${query.critAirMax} ou mieux',
      query.copyWith(critAirMax: null),
    );
  }
  for (final value in query.leasingType) {
    add(
      'leasingType:${value.value}',
      leasingTypeLabel(value),
      query.copyWith(leasingType: [...query.leasingType]..remove(value)),
    );
  }
  final monthly = _range(query.monthlyMin, query.monthlyMax, formatInteger, ' €/mois');
  if (monthly != null) {
    add('monthly', monthly, query.copyWith(monthlyMin: null, monthlyMax: null));
  }
  final duration = _range(query.durationMin, query.durationMax, (value) => '$value', ' mois');
  if (duration != null) {
    add('duration', duration, query.copyWith(durationMin: null, durationMax: null));
  }
  final allowance = _range(query.allowanceMin, null, formatInteger, ' km/an');
  if (allowance != null) {
    add('allowance', allowance, query.copyWith(allowanceMin: null));
  }
  if (query.location != null) {
    add('location', query.location!, query.copyWith(location: null));
  }
  if (query.goodDeals) {
    add('goodDeals', 'Bonnes affaires', query.copyWith(goodDeals: false));
  }
  return filters;
}

String? _range(int? min, int? max, String Function(int) format, String unit) {
  if (min != null && max != null) {
    return '${format(min)} – ${format(max)}$unit';
  }
  if (min != null) {
    return 'À partir de ${format(min)}$unit';
  }
  if (max != null) {
    return 'Jusqu’à ${format(max)}$unit';
  }
  return null;
}
