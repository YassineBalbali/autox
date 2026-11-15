import 'package:autox_api/autox_api.dart' show DeleteAccountRequest, UserRole, UserSummary;
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:lucide_icons_flutter/lucide_icons.dart';
import 'package:material_ui/material_ui.dart';

import '../../core/api/api_failure.dart';
import '../../core/auth/session.dart';
import '../../core/labels/app_labels.dart';
import '../../core/labels/labels.g.dart';
import '../../core/theme/tokens.dart';
import '../../core/widgets/pending.dart';
import 'account_providers.dart';
import 'data_export.dart';
import 'widgets/form_parts.dart';

/// The Compte tab, signed in: who, the address's verification, "Mes données", deletion and
/// sign-out. While the stored session is being checked it waits; when it cannot be checked it
/// says so and offers to try again.
class AccountScreen extends ConsumerWidget {
  const AccountScreen({super.key, this.welcome = false});

  /// Just registered: "Vérifiez votre boîte mail" first.
  final bool welcome;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final session = ref.watch(sessionProvider);
    return Scaffold(
      appBar: AppBar(title: const Text('Mon compte'), centerTitle: false),
      body: switch (session) {
        SignedIn(:final user) => _Account(user: user, welcome: welcome),
        SessionUnavailable(:final failure) => _Unavailable(failure: failure),
        // A moment, while the router takes a signed-out person to the sign-in form.
        AuthUnknown() || SignedOut() => const PendingView(label: 'Vérification de la session'),
      },
    );
  }
}

class _Unavailable extends ConsumerWidget {
  const _Unavailable({required this.failure});

  final ApiFailure failure;

  @override
  Widget build(BuildContext context, WidgetRef ref) => Center(
    child: Padding(
      padding: const EdgeInsets.all(AppSpace.s5),
      child: Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          const Icon(LucideIcons.cloudOff, size: 36, color: AppColors.inkFaint),
          const SizedBox(height: AppSpace.s3),
          Text(failure.message, textAlign: TextAlign.center),
          const SizedBox(height: AppSpace.s1),
          Text(AppLabels.sessionUnavailable, textAlign: TextAlign.center, style: Theme.of(context).textTheme.bodySmall),
          const SizedBox(height: AppSpace.s4),
          OutlinedButton(
            onPressed: () => ref.read(sessionProvider.notifier).retryRestore(),
            child: const Text('Réessayer'),
          ),
        ],
      ),
    ),
  );
}

class _Account extends ConsumerStatefulWidget {
  const _Account({required this.user, required this.welcome});

  final UserSummary user;
  final bool welcome;

  @override
  ConsumerState<_Account> createState() => _AccountState();
}

class _AccountState extends ConsumerState<_Account> {
  bool _resending = false;
  String? _resent;
  String? _resendFailure;
  bool _signingOut = false;
  bool _exporting = false;
  String? _exportFailure;

  Future<void> _resend(String email) async {
    setState(() {
      _resending = true;
      _resendFailure = null;
    });
    try {
      await ref.read(profileApiProvider).resendEmailVerification();
      if (mounted) {
        setState(() => _resent = email);
      }
    } on Object catch (error) {
      if (mounted) {
        setState(() => _resendFailure = ApiFailure.from(error).message);
      }
    } finally {
      if (mounted) {
        setState(() => _resending = false);
      }
    }
  }

  /// "Mes données": the export, through the share sheet. Nothing to say on success: the sheet is
  /// the answer.
  Future<void> _export() async {
    if (_exporting) {
      return;
    }
    setState(() {
      _exporting = true;
      _exportFailure = null;
    });
    try {
      await ref.read(dataExportProvider)();
    } on Object catch (error) {
      if (mounted) {
        setState(() => _exportFailure = ApiFailure.from(error).message);
      }
    } finally {
      if (mounted) {
        setState(() => _exporting = false);
      }
    }
  }

