import 'package:flutter/widgets.dart';

/// The site's design tokens, transcribed from `frontend/src/styles/_tokens.scss`.
///
/// Same names as the CSS custom properties, so a value can be looked up on either side. The
/// colours are checked against that file by `test/core/theme/tokens_test.dart`: change one
/// side and the test fails until the other follows.
///
/// Sizes are logical pixels where the web uses rem at a 16px root, so `--t-body: 0.9375rem`
/// is 15 here. Android scales them with the system font size, as a browser does rem.
abstract final class AppColors {
  // ---- brand: the wordmark's two colours
  static const navy = Color(0xFF0B1F3A); // --c-navy
  static const accent = Color(0xFF0057FF); // --c-accent: prices, primary actions

  static const accentInk = Color(0xFFFFFFFF); // --c-accent-ink
  static const accentStrong = Color(0xFF0043C7); // --c-accent-strong
  static const accentSoft = Color(0xFFE8EFFF); // --c-accent-soft

  // ---- neutrals
  static const page = Color(0xFFF2F3F5); // --c-page
  static const surface = Color(0xFFFFFFFF); // --c-surface
  static const surfaceSunk = Color(0xFFF7F8FA); // --c-surface-sunk

  static const ink = Color(0xFF0B1F3A); // --c-ink
  static const inkSoft = Color(0xFF46546A); // --c-ink-soft
  static const inkFaint = Color(0xFF647082); // --c-ink-faint

  static const line = Color(0xFFDDE1E7); // --c-line
  static const lineStrong = Color(0xFFB8C0CC); // --c-line-strong

  // ---- status: never the accent
  static const danger = Color(0xFFC0262D); // --c-danger
  static const dangerSoft = Color(0xFFFDEDED); // --c-danger-soft
  static const success = Color(0xFF177245); // --c-success
  static const successSoft = Color(0xFFE8F5EE); // --c-success-soft
  static const warn = Color(0xFF8A5A08); // --c-warn
  static const warnSoft = Color(0xFFFDF4E3); // --c-warn-soft

  static const focus = Color(0xFF0057FF); // --c-focus
}

/// `--s-*`: a 4px base; most of the interface lives on 8 and 12.
abstract final class AppSpace {
  static const double s0 = 2;
  static const double s1 = 4;
  static const double s2 = 8;
  static const double s3 = 12;
  static const double s4 = 16;
  static const double s5 = 24;
  static const double s6 = 32;
  static const double s7 = 48;

  /// `--gutter-min`: the page's side margin on a phone.
  static const double gutter = s4;
}

/// `--t-*`, `--lh-*`, `--fw-*`.
abstract final class AppType {
  static const family = 'Barlow Semi Condensed';

  static const double t2xs = 11;
  static const double txs = 12;
  static const double tsm = 13;
  static const double tbody = 15;
  static const double tmd = 17;
  static const double tlg = 20;
  static const double txl = 26;
  static const double t2xl = 32;

  static const double lhTight = 1.15;
  static const double lhSnug = 1.3;
  static const double lhBody = 1.45;

  // The four weights bundled; there is no 500, as on the web.
  static const regular = FontWeight.w400;
  static const semibold = FontWeight.w600;
  static const bold = FontWeight.w700;
  static const heavy = FontWeight.w800; // the wordmark's weight; in the UI, prices only
}

/// `--r-*`: nearly square, except a surface that is itself a way in.
abstract final class AppRadius {
  static const double sm = 2;
  static const double md = 3;
  static const double lg = 8;
}

/// Android's minimum touch target. The web's touch rules use 44 CSS px; Material asks 48 dp.
const double kMinTouchTarget = 48;
