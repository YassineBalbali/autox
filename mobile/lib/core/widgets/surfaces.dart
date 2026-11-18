import 'package:material_ui/material_ui.dart';

import '../theme/tokens.dart';

/// A white card with large corners on the grey page. With [onTap], the whole card is the
/// button, its ink clipped to the corners.
class AppCard extends StatelessWidget {
  const AppCard({super.key, required this.child, this.onTap, this.padding = const EdgeInsets.all(AppSpace.card)});

  final Widget child;
  final VoidCallback? onTap;
  final EdgeInsetsGeometry padding;

  @override
  Widget build(BuildContext context) {
    final content = Padding(padding: padding, child: child);
    return DecoratedBox(
      decoration: const BoxDecoration(
        borderRadius: BorderRadius.all(Radius.circular(AppRadius.lg)),
        boxShadow: AppShadows.card,
      ),
      child: Material(
        color: AppColors.surface,
        borderRadius: const BorderRadius.all(Radius.circular(AppRadius.lg)),
        clipBehavior: Clip.antiAlias,
        child: onTap == null ? content : InkWell(onTap: onTap, child: content),
      ),
    );
  }
}

/// A tab's big title, a heading for TalkBack, with an optional line under it.
class ScreenTitle extends StatelessWidget {
  const ScreenTitle(this.title, {super.key, this.subtitle});

  final String title;
  final String? subtitle;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Semantics(container: true, header: true, child: Text(title, style: theme.textTheme.headlineLarge)),
        if (subtitle != null) ...[
          const SizedBox(height: AppSpace.s1),
          Semantics(container: true, child: Text(subtitle!, style: theme.textTheme.bodySmall)),
        ],
      ],
    );
  }
}

/// A group's small title above its card, settings-style: "Mes données", "Compte".
class GroupLabel extends StatelessWidget {
  const GroupLabel(this.text, {super.key});

  final String text;

  @override
  Widget build(BuildContext context) => Padding(
    padding: const EdgeInsets.fromLTRB(AppSpace.s1, AppSpace.s5, AppSpace.s1, AppSpace.s2),
    child: Semantics(
      container: true,
      header: true,
      child: Text(text, style: Theme.of(context).textTheme.titleSmall?.copyWith(color: AppColors.inkSoft)),
    ),
  );
}

/// An icon on a tinted rounded square: the mark at the start of a card or a section.
class IconTile extends StatelessWidget {
  const IconTile({super.key, required this.icon, this.size = AppSizes.iconTile});

  final IconData icon;
  final double size;

  @override
  Widget build(BuildContext context) => ExcludeSemantics(
    child: Container(
      width: size,
      height: size,
      decoration: const BoxDecoration(
        color: AppColors.accentSoft,
        borderRadius: BorderRadius.all(Radius.circular(AppRadius.sm)),
      ),
      alignment: Alignment.center,
      child: Icon(icon, size: AppSizes.iconTileIcon, color: AppColors.accentStrong),
    ),
  );
}

/// A round 48 dp icon button on a white disc with a hairline: the results' sort button.
final ButtonStyle roundIconButtonStyle = IconButton.styleFrom(
  foregroundColor: AppColors.ink,
  backgroundColor: AppColors.surface,
  minimumSize: const Size(AppSizes.touch, AppSizes.touch),
  side: const BorderSide(color: AppColors.lineStrong),
  shape: const CircleBorder(),
);

/// A round button over a photo: a white disc of [AppSizes.overPhotoButton], its touch target
/// padded to 48 dp. The back, share and heart buttons on a listing's photos and the heart on a
/// result's photo.
final ButtonStyle overPhotoButtonStyle = IconButton.styleFrom(
  foregroundColor: AppColors.ink,
  backgroundColor: AppColors.overPhoto,
  fixedSize: const Size.square(AppSizes.overPhotoButton),
  minimumSize: const Size.square(AppSizes.overPhotoButton),
  padding: EdgeInsets.zero,
  tapTargetSize: MaterialTapTargetSize.padded,
  elevation: 1,
  shadowColor: AppColors.ink,
  shape: const CircleBorder(),
);

/// A small grey pill with a fact: "2020", "48 000 km", "Diesel".
class SpecChip extends StatelessWidget {
  const SpecChip(this.text, {super.key});

  final String text;

  @override
  Widget build(BuildContext context) => Container(
    padding: const EdgeInsets.symmetric(horizontal: AppSpace.s2, vertical: AppSpace.s0),
    decoration: const BoxDecoration(
      color: AppColors.surfaceSunk,
      borderRadius: BorderRadius.all(Radius.circular(AppRadius.pill)),
    ),
    child: Text(text, style: AppText.tag, maxLines: 1, overflow: TextOverflow.ellipsis),
  );
}
