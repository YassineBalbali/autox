import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';

import { TestBed } from '@angular/core/testing';
import { describe, expect, it } from 'vitest';

import { Logo } from './logo';
import { WORDMARK } from './wordmark.paths';

const svg = readFileSync(resolve(process.cwd(), 'public/autox-wordmark.svg'), 'utf8');
const tokens = readFileSync(resolve(process.cwd(), 'src/styles/_tokens.scss'), 'utf8');

const token = (name: string): string => {
  const match = tokens.match(new RegExp(`${name}:\\s*(#[0-9a-fA-F]{6})`));
  if (!match) {
    throw new Error(`Token ${name} not found in _tokens.scss`);
  }
  return match[1].toLowerCase();
};

/**
 * The wordmark exists twice, and this is what keeps the two the same.
 *
 * <p>Inside the application it is the `app-logo` component, drawn in `var(--c-navy)` and
 * `var(--c-accent)`. Outside it — an email, a social card — it is
 * `public/autox-wordmark.svg`, a static file that cannot read a custom property and so
 * carries the two colours as literals. That literal is the single copy of the accent
 * colour that lives outside the token, and without this test it would drift the first
 * time somebody adjusted the blue: the logo in the header would follow, and the file sent
 * to a partner would not.
 */
describe('the AutoX wordmark', () => {
  const fills = [...svg.matchAll(/<path fill="(#[0-9a-fA-F]{6})"/g)].map((m) => m[1].toLowerCase());

  it('draws "Auto" in the navy token and "X" in the accent token', () => {
    expect(fills).toEqual([token('--c-navy'), token('--c-accent')]);
  });

  it('is two-tone, and the two tones differ', () => {
    expect(fills).toHaveLength(2);
    expect(fills[0]).not.toBe(fills[1]);
  });

  it('is the same artwork as the component', () => {
    expect(svg).toContain(`d="${WORDMARK.auto}"`);
    expect(svg).toContain(`d="${WORDMARK.x}"`);
    expect(svg).toContain(`viewBox="0 0 ${WORDMARK.width} ${WORDMARK.height}"`);
  });

  it('is outlined, so it needs no font to render', () => {
    expect(svg).not.toContain('<text');
    expect(svg).not.toContain('font-family');
  });

  it('is well-formed XML', () => {
    // A double hyphen inside an XML comment is illegal and makes the whole file fail to
    // load as an image — which is exactly how the first version of this file broke.
    for (const comment of svg.match(/<!--[\s\S]*?-->/g) ?? []) {
      expect(comment.slice(4, -3)).not.toContain('--');
    }
    const doc = new DOMParser().parseFromString(svg, 'image/svg+xml');
    expect(doc.querySelector('parsererror')).toBeNull();
  });

  it('has an accessible name and no icon', () => {
    expect(svg).toContain('aria-label="AutoX"');
    expect(svg.match(/<path /g)).toHaveLength(2);
  });

  describe('as a component', () => {
    it('takes its colours from the tokens, not from literals', async () => {
      const fixture = TestBed.createComponent(Logo);
      await fixture.whenStable();
      const html = fixture.nativeElement.innerHTML as string;

      // No hex anywhere in the rendered mark: the fills come from the stylesheet.
      expect(html).not.toMatch(/#[0-9a-fA-F]{6}/);
      expect(fixture.nativeElement.querySelector('.logo__auto')).not.toBeNull();
      expect(fixture.nativeElement.querySelector('.logo__x')).not.toBeNull();
    });

    it('reserves its box from the artwork ratio', async () => {
      const fixture = TestBed.createComponent(Logo);
      await fixture.whenStable();

      expect((fixture.nativeElement as HTMLElement).style.aspectRatio).toContain(
        String(WORDMARK.width),
      );
    });
  });
});
