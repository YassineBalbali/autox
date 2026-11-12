import 'package:material_ui/material_ui.dart';

import '../theme/tokens.dart';

/// "AutoX": "Auto" in navy, "X" in the accent, at the heavy weight, as the site's logo.
class Wordmark extends StatelessWidget {
  const Wordmark({super.key, this.size = 22});

  final double size;

  @override
  Widget build(BuildContext context) => Semantics(
    label: 'AutoX',
    excludeSemantics: true,
    child: Text.rich(
      TextSpan(
        style: TextStyle(
          fontFamily: AppType.family,
          fontSize: size,
          fontWeight: AppType.heavy,
          height: 1,
          letterSpacing: -0.2,
        ),
        children: const [
          TextSpan(
            text: 'Auto',
            style: TextStyle(color: AppColors.navy),
          ),
          TextSpan(
            text: 'X',
            style: TextStyle(color: AppColors.accent),
          ),
        ],
      ),
    ),
  );
}
