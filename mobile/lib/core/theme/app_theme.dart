import 'package:material_ui/material_ui.dart';

import 'tokens.dart';

/// The site's look as a Material theme: grey page, white surfaces, hairlines instead of
/// shadows, nearly square corners, Barlow Semi Condensed throughout, blue only for what acts.
abstract final class AppTheme {
  static ThemeData light() {
    const scheme = ColorScheme(
      brightness: Brightness.light,
      primary: AppColors.accent,
      onPrimary: AppColors.accentInk,
      primaryContainer: AppColors.accentSoft,
      onPrimaryContainer: AppColors.accentStrong,
      secondary: AppColors.navy,
      onSecondary: AppColors.surface,
      error: AppColors.danger,
      onError: AppColors.surface,
      errorContainer: AppColors.dangerSoft,
      onErrorContainer: AppColors.danger,
      surface: AppColors.surface,
      onSurface: AppColors.ink,
      onSurfaceVariant: AppColors.inkSoft,
      surfaceContainerLowest: AppColors.surface,
      surfaceContainerLow: AppColors.surfaceSunk,
      surfaceContainer: AppColors.page,
      outline: AppColors.lineStrong,
      outlineVariant: AppColors.line,
    );

    const square = RoundedRectangleBorder(borderRadius: BorderRadius.all(Radius.circular(AppRadius.md)));

    return ThemeData(
      colorScheme: scheme,
      fontFamily: AppType.family,
      scaffoldBackgroundColor: AppColors.page,
      textTheme: _textTheme(),
      dividerTheme: const DividerThemeData(color: AppColors.line, thickness: 1, space: 1),
      appBarTheme: const AppBarTheme(
        backgroundColor: AppColors.surface,
        foregroundColor: AppColors.ink,
        surfaceTintColor: Colors.transparent,
        elevation: 0,
        scrolledUnderElevation: 0,
        shape: Border(bottom: BorderSide(color: AppColors.line)),
        titleTextStyle: TextStyle(
          fontFamily: AppType.family,
          fontSize: AppType.tmd,
          fontWeight: AppType.bold,
          color: AppColors.ink,
        ),
      ),
      navigationBarTheme: NavigationBarThemeData(
        backgroundColor: AppColors.surface,
        surfaceTintColor: Colors.transparent,
        indicatorColor: AppColors.accentSoft,
        elevation: 0,
        height: 64,
        labelTextStyle: WidgetStateProperty.resolveWith(
          (states) => TextStyle(
            fontFamily: AppType.family,
            fontSize: AppType.txs,
            fontWeight: states.contains(WidgetState.selected) ? AppType.bold : AppType.semibold,
            color: states.contains(WidgetState.selected) ? AppColors.accent : AppColors.inkSoft,
          ),
        ),
        iconTheme: WidgetStateProperty.resolveWith(
          (states) => IconThemeData(
            size: 22,
            color: states.contains(WidgetState.selected) ? AppColors.accent : AppColors.inkSoft,
          ),
        ),
      ),
      cardTheme: const CardThemeData(
        color: AppColors.surface,
        surfaceTintColor: Colors.transparent,
        elevation: 0,
        margin: EdgeInsets.zero,
        shape: RoundedRectangleBorder(
          side: BorderSide(color: AppColors.line),
          borderRadius: BorderRadius.all(Radius.circular(AppRadius.md)),
        ),
      ),
      filledButtonTheme: FilledButtonThemeData(
        style: FilledButton.styleFrom(
          backgroundColor: AppColors.accent,
          foregroundColor: AppColors.accentInk,
          minimumSize: const Size(kMinTouchTarget, kMinTouchTarget),
          shape: square,
          textStyle: const TextStyle(fontFamily: AppType.family, fontSize: AppType.tbody, fontWeight: AppType.bold),
        ),
      ),
      outlinedButtonTheme: OutlinedButtonThemeData(
        style: OutlinedButton.styleFrom(
          foregroundColor: AppColors.ink,
          minimumSize: const Size(kMinTouchTarget, kMinTouchTarget),
          side: const BorderSide(color: AppColors.lineStrong),
          shape: square,
          textStyle: const TextStyle(fontFamily: AppType.family, fontSize: AppType.tbody, fontWeight: AppType.semibold),
        ),
      ),
      listTileTheme: const ListTileThemeData(
        tileColor: AppColors.surface,
        minVerticalPadding: AppSpace.s2,
        contentPadding: EdgeInsets.symmetric(horizontal: AppSpace.gutter),
        titleTextStyle: TextStyle(
          fontFamily: AppType.family,
          fontSize: AppType.tbody,
          fontWeight: AppType.semibold,
          color: AppColors.ink,
        ),
        subtitleTextStyle: TextStyle(fontFamily: AppType.family, fontSize: AppType.tsm, color: AppColors.inkSoft),
      ),
      progressIndicatorTheme: const ProgressIndicatorThemeData(color: AppColors.accent),
    );
  }

  static TextTheme _textTheme() {
    TextStyle style(double size, FontWeight weight, double height, [Color color = AppColors.ink]) =>
        TextStyle(fontSize: size, fontWeight: weight, height: height, color: color);

    return TextTheme(
      headlineMedium: style(AppType.t2xl, AppType.heavy, AppType.lhTight), // the price on a listing
      headlineSmall: style(AppType.txl, AppType.bold, AppType.lhTight), // page titles
      titleLarge: style(AppType.tlg, AppType.bold, AppType.lhTight), // section titles
      titleMedium: style(AppType.tmd, AppType.bold, AppType.lhSnug), // row and panel titles
      titleSmall: style(AppType.tbody, AppType.semibold, AppType.lhSnug),
      bodyLarge: style(AppType.tbody, AppType.regular, AppType.lhBody),
      bodyMedium: style(AppType.tbody, AppType.regular, AppType.lhBody),
      bodySmall: style(AppType.tsm, AppType.regular, AppType.lhSnug, AppColors.inkSoft), // spec lines
      labelLarge: style(AppType.tbody, AppType.semibold, AppType.lhSnug),
      labelMedium: style(AppType.txs, AppType.semibold, AppType.lhSnug, AppColors.inkSoft),
      labelSmall: style(AppType.t2xs, AppType.regular, AppType.lhSnug, AppColors.inkFaint), // units, legal
    );
  }
}
