import 'dart:io';
import 'dart:math' as math;

import 'package:autox/core/theme/tokens.dart';
import 'package:flutter/widgets.dart';
import 'package:flutter_test/flutter_test.dart';

/// The app's palette is its own since v1.1, but the brand blue and the status colours are the
/// site's. This reads the site's token file and fails when a shared colour drifts, or when the
/// site gains a colour nobody has decided about. Then it checks the contrast of every text colour
/// on every surface it is drawn on.
void main() {
  // `flutter test` runs from mobile/.
  final scss = File('../frontend/src/styles/_tokens.scss').readAsStringSync();

  Color web(String name) {
    final match = RegExp('--$name:\\s*#([0-9a-fA-F]{6});').firstMatch(scss);
    if (match == null) {
      fail('--$name not found in _tokens.scss, or no longer a #rrggbb colour');
    }
    return Color(int.parse('FF${match.group(1)}', radix: 16));
  }

  /// The same on both sides.
  final shared = <String, Color>{
    'c-accent': AppColors.accent,
    'c-accent-ink': AppColors.accentInk,
    'c-accent-strong': AppColors.accentStrong,
    'c-accent-soft': AppColors.accentSoft,
    'c-surface': AppColors.surface,
    'c-danger': AppColors.danger,
    'c-danger-soft': AppColors.dangerSoft,
    'c-success': AppColors.success,
    'c-success-soft': AppColors.successSoft,
    'c-warn': AppColors.warn,
    'c-warn-soft': AppColors.warnSoft,
    'c-focus': AppColors.focus,
  };

  /// The site's colours the app sets for itself: its neutrals are the launcher icon's black and a
  /// cooler grey, and it has no navy.
  const appsOwn = {
    'c-navy',
    'c-page',
    'c-surface-sunk',
    'c-ink',
    'c-ink-soft',
    'c-ink-faint',
    'c-line',
    'c-line-strong',
  };

  for (final MapEntry(key: name, value: color) in shared.entries) {
    test('--$name matches the site', () {
      expect(color, web(name));
    });
  }

  test('every colour token of the site is either shared or the app’s own', () {
    final webColours = RegExp(r'--(c-[a-z-]+):\s*#').allMatches(scss).map((m) => m.group(1)).toSet();

    expect(webColours, {...shared.keys, ...appsOwn});
  });

  group('contrast, WCAG AA', () {
    double luminance(Color color) {
      double channel(double value) =>
          value <= 0.03928 ? value / 12.92 : math.pow((value + 0.055) / 1.055, 2.4).toDouble();
      return 0.2126 * channel(color.r) + 0.7152 * channel(color.g) + 0.0722 * channel(color.b);
    }

    double ratio(Color a, Color b) {
      final (light, dark) = (math.max(luminance(a), luminance(b)), math.min(luminance(a), luminance(b)));
      return (light + 0.05) / (dark + 0.05);
    }

    // Text colour, the surfaces it sits on.
    final pairs = <(String, Color, List<(String, Color)>)>[
      (
        'ink',
        AppColors.ink,
        [('surface', AppColors.surface), ('page', AppColors.page), ('sunk', AppColors.surfaceSunk)],
      ),
      (
        'inkSoft',
        AppColors.inkSoft,
        [('surface', AppColors.surface), ('page', AppColors.page), ('sunk', AppColors.surfaceSunk)],
      ),
      (
        'inkFaint',
        AppColors.inkFaint,
        [('surface', AppColors.surface), ('page', AppColors.page), ('sunk', AppColors.surfaceSunk)],
      ),
      ('accent', AppColors.accent, [('surface', AppColors.surface), ('page', AppColors.page)]),
      ('accentStrong', AppColors.accentStrong, [('surface', AppColors.surface), ('tint', AppColors.accentSoft)]),
      ('accentInk', AppColors.accentInk, [('accent', AppColors.accent)]),
      ('success', AppColors.success, [('surface', AppColors.surface)]),
      ('warn', AppColors.warn, [('surface', AppColors.surface)]),
      ('danger', AppColors.danger, [('surface', AppColors.surface), ('dangerSoft', AppColors.dangerSoft)]),
      ('surface on scrim', AppColors.surface, [('scrim on ink', AppColors.ink)]),
      // The back, share and heart buttons over a photo: their disc on the darkest photo there is.
      for (final (name, icon) in [('ink', AppColors.ink), ('accent (a saved heart)', AppColors.accent)])
        (
          '$name over a photo',
          icon,
          [('the disc on a black photo', Color.alphaBlend(AppColors.overPhoto, const Color(0xFF000000)))],
        ),
    ];

    for (final (name, text, grounds) in pairs) {
      for (final (groundName, ground) in grounds) {
        test('$name on $groundName is at least 4.5:1', () {
          expect(ratio(text, ground), greaterThanOrEqualTo(4.5));
        });
      }
    }
  });
}
