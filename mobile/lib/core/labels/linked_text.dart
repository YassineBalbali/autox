import 'package:flutter/gestures.dart';
import 'package:material_ui/material_ui.dart';

/// A piece of a sentence: plain text, or a link to one of the site's paths.
typedef TextSegment = ({String text, String? path});

final _link = RegExp(r'\[([^\]]+)\]\(([^)]+)\)');

/// Splits a sentence from `labels.fr.json` whose links are written `[words](/path)` into its
/// pieces. The web has the same function (frontend/src/app/shared/format/linked-text.ts), and
/// `frontend/fixtures/forms.json` holds both to the same answer.
List<TextSegment> linkedSegments(String sentence) {
  final segments = <TextSegment>[];
  var from = 0;
  for (final match in _link.allMatches(sentence)) {
    if (match.start > from) {
      segments.add((text: sentence.substring(from, match.start), path: null));
    }
    segments.add((text: match.group(1)!, path: match.group(2)));
    from = match.end;
  }
  if (from < sentence.length) {
    segments.add((text: sentence.substring(from), path: null));
  }
  return segments;
}

/// A sentence with links in it, each opened by [onOpen] with its path. Each link is its own
/// node for TalkBack, announced as a link.
class LinkedText extends StatefulWidget {
  const LinkedText(this.sentence, {super.key, required this.onOpen, this.style});

  final String sentence;
  final void Function(String path) onOpen;
  final TextStyle? style;

  @override
  State<LinkedText> createState() => _LinkedTextState();
}

class _LinkedTextState extends State<LinkedText> {
  final _recognizers = <TapGestureRecognizer>[];

  void _disposeRecognizers() {
    for (final recognizer in _recognizers) {
      recognizer.dispose();
    }
    _recognizers.clear();
  }

  @override
  void dispose() {
    _disposeRecognizers();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    _disposeRecognizers();
    final theme = Theme.of(context);
    final style = widget.style ?? theme.textTheme.bodySmall;
    final linkStyle = style?.copyWith(
      color: theme.colorScheme.primary,
      decoration: TextDecoration.underline,
      decorationColor: theme.colorScheme.primary,
    );
    return Text.rich(
      TextSpan(
        style: style,
        children: [
          for (final segment in linkedSegments(widget.sentence))
            if (segment.path case final path?)
              TextSpan(text: segment.text, style: linkStyle, recognizer: _opening(path))
            else
              TextSpan(text: segment.text),
        ],
      ),
    );
  }

  TapGestureRecognizer _opening(String path) {
    final recognizer = TapGestureRecognizer()..onTap = () => widget.onOpen(path);
    _recognizers.add(recognizer);
    return recognizer;
  }
}
