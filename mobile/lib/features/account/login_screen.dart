import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:material_ui/material_ui.dart';

import '../../core/api/api_failure.dart';
import '../../core/auth/session.dart';
import '../../core/forms/validation.dart';
import '../../core/theme/tokens.dart';
import 'account_locations.dart';
import 'forgot_password_screen.dart';
import 'register_screen.dart';
import 'widgets/form_parts.dart';

/// "Se connecter": the web's sign-in form. An unverified address may sign in (the API allows
/// it, docs/auth-decisions.md); the account then says what it cannot do yet.
class LoginScreen extends ConsumerStatefulWidget {
  const LoginScreen({super.key, this.returnTo});

  /// Signing in on the way somewhere (writing to a seller, reporting): once signed in, back there
  /// rather than to the account ([leaveForReturn]).
  final String? returnTo;

  @override
  ConsumerState<LoginScreen> createState() => _LoginScreenState();
}

class _LoginScreenState extends ConsumerState<LoginScreen> {
  final _email = TextEditingController();
  final _password = TextEditingController();
  bool _submitted = false;
  bool _pending = false;
  String? _failure;

  @override
  void dispose() {
    _email.dispose();
    _password.dispose();
    super.dispose();
  }

  String? get _emailError => _submitted ? validateEmail(_email.text) : null;
  String? get _passwordError => _submitted ? (_password.text.trim().isEmpty ? messageForKey('required') : null) : null;

  Future<void> _submit() async {
    setState(() {
      _submitted = true;
      _failure = null;
    });
    if (_emailError != null || _passwordError != null || _pending) {
      return;
    }
    setState(() => _pending = true);
    try {
      await ref.read(sessionProvider.notifier).login(email: _email.text, password: _password.text);
      // Signed in: back to where signing in was asked for, or the router takes the account's place.
      if (mounted) {
        leaveForReturn(context, widget.returnTo);
      }
    } on ApiFailure catch (failure) {
      if (mounted) {
        setState(() => _failure = failure.message);
      }
    } finally {
      if (mounted) {
        setState(() => _pending = false);
      }
    }
  }

  /// Registering instead, on the same way back: registered, this form leaves too.
  Future<void> _register() async {
    final back = widget.returnTo;
    if (back == null) {
      await context.push(AccountLocations.register);
      return;
    }
    // On the way somewhere: registration as a page over this one, and back through here.
    final registered = await Navigator.of(context)
        .push<bool>(MaterialPageRoute(builder: (_) => RegisterScreen(returnTo: back)));
    if (registered == true && mounted) {
      leaveForReturn(context, back);
    }
  }

  Future<void> _forgotPassword() async {
    if (widget.returnTo == null) {
      await context.push(AccountLocations.forgotPassword);
    } else {
      await Navigator.of(context).push<void>(MaterialPageRoute(builder: (_) => const ForgotPasswordScreen()));
    }
  }

  @override
  Widget build(BuildContext context) => FormPage(
    title: 'Se connecter',
    children: [
      AutofillGroup(
        child: Column(
          children: [
            FormTextField(
              field: 'email',
              controller: _email,
              error: _emailError,
              enabled: !_pending,
              keyboardType: TextInputType.emailAddress,
              autofillHints: const [AutofillHints.email, AutofillHints.username],
            ),
            const SizedBox(height: AppSpace.s3),
            PasswordField(
              controller: _password,
              error: _passwordError,
              enabled: !_pending,
              textInputAction: TextInputAction.done,
              onSubmitted: (_) => _submit(),
            ),
          ],
        ),
      ),
      FormError(_failure),
      SubmitButton(label: 'Se connecter', pendingLabel: 'Connexion…', pending: _pending, onPressed: _submit),
      TextButton(onPressed: _pending ? null : _forgotPassword, child: const Text('Mot de passe oublié ?')),
      OutlinedButton(onPressed: _pending ? null : _register, child: const Text('Créer un compte')),
    ],
  );
}
