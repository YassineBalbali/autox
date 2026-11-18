import 'package:material_ui/material_ui.dart';

import 'tokens.dart';

/// The tokens as a Material theme: a light grey page, white rounded cards, near-black text with
/// a strong hierarchy, fully round buttons and chips, blue only for what acts or is selected.
abstract final class AppTheme {
  static ThemeData light() {
    const scheme = ColorScheme(
      brightness: Brightness.light,
      primary: AppColors.accent,
      onPrimary: AppColors.accentInk,
      primaryContainer: AppColors.accentSoft,
      onPrimaryContainer: AppColors.accentStrong,
      secondary: AppColors.ink,
      onSecondary: AppColors.surface,
      secondaryContainer: AppColors.accentSoft,
      onSecondaryContainer: AppColors.accentStrong,
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
      surfaceContainerHighest: AppColors.surfaceSunk,
      outline: AppColors.lineStrong,
      outlineVariant: AppColors.line,
    );

    const pill = StadiumBorder();
    const label = TextStyle(fontFamily: AppType.family, fontSize: AppType.tbody, fontWeight: AppType.bold);

    return ThemeData(
      colorScheme: scheme,
      fontFamily: AppType.family,
      scaffoldBackgroundColor: AppColors.page,
      textTheme: _textTheme(),
      dividerTheme: const DividerThemeData(color: AppColors.line, thickness: 1, space: 1),
      appBarTheme: const AppBarTheme(
        backgroundColor: AppColors.page,
        foregroundColor: AppColors.ink,
        surfaceTintColor: Colors.transparent,
        elevation: 0,
        scrolledUnderElevation: 0,
        titleTextStyle: TextStyle(
          fontFamily: AppType.family,
          fontSize: AppType.tlg,
          fontWeight: AppType.heavy,
          color: AppColors.ink,
        ),
      ),
      navigationBarTheme: NavigationBarThemeData(
        backgroundColor: AppColors.surface,
        surfaceTintColor: Colors.transparent,
        indicatorColor: AppColors.accentSoft,
        indicatorShape: pill,
        elevation: 0,
        height: AppSizes.tabBar,
        labelTextStyle: WidgetStateProperty.resolveWith(
          (states) => TextStyle(
            fontFamily: AppType.family,
            fontSize: AppType.txs,
            fontWeight: states.contains(WidgetState.selected) ? AppType.heavy : AppType.semibold,
            color: states.contains(WidgetState.selected) ? AppColors.accentStrong : AppColors.inkSoft,
          ),
        ),
        iconTheme: WidgetStateProperty.resolveWith(
          (states) => IconThemeData(
            size: AppSizes.icon,
            color: states.contains(WidgetState.selected) ? AppColors.accentStrong : AppColors.inkSoft,
          ),
        ),
      ),
      cardTheme: const CardThemeData(
        color: AppColors.surface,
        surfaceTintColor: Colors.transparent,
        elevation: 0,
        margin: EdgeInsets.zero,
        clipBehavior: Clip.antiAlias,
        shape: RoundedRectangleBorder(borderRadius: BorderRadius.all(Radius.circular(AppRadius.lg))),
      ),
      filledButtonTheme: FilledButtonThemeData(
        style: FilledButton.styleFrom(
          backgroundColor: AppColors.accent,
          foregroundColor: AppColors.accentInk,
          minimumSize: const Size(AppSizes.touch, AppSizes.touch),
          padding: const EdgeInsets.symmetric(horizontal: AppSpace.s5),
          shape: pill,
          textStyle: label,
        ),
      ),
      outlinedButtonTheme: OutlinedButtonThemeData(
        style: OutlinedButton.styleFrom(
          foregroundColor: AppColors.ink,
          backgroundColor: AppColors.surface,
          minimumSize: const Size(AppSizes.touch, AppSizes.touch),
          padding: const EdgeInsets.symmetric(horizontal: AppSpace.s4),
          side: const BorderSide(color: AppColors.lineStrong),
          shape: pill,
          textStyle: label,
        ),
      ),
      textButtonTheme: TextButtonThemeData(
        style: TextButton.styleFrom(
          foregroundColor: AppColors.accent,
          minimumSize: const Size(AppSizes.touch, AppSizes.touch),
          shape: pill,
          textStyle: label,
        ),
      ),
      segmentedButtonTheme: SegmentedButtonThemeData(
        style: ButtonStyle(
          minimumSize: const WidgetStatePropertyAll(Size(AppSizes.touch, AppSizes.touch)),
          shape: const WidgetStatePropertyAll(pill),
          side: const WidgetStatePropertyAll(BorderSide(color: AppColors.lineStrong)),
          textStyle: const WidgetStatePropertyAll(label),
          backgroundColor: WidgetStateProperty.resolveWith(
            (states) => states.contains(WidgetState.selected) ? AppColors.ink : AppColors.surface,
          ),
          foregroundColor: WidgetStateProperty.resolveWith(
            (states) => states.contains(WidgetState.selected) ? AppColors.surface : AppColors.ink,
          ),
        ),
      ),
      chipTheme: ChipThemeData(
        backgroundColor: AppColors.surface,
        selectedColor: AppColors.accentSoft,
        disabledColor: AppColors.surfaceSunk,
        checkmarkColor: AppColors.accentStrong,
        deleteIconColor: AppColors.inkSoft,
        side: WidgetStateBorderSide.resolveWith(
          (states) =>
              BorderSide(color: states.contains(WidgetState.selected) ? AppColors.accent : AppColors.lineStrong),
        ),
        shape: pill,
        // A chosen chip's label is set by the chip itself (AppText.chipSelected): Material's
        // chips do not take it from the theme.
        labelStyle: AppText.chip.copyWith(fontFamily: AppType.family),
        padding: const EdgeInsets.symmetric(horizontal: AppSpace.s2, vertical: AppSpace.s1),
      ),
      inputDecorationTheme: _fields(
        radius: AppRadius.md,
        padding: const EdgeInsets.symmetric(horizontal: AppSpace.s4, vertical: AppSpace.s3),
      ),
      switchTheme: SwitchThemeData(
        trackColor: WidgetStateProperty.resolveWith(
          (states) => states.contains(WidgetState.selected) ? AppColors.accent : null,
        ),
      ),
      bottomSheetTheme: const BottomSheetThemeData(
        backgroundColor: AppColors.surface,
        surfaceTintColor: Colors.transparent,
        showDragHandle: true,
        shape: RoundedRectangleBorder(borderRadius: BorderRadius.vertical(top: Radius.circular(AppRadius.lg))),
      ),
      popupMenuTheme: PopupMenuThemeData(
        color: AppColors.surface,
        surfaceTintColor: Colors.transparent,
        shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(AppRadius.md)),
        textStyle: AppText.body,
      ),
      snackBarTheme: SnackBarThemeData(
        backgroundColor: AppColors.ink,
        behavior: SnackBarBehavior.floating,
        shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(AppRadius.md)),
      ),
      listTileTheme: const ListTileThemeData(
        tileColor: AppColors.surface,
        minVerticalPadding: AppSpace.s2,
        contentPadding: EdgeInsets.symmetric(horizontal: AppSpace.gutter),
        titleTextStyle: TextStyle(
          fontFamily: AppType.family,
          fontSize: AppType.tbody,
          fontWeight: AppType.bold,
          color: AppColors.ink,
        ),
        subtitleTextStyle: TextStyle(fontFamily: AppType.family, fontSize: AppType.tsm, color: AppColors.inkSoft),
      ),
      progressIndicatorTheme: const ProgressIndicatorThemeData(color: AppColors.accent),
    );
  }

  /// The fields of the account's forms (sign-in, registration): fully round, a little taller,
  /// the text further from the curve.
  static InputDecorationThemeData pillFields() => _fields(
    radius: AppRadius.pill,
    padding: const EdgeInsets.symmetric(horizontal: AppSpace.s5, vertical: AppSpace.s4),
  );

  static InputDecorationThemeData _fields({required double radius, required EdgeInsets padding}) {
    final border = OutlineInputBorder(
      borderRadius: BorderRadius.circular(radius),
      borderSide: const BorderSide(color: AppColors.lineStrong),
    );
    return InputDecorationThemeData(
      filled: true,
      fillColor: AppColors.surface,
      hintStyle: AppText.body.copyWith(color: AppColors.inkFaint),
      contentPadding: padding,
      border: border,
      enabledBorder: border,
      focusedBorder: border.copyWith(borderSide: const BorderSide(color: AppColors.accent, width: 2)),
      errorBorder: border.copyWith(borderSide: const BorderSide(color: AppColors.danger)),
      focusedErrorBorder: border.copyWith(borderSide: const BorderSide(color: AppColors.danger, width: 2)),
      disabledBorder: border.copyWith(borderSide: const BorderSide(color: AppColors.line)),
    );
  }

  static TextTheme _textTheme() => TextTheme(
    headlineLarge: AppText.screenTitle, // a tab's big title
    headlineMedium: AppText.priceLarge, // the price on a listing
    headlineSmall: AppText.pageTitle, // page titles
    titleLarge: AppText.sectionTitle, // section titles, the result count
    titleMedium: AppText.cardTitle, // card and row titles
    titleSmall: AppText.label.copyWith(fontWeight: AppType.semibold),
    bodyLarge: AppText.body,
    bodyMedium: AppText.body,
    bodySmall: AppText.secondary, // spec lines, summaries
    labelLarge: AppText.label,
    labelMedium: AppText.tag,
    labelSmall: AppText.fine, // units, legal
  );
}
