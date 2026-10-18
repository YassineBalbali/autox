import { Component, input } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { describe, expect, it } from 'vitest';

import { BOUND_ATTRIBUTES, Icon } from './icon';
import { ICONS, IconName } from './icons.data';

@Component({
  selector: 'app-icon-host',
  imports: [Icon],
  template: `<app-icon [name]="name()" [size]="size()" [label]="label()" />`,
})
class Host {
  readonly name = input<IconName>('heart');
  readonly size = input(18);
  readonly label = input<string | null>(null);
}

async function render(inputs: { name?: IconName; size?: number; label?: string | null } = {}): Promise<SVGElement> {
  const fixture = TestBed.createComponent(Host);
  for (const [key, value] of Object.entries(inputs)) {
    fixture.componentRef.setInput(key, value);
  }
  await fixture.whenStable();
  return fixture.nativeElement.querySelector('svg');
}

describe('Icon', () => {
  it('draws every icon only with elements and attributes the template binds', () => {
    for (const [name, nodes] of Object.entries(ICONS)) {
      expect(nodes.length, name).toBeGreaterThan(0);
      for (const [tag, attributes] of nodes) {
        expect(Object.keys(BOUND_ATTRIBUTES), `${name}: <${tag}>`).toContain(tag);
        for (const attribute of Object.keys(attributes)) {
          expect(BOUND_ATTRIBUTES[tag], `${name}: <${tag} ${attribute}>`).toContain(attribute);
        }
      }
    }
  });

  it('renders the icon’s shapes in the SVG namespace, from its data', async () => {
    const svg = await render({ name: 'search' });
    const drawn = Array.from(svg.children);

    expect(drawn).toHaveLength(ICONS.search.length);
    expect(drawn.every((child) => child.namespaceURI === 'http://www.w3.org/2000/svg')).toBe(true);
    expect(drawn.map((child) => child.tagName)).toEqual(ICONS.search.map(([tag]) => tag));
  });

  it('is decorative by default: hidden from assistive technology', async () => {
    const svg = await render();

    expect(svg.getAttribute('aria-hidden')).toBe('true');
    expect(svg.getAttribute('role')).toBeNull();
    expect(svg.getAttribute('focusable')).toBe('false');
  });

  it('is announced as an image with its label when it stands alone', async () => {
    const svg = await render({ label: 'Ajouter aux favoris' });

    expect(svg.getAttribute('aria-hidden')).toBeNull();
    expect(svg.getAttribute('role')).toBe('img');
    expect(svg.getAttribute('aria-label')).toBe('Ajouter aux favoris');
  });

  it('takes its size in pixels and its colour from the text around it', async () => {
    const svg = await render({ size: 24 });

    expect(svg.getAttribute('width')).toBe('24');
    expect(svg.getAttribute('height')).toBe('24');
    expect(svg.getAttribute('stroke')).toBe('currentColor');
  });
});
