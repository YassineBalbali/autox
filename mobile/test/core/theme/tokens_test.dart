import 'dart:io';

import 'package:autox/core/theme/tokens.dart';
import 'package:flutter/widgets.dart';
import 'package:flutter_test/flutter_test.dart';

/// The app's colours are a transcription of the site's. This reads the site's token file and
/// fails when the two disagree, so the look cannot drift apart silently.
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

  final pairs = <String, Color>{
    'c-navy': AppColors.navy,
    'c-accent': AppColors.accent,
    'c-accent-ink': AppColors.accentInk,
    'c-accent-strong': AppColors.accentStrong,
    'c-accent-soft': AppColors.accentSoft,
    'c-page': AppColors.page,
    'c-surface': AppColors.surface,
    'c-surface-sunk': AppColors.surfaceSunk,
    'c-ink': AppColors.ink,
    'c-ink-soft': AppColors.inkSoft,
    'c-ink-faint': AppColors.inkFaint,
    'c-line': AppColors.line,
    'c-line-strong': AppColors.lineStrong,
    'c-danger': AppColors.danger,
    'c-danger-soft': AppColors.dangerSoft,
    'c-success': AppColors.success,
    'c-success-soft': AppColors.successSoft,
    'c-warn': AppColors.warn,
    'c-warn-soft': AppColors.warnSoft,
    'c-focus': AppColors.focus,
  };

  for (final MapEntry(key: name, value: color) in pairs.entries) {
    test('--$name matches the site', () {
      expect(color, web(name));
    });
  }

  test('every colour token of the site is transcribed', () {
    final webColours = RegExp(r'--(c-[a-z-]+):\s*#').allMatches(scss).map((m) => m.group(1)).toSet();

    expect(webColours, pairs.keys.toSet());
  });
}
