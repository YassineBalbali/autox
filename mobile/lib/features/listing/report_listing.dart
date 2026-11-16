import 'dart:async';

import 'package:autox_api/autox_api.dart'
    show ErrorCode, ListingReportReason, PublicListingDetail, ReportListingRequest;
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:lucide_icons_flutter/lucide_icons.dart';
import 'package:material_ui/material_ui.dart';

import '../../core/api/api_failure.dart';
import '../../core/auth/session.dart';
import '../account/account_locations.dart';
import '../messages/messaging_providers.dart';
import '../messages/messaging_text.dart';
import '../messages/widgets/report_sheet.dart';

/// Whether the signed-in person has already reported a listing (an earlier visit). A failure
/// reads as "no": the server still refuses a second report, and the sheet then says so.
final listingReportedProvider = FutureProvider.autoDispose.family<bool, String>((ref, id) async {
  if (ref.watch(sessionProvider) is! SignedIn) {
    return false;
  }
  try {
    return (await ref.watch(listingsApiProvider).listingReportStatus(listingId: id)).data?.reported ?? false;
  } on Object {
    return false;
  }
});

/// "Signaler cette annonce", under the description — the web's `app-report-listing`. A listing is
/// reported once per person: the button gives way to a line saying so, whether the report was made
/// now or on an earlier visit. Not offered to its seller. Signed out, the button signs in first and
/// comes back here with the form open (the web shows it to signed-in visitors only).
class ReportListing extends ConsumerStatefulWidget {
  const ReportListing({super.key, required this.listing, this.openOnArrival = false});

  final PublicListingDetail listing;

  /// Arrived with the form to open (`?signaler=1`, back from signing in on another path).
  final bool openOnArrival;

  @override
  ConsumerState<ReportListing> createState() => _ReportListingState();
}

class _ReportListingState extends ConsumerState<ReportListing> {
  /// Reported from this screen: "Merci…" rather than "Vous avez signalé…".
  bool _justReported = false;
  bool _alreadyReported = false;

  String get _id => widget.listing.id ?? '';

  @override
  void initState() {
    super.initState();
    if (widget.openOnArrival) {
      WidgetsBinding.instance.addPostFrameCallback((_) async {
        if (!mounted || ref.read(sessionProvider) is! SignedIn) {
          return;
        }
        final reported = await ref.read(listingReportedProvider(_id).future);
        if (mounted && !reported) {
          unawaited(_open());
        }
      });
    }
  }

  Future<void> _start() async {
    if (ref.read(sessionProvider) is SignedIn) {
      return _open();
    }
    final signedIn = await signInFirst(context, back: '/annonces/$_id?signaler=1');
    if (signedIn && mounted) {
      final reported = await ref.read(listingReportedProvider(_id).future);
      if (!mounted) {
        return;
      }
      if (reported) {
        setState(() => _alreadyReported = true);
      } else {
        await _open();
      }
    }
  }

  Future<void> _open() async {
    final outcome = await ReportSheet.open<ListingReportReason>(
      context,
      title: 'Signaler cette annonce',
      reasons: listingReportReasons,
      noteMaxLength: listingReportNoteMaxLength,
      hint: 'L’annonce reste en ligne jusqu’à la décision d’un modérateur. Le vendeur ne sait pas qui l’a signalée.',
      send: (reason, note) async {
        try {
          await ref
              .read(listingsApiProvider)
              .reportListing(
                listingId: _id,
                reportListingRequest: ReportListingRequest(reason: reason, note: note),
              );
          return ReportOutcome.reported;
        } on Object catch (error) {
          final failure = ApiFailure.from(error);
          if (failure is ServerFailure && failure.code == ErrorCode.LISTING_ALREADY_REPORTED) {
            return ReportOutcome.alreadyReported;
          }
          throw failure;
        }
      },
    );
    if (outcome != null && mounted) {
      setState(() {
        _justReported = outcome == ReportOutcome.reported;
        _alreadyReported = outcome == ReportOutcome.alreadyReported;
      });
    }
  }

  @override
  Widget build(BuildContext context) {
    final session = ref.watch(sessionProvider);
    if (session is SignedIn && session.user.id == widget.listing.seller?.id) {
      return const SizedBox.shrink();
    }
    final reported = _justReported || _alreadyReported || (ref.watch(listingReportedProvider(_id)).value ?? false);
    if (reported) {
      return Semantics(
        container: true,
        liveRegion: _justReported,
        child: Text(
          _justReported ? 'Merci : l’annonce a été signalée aux modérateurs.' : 'Vous avez signalé cette annonce.',
          style: Theme.of(context).textTheme.bodySmall,
        ),
      );
    }
    return Align(
      alignment: Alignment.centerLeft,
      child: TextButton.icon(
        onPressed: _start,
        icon: const Icon(LucideIcons.flag, size: 16),
        label: const Text('Signaler cette annonce'),
      ),
    );
  }
}
