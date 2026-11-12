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
    expect(tokens).toMatch(/^\s+--h-control: 34px;/m);
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

  it('sets text fields in 16px on a phone and on any touch screen, so iOS does not zoom', () => {
    // A tablet is 768px wide and still a finger: the desktop size is for a mouse only.
    const input = controls.slice(controls.indexOf('.input {'));
    expect(input.slice(0, input.indexOf('\n}'))).toMatch(
      /font-size: 1rem;\s*@media \(min-width: t\.\$bp-md\) and \(pointer: fine\) \{\s*font-size: var\(--t-body\);/,
    );
  });

  it('gives the pages added since the last mobile pass 44px targets on a touch screen', () => {
    // The gallery's arrows, after the width they override; the seller's name; the dealer
    // page's way back; the estimate's link; the admin dashboard and the moderation queue.
    const gallery = read('src/app/shared/listing/photo-gallery.scss');
    const arrows = gallery.slice(gallery.indexOf('.gallery__arrow,'));
    expect(arrows.slice(0, arrows.indexOf('\n}'))).toMatch(/width: 2\.5rem;[\s\S]*@include t\.touch \{\s*width: 44px;/);
    expect(read('src/app/features/listing/listing-summary.scss')).toMatch(
      /\.seller__name--link \{[^}]*@include t\.touch \{\s*display: inline-flex;\s*align-items: center;\s*min-height: 44px;/,
    );
    expect(read('src/app/features/dealers/dealer-page.scss')).toMatch(
      /\.dealer__crumbs \{[\s\S]*?a \{\s*@include t\.touch \{\s*display: inline-flex;\s*align-items: center;\s*min-height: 44px;/,
    );
    expect(read('src/app/features/seller/price-estimate.ts')).toMatch(
      /\.estimate__more \{[^}]*@include t\.touch \{[^}]*min-height: 44px;/,
    );
    const dashboard = read('src/app/features/admin/admin-dashboard-page.scss');
    for (const selector of ['dash__period', 'dash__tab', 'dash__link']) {
      expect(dashboard).toMatch(new RegExp(`\\.${selector} \\{[^}]*@include t\\.touch \\{[^}]*min-height: 44px;`));
    }
    expect(read('src/app/features/admin/dashboard/demo-switch.ts')).toMatch(/@include t\.touch \{\s*min-height: 44px;/);
    expect(read('src/app/features/admin/moderation-page.scss')).toMatch(
      /\.moderation__view \{[^}]*@include t\.touch \{[^}]*min-width: 44px;\s*min-height: 44px;/,
    );
    expect(read('src/app/features/admin/moderation-filters.scss')).toMatch(/@include t\.touch \{\s*min-height: 44px;/);
  });

  it('centres the home card’s three tabs alike, the "Vendre" link with the two buttons', () => {
    const finder = read('src/app/features/home/home-finder.scss');
    const tab = finder.slice(finder.indexOf('.finder__tab {'));
    expect(tab.slice(0, tab.indexOf('\n}'))).toMatch(/display: flex;\s*align-items: center;\s*justify-content: center;/);
  });

  it('keeps the footer’s 44px rows on a tablet: the compact links are for a mouse', () => {
    expect(read('src/app/layout/site-footer.ts')).toMatch(
      /@media \(min-width: t\.\$bp-md\) and \(pointer: fine\) \{\s*display: inline;\s*min-height: 0;/,
    );
  });

  it('fits the header in a tablet’s width, signed in, without a sideways scroll', () => {
    // Between 768 and 1024px only: tighter gaps, single-line labels, the account name capped,
    // the messages link an icon. From 1024px on, the desktop bar as designed.
    const header = read('src/app/layout/site-header.scss');
    const tablet = '@media (min-width: t.$bp-md) and (max-width: calc(t.$bp-lg - 0.02px))';
    expect(header.split(tablet).length - 1).toBeGreaterThanOrEqual(4);
    expect(header).toMatch(/font-size: var\(--t-sm\);\s*white-space: nowrap;/);
    expect(header).toMatch(/max-width: 8rem;/);
    expect(header).toMatch(/\.header__nav-link \{[\s\S]*?@include t\.touch \{\s*justify-content: center;\s*min-width: 44px;/);
    expect(header).toMatch(/\.account-menu__button \{[\s\S]*?@include t\.touch \{\s*height: 44px;/);
    expect(read('src/app/layout/messages-link.ts')).toContain(
      '(max-width: calc(t.$bp-sm - 1px)), (min-width: t.$bp-md) and (max-width: calc(t.$bp-lg - 0.02px))',
    );
  });
});