  Future<void> _signOut() async {
    setState(() => _signingOut = true);
    await ref.read(sessionProvider.notifier).logout();
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final profile = ref.watch(ownProfileProvider).value;
    final user = widget.user;
    final email = profile?.email ?? user.email ?? '';
    final verified = profile?.emailVerified ?? user.emailVerified ?? false;
    final pending = profile?.emailVerificationPending ?? user.emailVerificationPending ?? false;
    final role = profile?.role ?? user.role;

    return ListView(
      padding: const EdgeInsets.only(bottom: AppSpace.s6),
      children: [
        Container(
          color: AppColors.surface,
          padding: const EdgeInsets.fromLTRB(AppSpace.gutter, AppSpace.s4, AppSpace.gutter, AppSpace.s4),
          child: Row(
            children: [
              const CircleAvatar(
                radius: 24,
                backgroundColor: AppColors.accentSoft,
                child: Icon(LucideIcons.userRound, color: AppColors.accent),
              ),
              const SizedBox(width: AppSpace.s3),
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Semantics(
                      container: true,
                      header: true,
                      child: Text(profile?.displayName ?? user.displayName ?? '', style: theme.textTheme.titleLarge),
                    ),
                    Semantics(container: true, child: Text(email, style: theme.textTheme.bodySmall)),
                    if (role != null && role != UserRole.unknownDefaultOpenApi)
                      Semantics(
                        container: true,
                        child: Text('Compte ${userRoleLabel(role).toLowerCase()}', style: theme.textTheme.labelMedium),
                      ),
                  ],
                ),
              ),
            ],
          ),
        ),
        Padding(
          padding: const EdgeInsets.fromLTRB(AppSpace.gutter, AppSpace.s4, AppSpace.gutter, 0),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              if (widget.welcome && !verified)
                FormNotice(
                  'Un lien de vérification a été envoyé à $email. Cliquez dessus pour pouvoir publier une annonce '
                  'et envoyer des messages.',
                  title: 'Vérifiez votre boîte mail',
                ),
              if (!verified) ...[
                _VerifyBanner(
                  pending: pending,
                  resent: _resent,
                  failure: _resendFailure,
                  resending: _resending,
                  onResend: () => _resend(email),
                ),
                const SizedBox(height: AppSpace.s3),
              ],
            ],
          ),
        ),
        Material(
          color: AppColors.surface,
          child: Column(
            children: [
              ListTile(
                leading: const Icon(LucideIcons.fileDown),
                title: Text(_exporting ? 'Préparation…' : 'Mes données'),
                subtitle: const Text(AppLabels.myDataHint),
                trailing: const Icon(LucideIcons.share2, size: 18),
                enabled: !_exporting,
                onTap: _export,
              ),
              if (_exportFailure != null || _exporting)
                Padding(
                  padding: const EdgeInsets.fromLTRB(AppSpace.gutter, 0, AppSpace.gutter, AppSpace.s2),
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.stretch,
                    children: [
                      if (_exportFailure != null) FormError(_exportFailure),
                      ColdStartHint(pending: _exporting),
                    ],
                  ),
                ),
              const Divider(),
              ListTile(
                leading: const Icon(LucideIcons.trash2, color: AppColors.danger),
                title: const Text('Supprimer mon compte', style: TextStyle(color: AppColors.danger)),
                onTap: () => showDialog<void>(context: context, builder: (_) => const DeleteAccountDialog()),
              ),
              const Divider(),
              ListTile(
                leading: const Icon(LucideIcons.logOut),
                title: Text(_signingOut ? 'Déconnexion…' : 'Se déconnecter'),
                enabled: !_signingOut,
                onTap: _signOut,
              ),
            ],
          ),
        ),
      ],
    );
  }
}

/// "E-mail non vérifié", in the web's words, with "Renvoyer le lien".
class _VerifyBanner extends StatelessWidget {
  const _VerifyBanner({
    required this.pending,
    required this.resent,
    required this.failure,
    required this.resending,
    required this.onResend,
  });

  final bool pending;
  final String? resent;
  final String? failure;
  final bool resending;
  final VoidCallback onResend;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return Semantics(
      container: true,
      child: Container(
        padding: const EdgeInsets.all(AppSpace.s3),
        decoration: BoxDecoration(color: AppColors.warnSoft, borderRadius: BorderRadius.circular(AppRadius.md)),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            Text(
              pending
                  ? 'E-mail non vérifié. Tant que vous n’avez pas cliqué sur le lien que nous vous avons envoyé, vous '
                        'pouvez parcourir les annonces et garder des favoris, mais pas publier d’annonce ni envoyer de '
                        'message.'
                  : 'E-mail non vérifié. Demandez un lien, puis cliquez dessus pour vérifier votre adresse.',
              style: theme.textTheme.bodyMedium,
            ),
            if (resent != null) ...[
              const SizedBox(height: AppSpace.s2),
              Semantics(
                container: true,
                liveRegion: true,
                child: Text(
                  'Nouveau lien envoyé à $resent. Il arrive parfois dans les indésirables.',
                  style: theme.textTheme.bodySmall,
                ),
              ),
            ],
            if (failure != null) ...[
              const SizedBox(height: AppSpace.s2),
              Semantics(
                container: true,
                liveRegion: true,
                child: Text(failure!, style: theme.textTheme.bodySmall?.copyWith(color: AppColors.danger)),
              ),
            ],
            const SizedBox(height: AppSpace.s2),
            Align(
              alignment: Alignment.centerLeft,
              child: OutlinedButton(
                onPressed: resending ? null : onResend,
                child: Text(resending ? 'Envoi…' : 'Renvoyer le lien'),
              ),
            ),
            ColdStartHint(pending: resending),
          ],
        ),
      ),
    );
  }
}

