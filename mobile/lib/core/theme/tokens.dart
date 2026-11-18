import 'package:flutter/widgets.dart';

/// The app's design tokens: every colour, size, radius, text style and shadow a widget uses.
/// Widgets take their values from here (or from the theme built on them), never their own.
///
/// The palette is the launcher icon's: white surfaces on a very light cool grey, near-black for
/// text and strong elements, AutoX blue for what acts and what is selected, a light-blue tint
/// behind selected things. The status colours and the blue are the site's
/// (`frontend/src/styles/_tokens.scss`); `test/core/theme/tokens_test.dart` keeps those equal and
/// lists the ones the app sets for itself. The contrast of every text colour on every surface it
/// is drawn on is checked by the same test, against WCAG AA.
///
/// Sizes are logical pixels. Android scales text with the system font size.
abstract final class AppColors {
  // ---- brand: the launcher icon's two colours
  static const accent = Color(0xFF0057FF); // primary actions, active states, accents
  static const accentInk = Color(0xFFFFFFFF); // text on the accent
  static const accentStrong = Color(0xFF0043C7); // text on the tint; the accent pressed
  static const accentSoft = Color(0xFFE8EFFF); // the tint: chips, selected states, icon tiles

  // ---- neutrals
  static const page = Color(0xFFF4F5F7); // behind everything: a very light cool grey
  static const surface = Color(0xFFFFFFFF); // cards, fields, the tab bar
  static const surfaceSunk = Color(0xFFF0F2F5); // spec chips, placeholders, inset areas

  static const ink = Color(0xFF111111); // text, strong elements
  static const inkSoft = Color(0xFF4B5563); // secondary text
  static const inkFaint = Color(0xFF5F6672); // the quietest text: hints, units

  static const line = Color(0xFFE3E6EB); // hairlines inside a card
  static const lineStrong = Color(0xFFC5CBD3); // field outlines

  // ---- status: never the accent
  static const danger = Color(0xFFC0262D);
  static const dangerSoft = Color(0xFFFDEDED);
  static const success = Color(0xFF177245);
  static const successSoft = Color(0xFFE8F5EE);
  static const warn = Color(0xFF8A5A08);
  static const warnSoft = Color(0xFFFDF4E3);

  static const focus = Color(0xFF0057FF);

  /// Behind the photo count on a photo.
  static const scrim = Color(0xB3111111);

  /// The discs of the buttons over a photo: white, the photo barely through.
  static const overPhoto = Color(0xEBFFFFFF);

  /// The top of a listing's photos, under the status bar and the buttons.
  static const photoShade = Color(0x66111111);

  /// A listing that went offline, in the favourites: its photo washed out.
  static const retired = Color(0xB3F4F5F7);
}

/// A 4-pixel base; most of the interface lives on 8, 12 and 16.
abstract final class AppSpace {
  static const double s0 = 2;
  static const double s1 = 4;
  static const double s2 = 8;
  static const double s3 = 12;
  static const double s4 = 16;
  static const double s5 = 24;
  static const double s6 = 32;
  static const double s7 = 48;

  /// The page's side margin on a phone.
  static const double gutter = s4;

  /// Inside a card.
  static const double card = s4;

  /// Room left under a tab's content for the floating tab bar's margin.
  static const double tabBarGap = s3;
}

/// Sizes, line heights and the four bundled weights.
abstract final class AppType {
  static const family = 'Barlow Semi Condensed';

  static const double t2xs = 11;
  static const double txs = 12;
  static const double tsm = 13;
  static const double tbody = 15;
  static const double tmd = 17;
  static const double tlg = 20;
  static const double txl = 24;
  static const double t2xl = 32;

  static const double lhTight = 1.15;
  static const double lhSnug = 1.3;
  static const double lhBody = 1.45;

  // The four weights bundled; there is no 500.
  static const regular = FontWeight.w400;
  static const semibold = FontWeight.w600;
  static const bold = FontWeight.w700;
  static const heavy = FontWeight.w800; // screen titles and prices
}

/// The text styles, by role. The theme's text theme is built from them; a widget asks the theme
/// for the role, or names one of these when no theme role fits.
abstract final class AppText {
  /// A tab's big title: "Trouvez votre voiture".
  static const screenTitle = TextStyle(
    fontSize: AppType.t2xl,
    fontWeight: AppType.heavy,
    height: AppType.lhTight,
    letterSpacing: -0.4,
    color: AppColors.ink,
  );

  /// A page's title under its bar, and a section's on a big page.
  static const pageTitle = TextStyle(
    fontSize: AppType.txl,
    fontWeight: AppType.heavy,
    height: AppType.lhTight,
    color: AppColors.ink,
  );

