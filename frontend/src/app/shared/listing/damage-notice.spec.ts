import { TestBed } from '@angular/core/testing';
import { describe, expect, it } from 'vitest';

import { DamageType, DamageView } from '../../core/api';
import { DamageNotice } from './damage-notice';

describe('DamageNotice', () => {
  function render(damage: DamageView | null | undefined, compact = false): HTMLElement {
    const fixture = TestBed.createComponent(DamageNotice);
    fixture.componentRef.setInput('damage', damage);
    fixture.componentRef.setInput('compact', compact);
    fixture.detectChanges();
    return fixture.nativeElement as HTMLElement;
  }

  const words = (element: Element | null) => element?.textContent?.replace(/\s+/g, ' ').trim() ?? '';

  it('shows nothing for an undamaged vehicle', () => {
    expect(render(null).querySelector('[data-damage]')).toBeNull();
    expect(render(undefined).querySelector('[data-damage]')).toBeNull();
  });

  it('shows the badge, every type, whether it runs and the description', () => {
    const root = render({
      types: [DamageType.BODYWORK, DamageType.HAIL],
      running: false,
      description: 'Choc avant, capot à remplacer.',
    });

    expect(words(root.querySelector('.tag--danger'))).toBe('Véhicule endommagé');
    expect(words(root.querySelector('[data-damage-facts]'))).toBe('Carrosserie, Grêle · Non roulant');
    expect(words(root.querySelector('[data-damage-description]'))).toBe('Choc avant, capot à remplacer.');
  });

  it('says it runs when it does', () => {
    const root = render({ types: [DamageType.FLOOD], running: true, description: 'Eau.' });
    expect(words(root.querySelector('[data-damage-facts]'))).toBe('Inondation · Roulant');
  });

  it('says nothing about running when the seller was never asked, rather than guess', () => {
    const root = render({ types: [DamageType.OTHER], description: 'Non précisé par le vendeur.' });
    expect(words(root.querySelector('[data-damage-facts]'))).toBe('Autre');
  });

  it('keeps everything on a card, and only shortens the description there', () => {
    const root = render({ types: [DamageType.MECHANICAL], running: false, description: 'Moteur HS.' }, true);

    expect(root.querySelector('.damage--compact')).not.toBeNull();
    expect(words(root.querySelector('.tag--danger'))).toBe('Véhicule endommagé');
    expect(words(root.querySelector('[data-damage-description]'))).toBe('Moteur HS.');
  });
});
