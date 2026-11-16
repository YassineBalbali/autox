import 'dart:async';

import 'package:material_ui/material_ui.dart';

import '../labels/app_labels.dart';
import '../theme/tokens.dart';

/// How long a request may take before the person is told the server may be waking up.
const coldStartDelay = Duration(seconds: 5);

/// Under a button whose request is on its way: nothing for five seconds, then "Le serveur
/// démarre…". Most requests answer well before; a cold start of the free server takes up to a
/// minute, and a bare spinner for that long looks like a broken app.
class ColdStartHint extends StatefulWidget {
  const ColdStartHint({super.key, required this.pending});

  final bool pending;

  @override
  State<ColdStartHint> createState() => _ColdStartHintState();
}

class _ColdStartHintState extends State<ColdStartHint> {
  Timer? _timer;
  bool _slow = false;

  @override
  void initState() {
    super.initState();
    _sync();
  }

  @override
  void didUpdateWidget(ColdStartHint old) {
    super.didUpdateWidget(old);
    if (old.pending != widget.pending) {
      _sync();
    }
  }

  void _sync() {
    _timer?.cancel();
    _slow = false;
    if (widget.pending) {
      _timer = Timer(coldStartDelay, () {
        if (mounted) {
          setState(() => _slow = true);
        }
      });
    }
  }

  @override
  void dispose() {
    _timer?.cancel();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    if (!widget.pending || !_slow) {
      return const SizedBox.shrink();
    }
    return Padding(
      padding: const EdgeInsets.only(top: AppSpace.s2),
      child: Semantics(
        container: true,
        liveRegion: true,
        child: Text(AppLabels.coldStart, style: Theme.of(context).textTheme.bodySmall, textAlign: TextAlign.center),
      ),
    );
  }
}

/// A form's main button: disabled while its request is on its way, its label then saying so
/// ("Connexion…"), and the cold-start hint under it.
class SubmitButton extends StatelessWidget {
  const SubmitButton({
    super.key,
    required this.label,
    required this.pendingLabel,
    required this.pending,
    required this.onPressed,
  });

  final String label;
  final String pendingLabel;
  final bool pending;
  final VoidCallback? onPressed;

  @override
  Widget build(BuildContext context) => Column(
    crossAxisAlignment: CrossAxisAlignment.stretch,
    children: [
      FilledButton(onPressed: pending ? null : onPressed, child: Text(pending ? pendingLabel : label)),
      ColdStartHint(pending: pending),
    ],
  );
}

/// A whole-screen wait (a session being checked at launch), with the same hint.
class PendingView extends StatelessWidget {
  const PendingView({super.key, this.label = 'Chargement…'});

  final String label;

  @override
  Widget build(BuildContext context) => Center(
    child: Padding(
      padding: const EdgeInsets.all(AppSpace.s5),
      child: Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          Semantics(
            container: true,
            label: label,
            child: const SizedBox.square(dimension: 28, child: CircularProgressIndicator(strokeWidth: 3)),
          ),
          const ColdStartHint(pending: true),
        ],
      ),
    ),
  );
}
