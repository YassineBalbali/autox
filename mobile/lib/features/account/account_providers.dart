import 'package:autox_api/autox_api.dart' show ProfileApi, UserProfileResponse;
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../core/api/api_failure.dart';
import '../../core/api/api_providers.dart';

final profileApiProvider = Provider<ProfileApi>((ref) => ref.watch(autoxApiProvider).getProfileApi());

/// The signed-in account, read fresh: whether the address has been verified since sign-in is
/// only known from here.
final ownProfileProvider = FutureProvider.autoDispose<UserProfileResponse>((ref) async {
  try {
    final response = await ref.watch(profileApiProvider).getOwnProfile();
    return response.data!;
  } on Object catch (error) {
    throw ApiFailure.from(error);
  }
});
