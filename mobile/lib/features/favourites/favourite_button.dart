import 'package:autox_api/autox_api.dart' show PublicListingSummary;
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:material_ui/material_ui.dart';

import '../../core/format/listing_text.dart';
import '../../core/theme/tokens.dart';
import '../../core/widgets/surfaces.dart';
import 'favourites.dart';

/// The heart on a result's photo and over a listing's photos: saves the listing on this phone, or
/// forgets it, and says so in a short message.
///
/// A toggle for TalkBack: the state is read as "activé", and the name is the action with the
/// listing's name in it — forty buttons all called "Ajouter aux favoris" are forty identical
/// entries (the web's `favourite-button.ts`).
class FavouriteButton extends ConsumerWidget {
  const FavouriteButton({super.key, required this.listing});

  /// The listing as it is shown: what the Favoris tab keeps.
  final PublicListingSummary listing;

  static String labelOf(String name, {required bool saved}) =>
      saved ? 'Retirer $name des favoris' : 'Ajouter $name aux favoris';

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final id = listing.id;
    if (id == null) {
      return const SizedBox.shrink();
    }
    final saved = ref.watch(isFavouriteProvider(id));
    final name = headlineOfListing(listing).title;

    void toggle() {
      final now = ref.read(favouritesProvider.notifier).toggle(listing);
      ScaffoldMessenger.maybeOf(context)
        ?..hideCurrentSnackBar()
        ..showSnackBar(
          SnackBar(
            content: Text(now ? 'Ajoutée à vos favoris' : 'Retirée de vos favoris'),
            duration: const Duration(seconds: 2),
          ),
        );
    }

    return Semantics(
      container: true,
      button: true,
      toggled: saved,
      label: labelOf(name, saved: saved),
      onTap: toggle,
      excludeSemantics: true,
      child: IconButton(
        style: overPhotoButtonStyle,
        onPressed: toggle,
        icon: HeartIcon(filled: saved, color: saved ? AppColors.accent : AppColors.ink),
      ),
    );
  }
}

/// Lucide's heart, drawn rather than taken from the font: the font has only the outline, and a
/// saved listing's heart is filled — the one state a glance has to tell apart from the other.
class HeartIcon extends StatelessWidget {
  const HeartIcon({super.key, required this.filled, required this.color, this.size = AppSizes.icon});

  final bool filled;
  final Color color;
  final double size;

  @override
  Widget build(BuildContext context) => SizedBox.square(
    dimension: size,
    child: CustomPaint(
      painter: _HeartPainter(filled: filled, color: color),
    ),
  );
}

class _HeartPainter extends CustomPainter {
  const _HeartPainter({required this.filled, required this.color});

  final bool filled;
  final Color color;

  /// Lucide's `heart`, on its 24-unit grid: M19 14c1.49-1.46 3-3.21 3-5.5A5.5 5.5 0 0 0 16.5 3
  /// c-1.76 0-3 .5-4.5 2-1.5-1.5-2.74-2-4.5-2A5.5 5.5 0 0 0 2 8.5c0 2.3 1.5 4.05 3 5.5l7 7Z.
  static Path _heart() => Path()
    ..moveTo(19, 14)
    ..relativeCubicTo(1.49, -1.46, 3, -3.21, 3, -5.5)
    ..arcToPoint(const Offset(16.5, 3), radius: const Radius.circular(5.5), clockwise: false)
    ..relativeCubicTo(-1.76, 0, -3, 0.5, -4.5, 2)
    ..relativeCubicTo(-1.5, -1.5, -2.74, -2, -4.5, -2)
    ..arcToPoint(const Offset(2, 8.5), radius: const Radius.circular(5.5), clockwise: false)
    ..relativeCubicTo(0, 2.3, 1.5, 4.05, 3, 5.5)
    ..relativeLineTo(7, 7)
    ..close();

  @override
  void paint(Canvas canvas, Size size) {
    canvas.scale(size.width / 24, size.height / 24);
    final path = _heart();
    final stroke = Paint()
      ..color = color
      ..style = PaintingStyle.stroke
      ..strokeWidth = 2
      ..strokeJoin = StrokeJoin.round
      ..strokeCap = StrokeCap.round;
    if (filled) {
      canvas.drawPath(path, Paint()..color = color);
    }
    canvas.drawPath(path, stroke);
  }

  @override
  bool shouldRepaint(_HeartPainter old) => old.filled != filled || old.color != color;
}
