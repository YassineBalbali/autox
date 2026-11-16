import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:material_ui/material_ui.dart';

import '../../../core/api/api_failure.dart';
import '../../../core/auth/session.dart';
import '../../../core/theme/tokens.dart';
import '../../../core/widgets/pending.dart';
import '../account_providers.dart';

/// Whether the signed-in account must verify its address before writing: the API refuses a
/// message (and a report) until then. From the profile when it has been read, which knows about a
/// link clicked since sign-in; from the session's summary until then.
final mustVerifyProvider = Provider.autoDispose<bool>((ref) {
  final session = ref.watch(sessionProvider);
  if (session is! SignedIn) {
    return false;
  }
  final profile = ref.watch(ownProfileProvider).value;
  return profile?.emailVerificationPending ?? session.user.emailVerificationPending ?? false;
});

/// The web's `app-email-verification-notice`: what an unverified address stops, in the words of the
/// place it is shown ([message]), and "Renvoyer le lien". Shown instead of a form, so nobody
/// writes a message to have it refused.
class VerifyEmailNotice extends ConsumerStatefulWidget {
  const VerifyEmailNotice(this.message, {super.key});

  final String message;

  @override
  ConsumerState<VerifyEmailNotice> createState() => _VerifyEmailNoticeState();
}

class _VerifyEmailNoticeState extends ConsumerState<VerifyEmailNotice> {
  bool _sending = false;
  bool _sent = false;
  String? _failure;

  Future<void> _resend() async {
    setState(() {
      _sending = true;
      _sent = false;
      _failure = null;
    });
    try {
      await ref.read(profileApiProvider).resendEmailVerification();
      if (mounted) {
        setState(() => _sent = true);
      }
    } on Object catch (error) {
      if (mounted) {
        setState(() => _failure = ApiFailure.from(error).message);
      }
    } finally {
      if (mounted) {
        setState(() => _sending = false);
      }
    }
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final session = ref.watch(sessionProvider);
    final address = session is SignedIn ? (session.user.email ?? 'votre adresse') : 'votre adresse';
    return Semantics(
      container: true,
      child: Container(
        padding: const EdgeInsets.all(AppSpace.s3),
        decoration: BoxDecoration(color: AppColors.accentSoft, borderRadius: BorderRadius.circular(AppRadius.md)),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            Text(widget.message, style: theme.textTheme.bodyMedium),
            if (_sent) ...[
              const SizedBox(height: AppSpace.s2),
              Semantics(
                container: true,
                liveRegion: true,
                child: Text(
                  'Nouveau lien envoyé à $address. Il arrive parfois dans les indésirables.',
                  style: theme.textTheme.bodySmall?.copyWith(color: AppColors.success),
                ),
              ),
            ],
            if (_failure != null) ...[
              const SizedBox(height: AppSpace.s2),
              Semantics(
                container: true,
                liveRegion: true,
                child: Text(_failure!, style: theme.textTheme.bodySmall?.copyWith(color: AppColors.danger)),
              ),
            ],
            const SizedBox(height: AppSpace.s2),
            Align(
              alignment: Alignment.centerLeft,
              child: OutlinedButton(
                onPressed: _sending ? null : _resend,
                child: Text(_sending ? 'Envoi…' : 'Renvoyer le lien'),
              ),
            ),
            ColdStartHint(pending: _sending),
          ],
        ),
      ),
    );
  }
}
