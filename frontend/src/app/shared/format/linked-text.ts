/** A piece of a sentence: plain text, or a link to one of the site's paths. */
export interface TextSegment {
  readonly text: string;
  readonly path?: string;
}

const LINK = /\[([^\]]+)\]\(([^)]+)\)/g;

/**
 * Splits a sentence from labels.fr.json whose links are written `[words](/path)` into its
 * pieces, so each client draws the links its own way. The Android app has the same function
 * (mobile/lib/core/labels/linked_text.dart); frontend/fixtures/forms.json holds both to it.
 */
export function linkedSegments(sentence: string): TextSegment[] {
  const segments: TextSegment[] = [];
  let from = 0;
  for (const match of sentence.matchAll(LINK)) {
    if (match.index > from) {
      segments.push({ text: sentence.slice(from, match.index) });
    }
    segments.push({ text: match[1], path: match[2] });
    from = match.index + match[0].length;
  }
  if (from < sentence.length) {
    segments.push({ text: sentence.slice(from) });
  }
  return segments;
}
