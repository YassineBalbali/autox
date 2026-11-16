import 'package:material_ui/material_ui.dart';

import '../../../core/api/api_failure.dart';
import '../../../core/theme/tokens.dart';
import '../../../core/widgets/pending.dart';
import '../../account/widgets/form_parts.dart' show FormError;
import '../messaging_text.dart';

/// How a report ended, for the screen that opened the sheet.
enum ReportOutcome {
  /// Sent now.
  reported,

  /// The server already had this person's report (a listing's 409).
  alreadyReported,
}

/// The web's report form, as a bottom sheet: a reason, a note ("Autre" requires one), what will
/// happen, and "Signaler". [send] makes the request and returns how it ended, or throws an
/// [ApiFailure] to show. The sheet closes with the outcome; dismissed, with null.
class ReportSheet<R extends Enum> extends StatefulWidget {
  const ReportSheet({
    super.key,
    required this.title,
    required this.reasons,
    required this.noteMaxLength,
    required this.hint,
    required this.send,
  });

  final String title;
  final List<(R, String)> reasons;
  final int noteMaxLength;
  final String hint;
  final Future<ReportOutcome> Function(R reason, String? note) send;

  /// Opens the sheet over [context].
  static Future<ReportOutcome?> open<R extends Enum>(
    BuildContext context, {
    required String title,
    required List<(R, String)> reasons,
    required int noteMaxLength,
    required String hint,
    required Future<ReportOutcome> Function(R reason, String? note) send,
  }) => showModalBottomSheet<ReportOutcome>(
    context: context,
    isScrollControlled: true,
    showDragHandle: true,
    builder: (_) =>
        ReportSheet<R>(title: title, reasons: reasons, noteMaxLength: noteMaxLength, hint: hint, send: send),
  );

  @override
  State<ReportSheet<R>> createState() => _ReportSheetState<R>();
}

class _ReportSheetState<R extends Enum> extends State<ReportSheet<R>> {
  final _note = TextEditingController();
  R? _reason;
  bool _sending = false;
  String? _failure;

  @override
  void initState() {
    super.initState();
    _note.addListener(() => setState(() {}));
  }

  @override
  void dispose() {
    _note.dispose();
    super.dispose();
  }

  bool get _canSubmit => canSubmitReport(_reason?.name, _note.text) && !_sending;

  Future<void> _submit() async {
    final reason = _reason;
    if (!_canSubmit || reason == null) {
      return;
    }
    setState(() {
      _sending = true;
      _failure = null;
    });
    final navigator = Navigator.of(context);
    try {
      final note = _note.text.trim();
      final outcome = await widget.send(reason, note.isEmpty ? null : note);
      navigator.pop(outcome);
    } on Object catch (error) {
      if (mounted) {
        setState(() {
          _sending = false;
          _failure = ApiFailure.from(error).message;
        });
      }
    }
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return SafeArea(
      child: Padding(
        padding: EdgeInsets.only(bottom: MediaQuery.viewInsetsOf(context).bottom),
        child: SingleChildScrollView(
          padding: const EdgeInsets.fromLTRB(AppSpace.gutter, 0, AppSpace.gutter, AppSpace.s4),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            mainAxisSize: MainAxisSize.min,
            children: [
              Semantics(header: true, child: Text(widget.title, style: theme.textTheme.titleLarge)),
              const SizedBox(height: AppSpace.s2),
              Semantics(
                container: true,
                label: 'Motif du signalement',
                explicitChildNodes: true,
                child: RadioGroup<R>(
                  groupValue: _reason,
                  onChanged: (value) => _sending ? null : setState(() => _reason = value),
                  child: Column(
                    children: [
                      for (final (reason, label) in widget.reasons)
                        RadioListTile<R>(
                          value: reason,
                          title: Text(label),
                          contentPadding: EdgeInsets.zero,
                          enabled: !_sending,
                        ),
                    ],
                  ),
                ),
              ),
              TextField(
                controller: _note,
                enabled: !_sending,
                minLines: 2,
                maxLines: 5,
                maxLength: widget.noteMaxLength,
                decoration: InputDecoration(
                  labelText: 'Précisions',
                  hintText: reportNotePlaceholder(_reason?.name),
                  border: const OutlineInputBorder(),
                ),
              ),
              Text(widget.hint, style: theme.textTheme.bodySmall),
              if (_failure != null) ...[const SizedBox(height: AppSpace.s2), FormError(_failure)],
              const SizedBox(height: AppSpace.s3),
              FilledButton(
                style: FilledButton.styleFrom(backgroundColor: AppColors.danger),
                onPressed: _canSubmit ? _submit : null,
                child: Text(_sending ? 'Envoi…' : 'Signaler'),
              ),
              TextButton(onPressed: _sending ? null : () => Navigator.of(context).pop(), child: const Text('Annuler')),
              ColdStartHint(pending: _sending),
            ],
          ),
        ),
      ),
    );
  }
}
