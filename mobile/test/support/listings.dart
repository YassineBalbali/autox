/// Listings as the API sends them, for tests.
Map<String, Object?> listingJson({
  String id = 'l1',
  String make = 'Peugeot',
  String model = '3008',
  String version = '1.5 BlueHDi 130 Allure',
  int? priceAmount = 2249000,
  int year = 2020,
  int mileageKm = 48000,
  String fuelType = 'DIESEL',
  String gearbox = 'AUTOMATIC',
  String city = 'Lyon',
  String postalCode = '69003',
  String sellerRole = 'PROFESSIONNEL',
  String? priceLevel,
  Map<String, Object?>? damage,
  Map<String, Object?>? leasing,
  bool soldNew = false,
  int photoCount = 0,
}) => {
  'id': id,
  'title': '$make $model',
  'priceAmount': priceAmount,
  'currencyCode': 'EUR',
  'make': make,
  'model': model,
  'version': version,
  'firstRegistrationYear': year,
  'mileageKm': mileageKm,
  'fuelType': fuelType,
  'gearbox': gearbox,
  'countryCode': 'FR',
  'city': city,
  'postalCode': postalCode,
  'seller': {'id': 's1', 'displayName': 'Garage du Centre', 'role': sellerRole},
  'photoCount': photoCount,
  'priceIndicator': ?(priceLevel == null
      ? null
      : {'level': priceLevel, 'medianPriceAmount': 2400000, 'comparableCount': 12}),
  'damage': ?damage,
  'leasing': ?leasing,
  'soldNew': soldNew,
};

/// A page of results.
Map<String, Object?> pageJson(List<Map<String, Object?>> items, {String? nextCursor}) => {
  'items': items,
  'hasMore': nextCursor != null,
  'nextCursor': ?nextCursor,
  'sort': 'NEWEST',
  'size': 24,
};

const makesJson = [
  {'id': 'm-vw', 'slug': 'volkswagen', 'name': 'Volkswagen', 'popular': false},
  {'id': 'm-peugeot', 'slug': 'peugeot', 'name': 'Peugeot', 'popular': true},
  {'id': 'm-alpine', 'slug': 'alpine', 'name': 'Alpine', 'popular': false},
];

const peugeotModelsJson = [
  {'id': 'mo-3008', 'slug': '3008', 'name': '3008'},
  {'id': 'mo-208', 'slug': '208', 'name': '208'},
];
