import 'package:autox/features/listing/widgets/listing_tags.dart';
import 'package:autox_api/autox_api.dart' show DamageType, DamageView;
import 'package:flutter_test/flutter_test.dart';

void main() {
  test('the damage notice lists what was declared, then whether the car runs', () {
    expect(DamageTag.factsOf(DamageView(types: [DamageType.BODYWORK, DamageType.HAIL], running: true)),
        'Carrosserie, Grêle · Roulant');
    expect(DamageTag.factsOf(DamageView(running: false)), 'Non roulant');
    expect(DamageTag.factsOf(DamageView()), isEmpty);
  });

  test('the image cache key ignores the signature in the query string', () {
    expect(
      imageCacheKey('https://bucket.example/listings/1/2/card.jpg?X-Amz-Signature=abc&X-Amz-Date=1'),
      'https://bucket.example/listings/1/2/card.jpg',
    );
    expect(imageCacheKey('https://cdn.example/a.jpg'), 'https://cdn.example/a.jpg');
  });
}
