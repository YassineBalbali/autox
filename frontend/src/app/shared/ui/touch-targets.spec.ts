import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';

const read = (path: string) => readFileSync(resolve(process.cwd(), path), 'utf8');
const tokens = read('src/styles/_tokens.scss');
const controls = read('src/styles/_controls.scss');

/**
 * The phone's floors, which every control inherits from two shared sheets.
 *
 * <p>jsdom does not lay anything out, so these read the sheets themselves. What they hold
 * in place is that a phone-width layout gets 44px controls whatever pointer the browser
 * reports — the desktop's 34px is a mouse's size — and 16px text in a field, below which
 * iOS Safari zooms the whole page when the field takes focus.
 */
describe('touch targets', () => {
  it('raises every control to 44px for a finger, and for any phone-width layout', () => {
    expect(tokens).toMatch(
      /@media \(pointer: coarse\), \(max-width: 767\.98px\) \{\s*:root \{\s*--h-control: 44px;/,
    );
  });

  it('gives components the same condition to key their own rules on', () => {
    expect(tokens).toMatch(/@mixin touch \{\s*@media \(pointer: coarse\), \(max-width: 767\.98px\) \{\s*@content;/);
  });

  it('keeps the desktop’s 34px controls otherwise', () => {
    expect(tokens).toMatch(/^ {2}--h-control: 34px;/m);
  });

  it('does not let a small button fall below the floor on a phone', () => {
    const small = controls.slice(controls.indexOf('.btn--sm {'));
    expect(small.slice(0, small.indexOf('\n}'))).toMatch(
      /@include t\.touch \{\s*height: var\(--h-control\);/,
    );
  });

  it('gives the controls a results page is tapped through the full floor on a phone', () => {
    // The heart on every photo, the filter chips, and the search bar, which is a step
    // taller than other controls on a desktop and must not stay a step short on a phone.
    expect(read('src/app/shared/listing/favourite-button.ts')).toMatch(
      /@include t\.touch \{[^}]*width: 44px;\s*height: 44px;/,
    );
    expect(read('src/app/shared/ui/filter-chip.ts')).toMatch(/@include t\.touch \{\s*height: var\(--h-control\);/);
    expect(read('src/app/features/search/active-filters.ts')).toMatch(
      /@include t\.touch \{\s*height: var\(--h-control\);/,
    );
    expect(read('src/app/features/search/search-bar.scss')).toMatch(/@include t\.touch \{\s*--h-control: 44px;/);
  });

  it('keeps the heart on a phone photo a 44px target, but shows a 32px disc set in like the photo count', () => {
    // The target is 44px; the disc inside it is inset 6px each side, and the thumbnail
    // moves the button out by those 6px so the disc, not the target, is 8px from the corner.
    const heart = read('src/app/shared/listing/favourite-button.ts');
    expect(heart).toMatch(/--fav-disc-inset: 6px;/);
    expect(heart).toMatch(/@include t\.touch \{[^}]*width: 44px;\s*height: 44px;/);
    expect(heart).toMatch(/&::before \{[^}]*inset: var\(--fav-disc-inset\);/);
    expect(read('src/app/shared/listing/listing-thumb.ts')).toMatch(
      /@include t\.touch \{\s*top: calc\(var\(--s-2\) - var\(--fav-disc-inset, 0px\)\);\s*right: calc\(var\(--s-2\) - var\(--fav-disc-inset, 0px\)\);/,
    );
  });

  it('sets text fields in 16px below the medium breakpoint, so iOS does not zoom', () => {
    const input = controls.slice(controls.indexOf('.input {'));
    expect(input.slice(0, input.indexOf('\n}'))).toMatch(
      /font-size: 1rem;\s*@media \(min-width: t\.\$bp-md\) \{\s*font-size: var\(--t-body\);/,
    );
  });
});
