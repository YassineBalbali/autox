import 'package:autox_api/autox_api.dart' show ForgotPasswordRequest;
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:material_ui/material_ui.dart';

import '../../core/api/api_failure.dart';
import '../../core/api/api_providers.dart';
import '../../core/forms/validation.dart';
import 'widgets/form_parts.dart';

/// "Mot de passe oublié": asks for the reset link. The new password is chosen on the website,
/// from the link in the e-mail; the app has no page for it (no App Links in this version).
class ForgotPasswordScreen extends ConsumerStatefulWidget {
  const ForgotPasswordScreen({super.key});

  @override
  ConsumerState<ForgotPasswordScreen> createState() => _ForgotPasswordScreenState();
}

class _ForgotPasswordScreenState extends ConsumerState<ForgotPasswordScreen> {
  final _email = TextEditingController();
  bool _submitted = false;
  bool _pending = false;
  bool _sent = false;
  String? _failure;

  @override
  void dispose() {
    _email.dispose();
    super.dispose();
  }

  String? get _emailError => _submitted ? validateEmail(_email.text) : null;

  Future<void> _submit() async {
    setState(() {
      _submitted = true;
      _failure = null;
    });
    if (_emailError != null || _pending) {
      return;
    }
    setState(() => _pending = true);
    try {
      await ref
          .read(authenticationApiProvider)
          .forgotPassword(forgotPasswordRequest: ForgotPasswordRequest(email: _email.text.trim()));
      if (mounted) {
        setState(() => _sent = true);
      }
    } on Object catch (error) {
      if (mounted) {
        setState(() => _failure = ApiFailure.from(error).message);
      }
    } finally {
      if (mounted) {
        setState(() => _pending = false);
      }
    }
  }

  @override
  Widget build(BuildContext context) => FormPage(
    title: 'Mot de passe oublié',
    children: [
      // The form gives way to the answer: a second request would be throttled and pointless.
      if (_sent) ...[
        const FormNotice(
          'Si un compte AutoX existe avec cette adresse, un lien de réinitialisation vient de lui être envoyé. '
          'Le lien est valable 30 minutes et ne fonctionne qu’une fois.',
        ),
        Text(
          'Rien reçu ? Vérifiez vos courriers indésirables, puis que l’adresse saisie est bien celle de votre '
          'compte. Par sécurité, nous ne pouvons pas vous dire si une adresse est associée à un compte.',
          style: Theme.of(context).textTheme.bodySmall,
        ),
      ] else ...[
        FormTextField(
          field: 'email',
          controller: _email,
          error: _emailError,
          enabled: !_pending,
          keyboardType: TextInputType.emailAddress,
          textInputAction: TextInputAction.done,
          autofillHints: const [AutofillHints.email],
          onSubmitted: (_) => _submit(),
        ),
        FormError(_failure),
        SubmitButton(label: 'Envoyer le lien', pendingLabel: 'Envoi…', pending: _pending, onPressed: _submit),
      ],
      TextButton(onPressed: () => context.pop(), child: const Text('Retour à la connexion')),
    ],
  );
}
