import { TestBed } from '@angular/core/testing';
import { describe, expect, it } from 'vitest';

import { BodySilhouette } from './body-silhouette';
import { SILHOUETTES, SilhouetteName } from './body-silhouettes.data';

/** Every number in a path, in order: x and y alternate for the commands used here. */
const numbers = (d: string) => (d.match(/-?\d+(\.\d+)?/g) ?? []).map(Number);

/**
 * The body-type drawings.
 *
 * <p>Checked as data — every drawing inside its grid, every wheel inside the body's
 * length — and as rendered: outlines in the text's colour, hidden from a screen reader.
 */
describe('body silhouettes', () => {
  const names = Object.keys(SILHOUETTES) as SilhouetteName[];

  it('covers the six body types the home page offers', () => {
    expect(names).toEqual(['citadine', 'berline', 'break', 'suv', 'monospace', 'utilitaire']);
  });

  it.each(names)('draws %s inside its 64 x 32 grid, on two wheels under the body', (name) => {
    const { paths, wheels } = SILHOUETTES[name];

    for (const value of paths.flatMap(numbers)) {
      expect(value).toBeGreaterThanOrEqual(0);
      expect(value).toBeLessThanOrEqual(64);
    }
    expect(wheels).toHaveLength(2);
    for (const [cx, cy, r] of wheels) {
      expect(cx - r).toBeGreaterThan(0);
      expect(cx + r).toBeLessThan(64);
      expect(cy + r).toBeLessThanOrEqual(32);
    }
    // The body first, closed: an outline, not a scribble.
    expect(paths[0].trim().endsWith('Z')).toBe(true);
  });

  it('renders as an outline in the colour around it, on the 2:1 grid, and stays silent', async () => {
    const fixture = TestBed.createComponent(BodySilhouette);
    fixture.componentRef.setInput('name', 'break');
    fixture.componentRef.setInput('width', 72);
    await fixture.whenStable();
    const svg = fixture.nativeElement.querySelector('svg') as SVGElement;

    expect(svg.getAttribute('viewBox')).toBe('0 0 64 32');
    expect(svg.getAttribute('width')).toBe('72');
    expect(svg.getAttribute('height')).toBe('36');
    expect(svg.getAttribute('fill')).toBe('none');
    expect(svg.getAttribute('stroke')).toBe('currentColor');
    expect(svg.getAttribute('aria-hidden')).toBe('true');
    expect(svg.querySelectorAll('path')).toHaveLength(SILHOUETTES.break.paths.length);
    expect(svg.querySelectorAll('circle')).toHaveLength(2);
  });
});
