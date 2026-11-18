import 'package:autox_api/autox_api.dart' show Country, RegisterRequest, UserRole;
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:material_ui/material_ui.dart';

import '../../core/api/api_failure.dart';
import '../../core/api/api_providers.dart';
import '../../core/auth/session.dart';
import '../../core/forms/validation.dart';
import '../../core/labels/labels.g.dart' show noticeTexts;
import '../../core/labels/linked_text.dart';
import '../../core/links.dart';
import '../../core/theme/tokens.dart';
import 'account_locations.dart';
import 'widgets/form_parts.dart';

/// The countries an account can be opened in; France when the list cannot be read, as on the web.
final _countriesProvider = FutureProvider.autoDispose<List<Country>>((ref) async {
  try {
    final response = await ref.watch(referenceApiProvider).countries();
    final countries = (response.data ?? const <Country>[]).where((country) => country.code != null).toList();
    return countries.isEmpty ? [Country(code: 'FR', name: 'France')] : countries;
  } on Object {
    return [Country(code: 'FR', name: 'France')];
  }
});

/// "Créer un compte", for a private buyer: the web's form, its fields and rules. Professionals
/// register on the website. The account is signed in as soon as it exists (the API returns a
/// token pair), and says to check the mailbox for the verification link.
class RegisterScreen extends ConsumerStatefulWidget {
  const RegisterScreen({super.key, this.returnTo});

  /// Registering on the way somewhere: once the account exists (and is signed in), back there.
  final String? returnTo;

  @override
  ConsumerState<RegisterScreen> createState() => _RegisterScreenState();
}

class _RegisterScreenState extends ConsumerState<RegisterScreen> {
  final _displayName = TextEditingController();
  final _email = TextEditingController();
  final _password = TextEditingController();
  final _confirmation = TextEditingController();
  final _firstName = TextEditingController();
  final _lastName = TextEditingController();
  final _phone = TextEditingController();
  String _country = 'FR';
  bool _submitted = false;
  bool _pending = false;
  String? _failure;

  @override
  void dispose() {
    for (final controller in [_displayName, _email, _password, _confirmation, _firstName, _lastName, _phone]) {
      controller.dispose();
    }
    super.dispose();
  }

  Map<String, String?> get _errors => {
    'displayName': validateDisplayName(_displayName.text),
    'email': validateEmail(_email.text),
    'password': validatePassword(_password.text),
    'passwordConfirmation': validatePasswordConfirmation(_confirmation.text, _password.text),
    'firstName': validateName(_firstName.text),
    'lastName': validateName(_lastName.text),
    'phone': validatePhone(_phone.text.trim()),
  };

  String? _error(String field) => _submitted ? _errors[field] : null;

  String? _blank(TextEditingController controller) => controller.text.trim().isEmpty ? null : controller.text.trim();

  Future<void> _submit() async {
    setState(() {
      _submitted = true;
      _failure = null;
    });
    if (_errors.values.any((error) => error != null) || _pending) {
      return;
    }
    setState(() => _pending = true);
    final router = GoRouter.of(context);
    try {
      await ref
          .read(sessionProvider.notifier)
          .register(
            RegisterRequest(
              email: _email.text.trim(),
              password: _password.text,
              role: UserRole.PARTICULIER,
              displayName: _displayName.text.trim(),
              // Blank optional fields are left out, not sent as "": the API reads "" as "clear".
              firstName: _blank(_firstName),
              lastName: _blank(_lastName),
              phone: _blank(_phone),
              countryCode: _country,
            ),
          );
      if (!mounted || !leaveForReturn(context, widget.returnTo)) {
        router.go(AccountLocations.welcome);
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

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final countries = ref.watch(_countriesProvider).value ?? [Country(code: 'FR', name: 'France')];
    final site = ref.watch(appConfigProvider);
    final open = ref.read(linkOpenerProvider);

    return FormPage(
      title: 'Créer un compte',
      children: [
        Text(
          'Un compte particulier. Pour un compte professionnel, inscrivez-vous sur le site.',
          style: theme.textTheme.bodySmall,
        ),
        AutofillGroup(
          child: Column(
            children: [
              FormTextField(
                field: 'displayName',
                controller: _displayName,
                error: _error('displayName'),
                enabled: !_pending,
              ),
              const SizedBox(height: AppSpace.s3),
              FormTextField(
                field: 'email',
                controller: _email,
                error: _error('email'),
                enabled: !_pending,
                keyboardType: TextInputType.emailAddress,
                autofillHints: const [AutofillHints.email],
              ),
              const SizedBox(height: AppSpace.s3),
              PasswordField(controller: _password, error: _error('password'), enabled: !_pending, newPassword: true),
              const SizedBox(height: AppSpace.s1),
              Align(
                alignment: Alignment.centerLeft,
                child: Text(
                  'Au moins $passwordMinLength caractères. Une phrase est plus sûre et plus facile à retenir '
                  'qu’un mot compliqué.',
                  style: theme.textTheme.labelSmall,
                ),
              ),
              const SizedBox(height: AppSpace.s3),
              PasswordField(
                controller: _confirmation,
                field: 'passwordConfirmation',
                label: 'Confirmer le mot de passe',
                error: _error('passwordConfirmation'),
                enabled: !_pending,
                newPassword: true,
              ),
              const SizedBox(height: AppSpace.s3),
              FormTextField(
                field: 'firstName',
                controller: _firstName,
                error: _error('firstName'),
                enabled: !_pending,
                autofillHints: const [AutofillHints.givenName],
              ),
              const SizedBox(height: AppSpace.s3),
              FormTextField(
                field: 'lastName',
                controller: _lastName,
                error: _error('lastName'),
                enabled: !_pending,
                autofillHints: const [AutofillHints.familyName],
              ),
              const SizedBox(height: AppSpace.s3),
              FormTextField(
                field: 'phone',
                controller: _phone,
                error: _error('phone'),
                enabled: !_pending,
                hint: '+33612345678',
                keyboardType: TextInputType.phone,
                autofillHints: const [AutofillHints.telephoneNumber],
              ),
            ],
          ),
        ),
        DropdownButtonFormField<String>(
          initialValue: _country,
          decoration: const InputDecoration(labelText: 'Pays'),
          items: [
            for (final country in countries)
              DropdownMenuItem(value: country.code, child: Text(country.name ?? country.code!)),
          ],
          onChanged: _pending ? null : (code) => setState(() => _country = code ?? _country),
        ),
        FormError(_failure),
        SubmitButton(
          label: 'Créer mon compte',
          pendingLabel: 'Création du compte…',
          pending: _pending,
          onPressed: _submit,
        ),
        // As on the site, worded in labels.fr.json: creating the account accepts the terms. The
        // links open the site's pages.
        LinkedText(noticeTexts['registerConsent']!, onOpen: (path) => open(site.site(path))),
        TextButton(
          onPressed: _pending ? null : () => context.canPop() ? context.pop() : context.go(AccountLocations.signIn),
          child: const Text('Se connecter'),
        ),
      ],
    );
  }
}