/// "Supprimer mon compte": what goes and what stays, in the web's words, then the password.
class DeleteAccountDialog extends ConsumerStatefulWidget {
  const DeleteAccountDialog({super.key});

  @override
  ConsumerState<DeleteAccountDialog> createState() => _DeleteAccountDialogState();
}

class _DeleteAccountDialogState extends ConsumerState<DeleteAccountDialog> {
  final _password = TextEditingController();
  bool _submitted = false;
  bool _pending = false;
  String? _failure;

  @override
  void dispose() {
    _password.dispose();
    super.dispose();
  }

  Future<void> _delete() async {
    setState(() {
      _submitted = true;
      _failure = null;
    });
    if (_password.text.isEmpty || _pending) {
      return;
    }
    setState(() => _pending = true);
    final navigator = Navigator.of(context);
    final messenger = ScaffoldMessenger.of(context);
    try {
      await ref
          .read(profileApiProvider)
          .deleteAccount(deleteAccountRequest: DeleteAccountRequest(password: _password.text));
      // Every session was ended by the server: nothing to revoke, only the phone to clear.
      await ref.read(sessionProvider.notifier).forget();
      navigator.pop();
      messenger.showSnackBar(const SnackBar(content: Text('Votre compte a été supprimé')));
    } on Object catch (error) {
      if (mounted) {
        setState(() {
          _pending = false;
          _failure = ApiFailure.from(error).message;
        });
      }
    }
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return AlertDialog(
      title: const Text('Supprimer mon compte'),
      scrollable: true,
      content: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        mainAxisSize: MainAxisSize.min,
        children: [
          Text(
            'La suppression est définitive. Elle ne peut pas être annulée.',
            style: theme.textTheme.bodyMedium?.copyWith(fontWeight: AppType.bold),
          ),
          const SizedBox(height: AppSpace.s2),
          Text('Ce qui est effacé', style: theme.textTheme.titleSmall),
          for (final line in const [
            'votre profil : adresse e-mail, nom, téléphone, et les informations de votre entreprise ;',
            'vos annonces et leurs photos ;',
            'vos recherches enregistrées et leurs alertes ;',
            'vos sessions : vous serez déconnecté de tous vos appareils.',
          ])
            Text('• $line', style: theme.textTheme.bodySmall),
          const SizedBox(height: AppSpace.s2),
          Text('Ce qui reste, sans rien qui vous identifie', style: theme.textTheme.titleSmall),
          Text(
            '• les décisions de modération qui vous concernaient, sans votre nom ni votre adresse.',
            style: theme.textTheme.bodySmall,
          ),
          const SizedBox(height: AppSpace.s2),
          Text('Un e-mail de confirmation est envoyé à votre adresse.', style: theme.textTheme.bodySmall),
          const SizedBox(height: AppSpace.s3),
          PasswordField(
            controller: _password,
            label: 'Votre mot de passe, pour confirmer',
            enabled: !_pending,
            error: _submitted && _password.text.isEmpty ? validationMessages['required'] : null,
            textInputAction: TextInputAction.done,
            onSubmitted: (_) => _delete(),
          ),
          if (_failure != null) ...[const SizedBox(height: AppSpace.s2), FormError(_failure)],
          ColdStartHint(pending: _pending),
        ],
      ),
      actions: [
        TextButton(onPressed: _pending ? null : () => Navigator.of(context).pop(), child: const Text('Annuler')),
        FilledButton(
          style: FilledButton.styleFrom(backgroundColor: AppColors.danger),
          onPressed: _pending ? null : _delete,
          child: Text(_pending ? 'Suppression…' : 'Supprimer mon compte'),
        ),
      ],
    );
  }
}
