import 'package:flutter/services.dart';
import 'package:lucide_icons_flutter/lucide_icons.dart';
import 'package:material_ui/material_ui.dart';

import '../../../core/labels/labels.g.dart';
import '../../../core/theme/app_theme.dart';
import '../../../core/theme/tokens.dart';
import '../../../core/widgets/surfaces.dart';

export '../../../core/widgets/pending.dart' show SubmitButton;

/// The parts the account forms share. Labels are the site's field names (`fieldLabels`, from
/// `frontend/labels.fr.json`); each field is a labelled text field for TalkBack, its error read
/// with it.

/// A form's text field, labelled with the site's name for [field].
class FormTextField extends StatelessWidget {
  const FormTextField({
    super.key,
    required this.field,
    required this.controller,
    this.error,
    this.keyboardType,
    this.textInputAction = TextInputAction.next,
    this.autofillHints,
    this.hint,
    this.label,
    this.onSubmitted,
    this.enabled = true,
  });

  final String field;
  final TextEditingController controller;
  final String? error;
  final TextInputType? keyboardType;
  final TextInputAction textInputAction;
  final Iterable<String>? autofillHints;
  final String? hint;

  /// Instead of the site's field name, when the form words it differently.
  final String? label;
  final ValueChanged<String>? onSubmitted;
  final bool enabled;

  @override
  Widget build(BuildContext context) => TextField(
    controller: controller,
    enabled: enabled,
    keyboardType: keyboardType,
    textInputAction: textInputAction,
    autofillHints: autofillHints,
    autocorrect: false,
    onSubmitted: onSubmitted,
    decoration: InputDecoration(
      labelText: label ?? fieldLabels[field] ?? field,
      hintText: hint,
      errorText: error,
      errorMaxLines: 3,
    ),
  );
}

/// A password field with its show/hide button.
class PasswordField extends StatefulWidget {
  const PasswordField({
    super.key,
    required this.controller,
    this.field = 'password',
    this.label,
    this.error,
    this.newPassword = false,
    this.textInputAction = TextInputAction.next,
    this.onSubmitted,
    this.enabled = true,
  });

  final TextEditingController controller;
  final String field;
  final String? label;
  final String? error;

  /// A password being chosen (registration) rather than typed: tells the autofill service.
  final bool newPassword;
  final TextInputAction textInputAction;
  final ValueChanged<String>? onSubmitted;
  final bool enabled;

  @override
  State<PasswordField> createState() => _PasswordFieldState();
}

class _PasswordFieldState extends State<PasswordField> {
  bool _visible = false;

  @override
  Widget build(BuildContext context) => TextField(
    controller: widget.controller,
    enabled: widget.enabled,
    obscureText: !_visible,
    enableSuggestions: false,
    autocorrect: false,
    keyboardType: TextInputType.visiblePassword,
    textInputAction: widget.textInputAction,
    onSubmitted: widget.onSubmitted,
    autofillHints: [widget.newPassword ? AutofillHints.newPassword : AutofillHints.password],
    inputFormatters: [LengthLimitingTextInputFormatter(128)],
    decoration: InputDecoration(
      labelText: widget.label ?? fieldLabels[widget.field] ?? widget.field,
      errorText: widget.error,
      errorMaxLines: 3,
      suffixIcon: IconButton(
        tooltip: _visible ? 'Masquer le mot de passe' : 'Afficher le mot de passe',
        icon: Icon(_visible ? LucideIcons.eyeOff : LucideIcons.eye, size: AppSizes.icon),
        onPressed: () => setState(() => _visible = !_visible),
      ),
    ),
  );
}

/// The failure of the last submit, above the button. Read by TalkBack as it appears.
class FormError extends StatelessWidget {
  const FormError(this.message, {super.key});

  final String? message;

  @override
  Widget build(BuildContext context) {
    if (message == null) {
      return const SizedBox.shrink();
    }
    return Semantics(
      container: true,
      liveRegion: true,
      child: Container(
        width: double.infinity,
        margin: const EdgeInsets.only(bottom: AppSpace.s3),
        padding: const EdgeInsets.all(AppSpace.s3),
        decoration: BoxDecoration(color: AppColors.dangerSoft, borderRadius: BorderRadius.circular(AppRadius.md)),
        child: Text(message!, style: Theme.of(context).textTheme.bodyMedium?.copyWith(color: AppColors.danger)),
      ),
    );
  }
}

/// A success or information notice.
class FormNotice extends StatelessWidget {
  const FormNotice(this.message, {super.key, this.title});

  final String? title;
  final String message;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return Semantics(
      container: true,
      liveRegion: true,
      child: Container(
        width: double.infinity,
        margin: const EdgeInsets.only(bottom: AppSpace.s3),
        padding: const EdgeInsets.all(AppSpace.s3),
        decoration: BoxDecoration(color: AppColors.successSoft, borderRadius: BorderRadius.circular(AppRadius.md)),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            if (title != null) ...[
              Text(title!, style: theme.textTheme.titleSmall?.copyWith(fontWeight: AppType.bold)),
              const SizedBox(height: AppSpace.s1),
            ],
            Text(message, style: theme.textTheme.bodyMedium),
          ],
        ),
      ),
    );
  }
}

/// The page around a form: a title, the form on a white rounded card with fully round fields
/// and buttons, scrolling with the keyboard open.
class FormPage extends StatelessWidget {
  const FormPage({super.key, required this.title, required this.children});

  final String title;
  final List<Widget> children;

  @override
  Widget build(BuildContext context) => Scaffold(
    appBar: AppBar(title: Text(title), centerTitle: false),
    body: SafeArea(
      child: Theme(
        data: Theme.of(context).copyWith(inputDecorationTheme: AppTheme.pillFields()),
        child: ListView(
          padding: const EdgeInsets.fromLTRB(AppSpace.gutter, AppSpace.s2, AppSpace.gutter, AppSpace.s6),
          children: [
            AppCard(
              padding: const EdgeInsets.all(AppSpace.s4),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.stretch,
                children: [
                  for (final (index, child) in children.indexed) ...[
                    if (index > 0) const SizedBox(height: AppSpace.s3),
                    child,
                  ],
                ],
              ),
            ),
          ],
        ),
      ),
    ),
  );
}
