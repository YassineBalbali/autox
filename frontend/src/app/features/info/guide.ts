import CONTENT from '../../../../content/help.fr.json';
import { linkedSegments } from '../../shared/format/linked-text';

/**
 * The help pages' text, read from frontend/content/help.fr.json: one source for the site and,
 * later, for the assistant (docs/assistant-plan.md, A0). The legal pages are not in it.
 */

/** A piece of a text: plain, bold, a link to a site path, or the contact address. */
export interface GuideSegment {
  readonly text: string;
  readonly strong?: true;
  readonly path?: string;
  readonly contact?: true;
}

export type GuideBlock =
  | { readonly kind: 'p'; readonly segments: readonly GuideSegment[] }
  | { readonly kind: 'ul' | 'ol'; readonly items: readonly (readonly GuideSegment[])[] };

export interface GuideSection {
  readonly id: string;
  readonly heading: string | null;
  readonly blocks: readonly GuideBlock[];
}

export interface Guide {
  readonly id: string;
  readonly path: string;
  readonly title: string;
  readonly lede: string | null;
  readonly sections: readonly GuideSection[];
}

const CONTACT = '{contact}';

/**
 * Splits a text written with the file's markup: `**words**` in bold, `[words](/path)` a link,
 * as in labels.fr.json, which may sit inside bold, and `{contact}` the contact address.
 */
export function guideSegments(text: string): GuideSegment[] {
  const segments: GuideSegment[] = [];
  text.split('**').forEach((part, index) => {
    const strong = index % 2 === 1;
    for (const { text: piece, path } of linkedSegments(part)) {
      piece.split(CONTACT).forEach((words, at) => {
        if (at > 0) segments.push({ text: '', contact: true });
        if (words)
          segments.push({
            text: words,
            ...(strong ? { strong: true } : {}),
            ...(path ? { path } : {}),
          });
      });
    }
  });
  return segments;
}

interface RawBlock {
  readonly p?: string;
  readonly ul?: readonly string[];
  readonly ol?: readonly string[];
}

function block(raw: RawBlock): GuideBlock {
  if (raw.p !== undefined) return { kind: 'p', segments: guideSegments(raw.p) };
  if (raw.ul) return { kind: 'ul', items: raw.ul.map(guideSegments) };
  if (raw.ol) return { kind: 'ol', items: raw.ol.map(guideSegments) };
  throw new Error(`help.fr.json: a block that is neither p, ul nor ol: ${JSON.stringify(raw)}`);
}

export const GUIDES: readonly Guide[] = CONTENT.pages.map((page) => ({
  id: page.id,
  path: page.path,
  title: page.title,
  lede: page.lede,
  sections: page.sections.map((section) => ({
    id: section.id,
    heading: section.heading,
    blocks: (section.blocks as RawBlock[]).map(block),
  })),
}));

/** The page with this id; a missing one is a programming error, caught by the spec. */
export function guide(id: string): Guide {
  const found = GUIDES.find((page) => page.id === id);
  if (!found) throw new Error(`help.fr.json has no page "${id}".`);
  return found;
}
