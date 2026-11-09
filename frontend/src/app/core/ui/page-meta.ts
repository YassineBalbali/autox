import { Injectable, inject } from '@angular/core';
import { DOCUMENT } from '@angular/common';
import { Meta, Title } from '@angular/platform-browser';

/** What a page says about itself to a browser tab and to a link preview. */
export interface PageDescription {
  readonly title: string;
  readonly description: string;
  /** An image URL for the preview; the site's own image when absent. */
  readonly image?: string | null;
}

/**
 * The title, the description and the Open Graph and Twitter tags of the page being shown.
 *
 * <p>index.html carries the site's own, read once when this is first used. A page that describes
 * itself calls {@link describe}, as often as what it shows changes, and {@link reset} when it
 * goes, so the next page does not inherit a dealer's description. The title is the router's again
 * at the next navigation.
 *
 * <p>The site is rendered in the browser: a crawler that runs JavaScript reads these, one that
 * only reads the HTML (most chat apps' link previews) gets index.html's.
 */
@Injectable({ providedIn: 'root' })
export class PageMeta {
  private readonly meta = inject(Meta);
  private readonly title = inject(Title);
  private readonly document = inject(DOCUMENT);

  /** The site's own tags, as index.html has them. */
  private readonly defaults = new Map(TAGS.map((tag) => [tag, this.meta.getTag(tag)?.getAttribute('content') ?? null]));

  describe(page: PageDescription): void {
    this.title.setTitle(page.title);
    const values: Record<string, string | null> = {
      'name="description"': page.description,
      'property="og:title"': page.title,
      'property="og:description"': page.description,
      'property="og:type"': 'profile',
      'property="og:url"': this.document.location?.href ?? null,
      'property="og:image"': page.image ?? this.defaults.get('property="og:image"') ?? null,
      'name="twitter:title"': page.title,
      'name="twitter:description"': page.description,
      'name="twitter:image"': page.image ?? this.defaults.get('name="twitter:image"') ?? null,
    };
    this.write(values);
  }

  /** The site's own tags again. */
  reset(): void {
    this.write(Object.fromEntries(this.defaults));
  }

  private write(values: Record<string, string | null>): void {
    for (const [tag, content] of Object.entries(values)) {
      if (content === null) {
        this.meta.removeTag(tag);
      } else {
        this.meta.updateTag({ ...attributeOf(tag), content }, tag);
      }
    }
  }
}

const TAGS = [
  'name="description"',
  'property="og:title"',
  'property="og:description"',
  'property="og:type"',
  'property="og:url"',
  'property="og:image"',
  'name="twitter:title"',
  'name="twitter:description"',
  'name="twitter:image"',
];

/** 'property="og:title"' → { property: 'og:title' }. */
function attributeOf(selector: string): Record<string, string> {
  const [key, value] = selector.split('=');
  return { [key]: value.replace(/"/g, '') };
}