  /// A block's title: "Vos dernières recherches", the result count.
  static const sectionTitle = TextStyle(
    fontSize: AppType.tlg,
    fontWeight: AppType.heavy,
    height: AppType.lhTight,
    color: AppColors.ink,
  );

  /// A card's or a row's title.
  static const cardTitle = TextStyle(
    fontSize: AppType.tmd,
    fontWeight: AppType.bold,
    height: AppType.lhSnug,
    color: AppColors.ink,
  );

  /// A price on a result card.
  static const price = TextStyle(
    fontSize: AppType.txl,
    fontWeight: AppType.heavy,
    height: AppType.lhTight,
    color: AppColors.ink,
  );

  /// The price on a listing's own page.
  static const priceLarge = TextStyle(
    fontSize: AppType.t2xl,
    fontWeight: AppType.heavy,
    height: AppType.lhTight,
    color: AppColors.ink,
  );

  static const body = TextStyle(
    fontSize: AppType.tbody,
    fontWeight: AppType.regular,
    height: AppType.lhBody,
    color: AppColors.ink,
  );

  /// Secondary lines: a summary, a place, a subtitle.
  static const secondary = TextStyle(
    fontSize: AppType.tsm,
    fontWeight: AppType.regular,
    height: AppType.lhSnug,
    color: AppColors.inkSoft,
  );

  /// Buttons, chips, tabs.
  static const label = TextStyle(
    fontSize: AppType.tbody,
    fontWeight: AppType.bold,
    height: AppType.lhSnug,
    color: AppColors.ink,
  );

  /// A chip's label, and a chosen chip's.
  static const chip = TextStyle(
    fontSize: AppType.tsm,
    fontWeight: AppType.semibold,
    height: AppType.lhSnug,
    color: AppColors.ink,
  );
  static const chipSelected = TextStyle(
    fontSize: AppType.tsm,
    fontWeight: AppType.bold,
    height: AppType.lhSnug,
    color: AppColors.accentStrong,
  );

  /// Small tags: "Pro", the verdict's word, a spec chip.
  static const tag = TextStyle(
    fontSize: AppType.txs,
    fontWeight: AppType.semibold,
    height: AppType.lhSnug,
    color: AppColors.inkSoft,
  );

  /// Units, legal lines, the quietest text.
  static const fine = TextStyle(
    fontSize: AppType.t2xs,
    fontWeight: AppType.regular,
    height: AppType.lhSnug,
    color: AppColors.inkFaint,
  );
}

/// Corners: large on cards, fully round on buttons and chips.
abstract final class AppRadius {
  static const double xs = 6; // tags on a photo, the verdict's segments
  static const double sm = 10; // small tags, icon tiles
  static const double md = 14; // fields, photos inside a card, inset panels
  static const double lg = 20; // cards, sheets
  static const double pill = 999; // buttons, chips, the tab bar, the search field
}

/// Shadows, used sparingly: on what floats over the page, not on every card.
abstract final class AppShadows {
  /// A card that stands out from the grey: very soft.
  static const card = [BoxShadow(color: Color(0x0D111111), blurRadius: 16, offset: Offset(0, 4))];

  /// What floats over the content: the tab bar, the sticky button's panel, the search field.
  static const floating = [BoxShadow(color: Color(0x1F111111), blurRadius: 24, offset: Offset(0, 8))];
}

/// Fixed sizes of components.
abstract final class AppSizes {
  /// Android's minimum touch target (Material asks 48 dp).
  static const double touch = 48;

  /// The prominent controls: the search field, the sticky button.
  static const double control = 56;

  /// The floating tab bar's height.
  static const double tabBar = 64;

  /// An icon tile's side, and its icon.
  static const double iconTile = 40;
  static const double iconTileIcon = 20;

  static const double icon = 20;
  static const double iconSmall = 16;
  static const double iconTiny = 12;

  /// The verdict bar's segments.
  static const double verdictSegmentWidth = 14;
  static const double verdictSegmentHeight = 5;

  /// A result card's photo: wider than tall.
  static const double cardPhotoAspect = 16 / 10;

  /// A listing's photos, edge to edge.
  static const double galleryAspect = 4 / 3;

  /// The disc of a button over a photo; its touch target is padded to [touch].
  static const double overPhotoButton = 40;

  /// A small photo: a conversation's listing, a removed favourite.
  static const double thumbWidth = 72;
  static const double thumbHeight = 54;

  /// The avatar on the account's card and a seller's.
  static const double avatar = 48;

  /// The Crit'Air vignette.
  static const double vignette = 64;
}

/// Android's minimum touch target. The web's touch rules use 44 CSS px; Material asks 48 dp.
const double kMinTouchTarget = AppSizes.touch;
