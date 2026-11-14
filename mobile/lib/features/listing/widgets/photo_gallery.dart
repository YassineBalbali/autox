import 'package:cached_network_image/cached_network_image.dart';
import 'package:lucide_icons_flutter/lucide_icons.dart';
import 'package:material_ui/material_ui.dart';

import '../../../core/theme/tokens.dart';
import '../listing_text.dart';
import 'listing_row.dart' show imageCacheKey;

/// The listing's photos: swiped one by one at 4:3, the count over them, and full screen on a tap,
/// where they can be zoomed. Placeholder when there are none.
class PhotoGallery extends StatefulWidget {
  const PhotoGallery({super.key, required this.images, required this.subject});

  final List<GalleryImage> images;

  /// What the photos are of, for TalkBack: "Photo 2 sur 9 : Peugeot 208".
  final String subject;

  @override
  State<PhotoGallery> createState() => _PhotoGalleryState();
}

class _PhotoGalleryState extends State<PhotoGallery> {
  late final _pages = PageController();
  int _index = 0;

  @override
  void dispose() {
    _pages.dispose();
    super.dispose();
  }

  void _openFullScreen() => Navigator.of(context, rootNavigator: true).push(
    MaterialPageRoute<void>(
      fullscreenDialog: true,
      builder: (_) => _FullScreenGallery(images: widget.images, initial: _index, subject: widget.subject),
    ),
  );

  @override
  Widget build(BuildContext context) {
    final images = widget.images;
    if (images.isEmpty) {
      return AspectRatio(
        aspectRatio: 4 / 3,
        child: Container(
          color: AppColors.surfaceSunk,
          alignment: Alignment.center,
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              const Icon(LucideIcons.image, size: 40, color: AppColors.inkFaint),
              const SizedBox(height: AppSpace.s1),
              Text('Pas de photo', style: Theme.of(context).textTheme.bodySmall),
            ],
          ),
        ),
      );
    }
    return AspectRatio(
      aspectRatio: 4 / 3,
      child: Stack(
        fit: StackFit.expand,
        children: [
          PageView.builder(
            controller: _pages,
            itemCount: images.length,
            onPageChanged: (index) => setState(() => _index = index),
            itemBuilder: (_, index) => Semantics(
              container: true,
              button: true,
              label: 'Photo ${index + 1} sur ${images.length} : ${widget.subject}. Afficher en plein écran',
              excludeSemantics: true,
              child: GestureDetector(
                onTap: _openFullScreen,
                child: _Photo(url: images[index].medium),
              ),
            ),
          ),
          if (images.length > 1)
            Positioned(
              right: AppSpace.s2,
              bottom: AppSpace.s2,
              child: ExcludeSemantics(child: _Counter(text: '${_index + 1} / ${images.length}')),
            ),
        ],
      ),
    );
  }
}

class _Photo extends StatelessWidget {
  const _Photo({required this.url, this.fit = BoxFit.cover});

  final String url;
  final BoxFit fit;

  @override
  Widget build(BuildContext context) => CachedNetworkImage(
    imageUrl: url,
    cacheKey: imageCacheKey(url),
    fit: fit,
    placeholder: (_, _) => Container(color: AppColors.surfaceSunk),
    errorWidget: (_, _, _) => Container(
      color: AppColors.surfaceSunk,
      alignment: Alignment.center,
      child: const Icon(LucideIcons.image, color: AppColors.inkFaint),
    ),
  );
}

class _Counter extends StatelessWidget {
  const _Counter({required this.text});

  final String text;

  @override
  Widget build(BuildContext context) => Container(
    padding: const EdgeInsets.symmetric(horizontal: AppSpace.s2, vertical: 2),
    decoration: BoxDecoration(
      color: AppColors.navy.withValues(alpha: 0.75),
      borderRadius: BorderRadius.circular(AppRadius.sm),
    ),
    child: Text(
      text,
      style: const TextStyle(color: AppColors.surface, fontSize: AppType.tsm, fontWeight: AppType.semibold),
    ),
  );
}

/// Black, edge to edge, the large size, pinch to zoom, swipe between photos.
class _FullScreenGallery extends StatefulWidget {
  const _FullScreenGallery({required this.images, required this.initial, required this.subject});

  final List<GalleryImage> images;
  final int initial;
  final String subject;

  @override
  State<_FullScreenGallery> createState() => _FullScreenGalleryState();
}

class _FullScreenGalleryState extends State<_FullScreenGallery> {
  late final _pages = PageController(initialPage: widget.initial);
  late int _index = widget.initial;

  @override
  void dispose() {
    _pages.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) => Scaffold(
    backgroundColor: Colors.black,
    appBar: AppBar(
      backgroundColor: Colors.black,
      foregroundColor: Colors.white,
      shape: const Border(),
      leading: IconButton(
        tooltip: 'Fermer',
        icon: const Icon(LucideIcons.x),
        onPressed: () => Navigator.of(context).pop(),
      ),
      title: Text(
        '${_index + 1} / ${widget.images.length}',
        style: const TextStyle(color: Colors.white, fontSize: AppType.tmd),
      ),
    ),
    body: PageView.builder(
      controller: _pages,
      itemCount: widget.images.length,
      onPageChanged: (index) => setState(() => _index = index),
      itemBuilder: (_, index) => Semantics(
        container: true,
        image: true,
        label: 'Photo ${index + 1} sur ${widget.images.length} : ${widget.subject}',
        child: InteractiveViewer(
          maxScale: 4,
          child: Center(
            child: _Photo(url: widget.images[index].large, fit: BoxFit.contain),
          ),
        ),
      ),
    ),
  );
}
