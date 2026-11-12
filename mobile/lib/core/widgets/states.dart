import 'package:lucide_icons_flutter/lucide_icons.dart';
import 'package:material_ui/material_ui.dart';

import '../api/api_failure.dart';
import '../theme/tokens.dart';

/// A request in flight, for a whole screen or section.
class LoadingView extends StatelessWidget {
  const LoadingView({super.key, this.label = 'Chargement…'});

  /// Read by TalkBack; nothing is shown but the spinner.
  final String label;

  @override
  Widget build(BuildContext context) => Center(
    child: Padding(
      padding: const EdgeInsets.all(AppSpace.s5),
      child: Semantics(
        label: label,
        child: const SizedBox.square(dimension: 28, child: CircularProgressIndicator(strokeWidth: 3)),
      ),
    ),
  );
}

/// A failed request: what happened, in French, and a way to try again when that could help.
class FailureView extends StatelessWidget {
  const FailureView({super.key, required this.error, this.onRetry});

  /// Anything a provider threw; turned into an [ApiFailure] for its wording.
  final Object error;
  final VoidCallback? onRetry;

  @override
  Widget build(BuildContext context) {
    final failure = ApiFailure.from(error);
    final theme = Theme.of(context);
    return Center(
      child: Padding(
        padding: const EdgeInsets.all(AppSpace.s5),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            const Icon(LucideIcons.circleAlert, size: 32, color: AppColors.inkFaint),
            const SizedBox(height: AppSpace.s3),
            Text(failure.message, style: theme.textTheme.bodyMedium, textAlign: TextAlign.center),
            if (onRetry != null && failure.retryable) ...[
              const SizedBox(height: AppSpace.s4),
              OutlinedButton.icon(
                onPressed: onRetry,
                icon: const Icon(LucideIcons.rotateCw, size: 18),
                label: const Text('Réessayer'),
              ),
            ],
          ],
        ),
      ),
    );
  }
}

/// A tab whose screens come in a later milestone. Says so plainly rather than looking broken.
class ComingSoonView extends StatelessWidget {
  const ComingSoonView({super.key, required this.icon, required this.title, required this.body});

  final IconData icon;
  final String title;
  final String body;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return Center(
      child: Padding(
        padding: const EdgeInsets.all(AppSpace.s5),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            Icon(icon, size: 36, color: AppColors.inkFaint),
            const SizedBox(height: AppSpace.s3),
            Text(title, style: theme.textTheme.titleMedium, textAlign: TextAlign.center),
            const SizedBox(height: AppSpace.s1),
            Text(body, style: theme.textTheme.bodySmall, textAlign: TextAlign.center),
          ],
        ),
      ),
    );
  }
}
