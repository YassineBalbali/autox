import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { describe, expect, it } from 'vitest';

import {
  FuelType,
  Gearbox,
  ImageStatus,
  ListingStatus,
  ModeratedListing,
  ModerationActionType,
  ModerationReason,
  ModerationStatus,
  UserRole,
  VehicleCondition,
} from '../../core/api';
import { NBSP } from '../../shared/format/number';
import { ModerationCard, ModerationDecision } from './moderation-card';

const { APPROVE, REJECT, REMOVE, REINSTATE } = ModerationActionType;

/** A listing as the API sends it to a moderator. `allowedActions` arrives as an array. */
const item = (actions: ModerationActionType[], over: Partial<ModeratedListing> = {}): ModeratedListing => ({
  listing: {
    id: 'l1',
    status: ListingStatus.ACTIVE,
    moderationStatus: ModerationStatus.NOT_REVIEWED,
    title: 'Peugeot 308 BlueHDi 130',
    description: 'Première main.\nCarnet à jour.',
    priceAmount: 1_599_000,
    currencyCode: 'EUR',
    priceNegotiable: true,
    mileageKm: 42_500,
    condition: VehicleCondition.USED,
    vin: 'VF1RFB00066612345',
    publishedAt: '2026-10-01T09:00:00Z',
    location: { postalCode: '69003', city: 'Lyon' },
    vehicle: { make: 'Peugeot', model: '308', firstRegistrationYear: 2021, fuelType: FuelType.DIESEL, gearbox: Gearbox.MANUAL },
    images: [
      { id: 'a', position: 0, status: ImageStatus.READY, variants: { THUMBNAIL: { url: 'http://img.test/a/t.jpg' }, CARD: { url: 'http://img.test/a/c.jpg' } } },
      { id: 'b', position: 1, status: ImageStatus.READY, variants: { THUMBNAIL: { url: 'http://img.test/b/t.jpg' }, CARD: { url: 'http://img.test/b/c.jpg' } } },
    ],
  },
  seller: {
    id: 'u1',
    displayName: 'Garage du Rhône',
    email: 'contact@garage.test',
    role: UserRole.PROFESSIONNEL,
    memberSince: '2025-03-04T10:00:00Z',
  },
  allowedActions: actions as unknown as Set<ModerationActionType>,
  trail: [],
  ...over,
});

/**
 * The moderation card.
 *
 * <p>Two things are held in place here. What a moderator needs in order to judge is on
 * the card, all of it. And a decision that needs a reason cannot leave the card without
 * one: the form refuses before the API has to.
 */
describe('ModerationCard', () => {
  let fixture: ComponentFixture<ModerationCard>;
  let decisions: ModerationDecision[];

  async function render(value: ModeratedListing, inputs: { busy?: boolean; failure?: string | null } = {}) {
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({ providers: [provideRouter([])] });
    fixture = TestBed.createComponent(ModerationCard);
    fixture.componentRef.setInput('item', value);
    fixture.componentRef.setInput('busy', inputs.busy ?? false);
    fixture.componentRef.setInput('failure', inputs.failure ?? null);
    decisions = [];
    fixture.componentInstance.decided.subscribe((decision) => decisions.push(decision));
    await fixture.whenStable();
  }

  const el = () => fixture.nativeElement as HTMLElement;
  const settle = () => fixture.whenStable();
  const text = () => (el().textContent ?? '').replace(/\s+/g, ' ');
  const actions = () => Array.from(el().querySelectorAll('[data-action]')).map((button) => button.textContent?.trim());
  const press = async (action: string) => {
    (el().querySelector(`[data-action=${action}]`) as HTMLButtonElement).click();
    await settle();
  };
  const control = (label: string) => {
    const found = Array.from(el().querySelectorAll('label')).find((candidate) => candidate.textContent?.trim().startsWith(label));
    return el().querySelector(`#${found!.getAttribute('for')}`) as HTMLSelectElement | HTMLTextAreaElement;
  };
  const fill = async (label: string, value: string) => {
    const element = control(label);
    element.value = value;
    element.dispatchEvent(new Event(element.tagName === 'SELECT' ? 'change' : 'input'));
    await settle();
  };
  const errors = () => Array.from(el().querySelectorAll('.field__error')).map((error) => error.textContent?.trim());

  describe('what there is to judge', () => {
    it('shows the listing: title, price, figures, place and the whole description', async () => {
      await render(item([APPROVE, REJECT, REMOVE]));

      expect(el().querySelector('.card__title')?.textContent?.trim()).toBe('Peugeot 308 BlueHDi 130');
      expect(el().querySelector('app-price')?.textContent).toContain(`15${NBSP}990${NBSP}€`);
      expect(text()).toContain('Prix négociable');
      const specs = Array.from(el().querySelectorAll('.readout > div')).map(
        (row) => `${row.querySelector('dt')?.textContent?.trim()}: ${row.querySelector('dd')?.textContent?.trim()}`,
      );
      expect(specs).toEqual(
        expect.arrayContaining([
          'Année: 2021',
          `Kilométrage: 42${NBSP}500${NBSP}km`,
          'Carburant: Diesel',
          'Boîte de vitesses: Manuelle',
          'Localisation: 69003 Lyon',
          'Mise en ligne: 1 octobre 2026',
        ]),
      );
      // Both lines, as the seller wrote them.
      expect(el().querySelector('.card__description')?.textContent).toBe('Première main.\nCarnet à jour.');
    });

    it('shows every photo, in the gallery', async () => {
      await render(item([APPROVE]));

      expect(el().querySelector('app-photo-gallery')).not.toBeNull();
      expect(el().querySelectorAll('app-photo-gallery img').length).toBeGreaterThanOrEqual(2);
    });

    it('says so when there is no photo or no description, rather than leaving a gap', async () => {
      const bare = item([APPROVE]);
      await render({ ...bare, listing: { ...bare.listing, images: [], description: undefined } });

      expect(el().querySelector('.card__nophoto')?.textContent).toContain('Aucune photo');
      expect(text()).toContain('Le vendeur n’a pas rédigé de description.');
    });

    it('shows the VIN, which the public page never does', async () => {
      await render(item([APPROVE]));

      expect(text()).toContain('VF1RFB00066612345');
    });

    it('never treats the description as markup', async () => {
      const hostile = item([APPROVE]);
      await render({ ...hostile, listing: { ...hostile.listing, description: '<img src=x onerror=alert(1)><b>gras</b>' } });

      expect(el().querySelector('.card__description img')).toBeNull();
      expect(el().querySelector('.card__description b')).toBeNull();
      expect(el().querySelector('.card__description')?.textContent).toContain('<b>gras</b>');
    });

    it('shows who is selling, their email included', async () => {
      await render(item([APPROVE]));

      expect(el().querySelector('.card__seller')?.textContent).toContain('Garage du Rhône');
      expect(el().querySelector('.card__seller')?.textContent).toContain('Professionnel');
      expect(text()).toContain('contact@garage.test');
      expect(text()).toContain('Membre depuis 4 mars 2025');
    });

    it('labels the status and the verdict in French, never by their codes', async () => {
      await render(item([APPROVE]));

      expect(el().querySelector('[data-tag=status]')?.textContent?.trim()).toBe('En ligne');
      expect(el().querySelector('[data-tag=verdict]')?.textContent?.trim()).toBe('À vérifier');
      expect(text()).not.toContain('NOT_REVIEWED');
      expect(text()).not.toContain('ACTIVE');
    });

    it('links an online listing to its public page in a new tab, and no other', async () => {
      await render(item([REMOVE]));
      const link = el().querySelector('.card__title a') as HTMLAnchorElement;
      expect(link.getAttribute('href')).toBe('/annonces/l1');
      expect(link.target).toBe('_blank');

      const removed = item([REINSTATE]);
      await render({ ...removed, listing: { ...removed.listing, status: ListingStatus.REMOVED } });
      expect(el().querySelector('.card__title a')).toBeNull();
    });
  });

  describe('the trail', () => {
    it('says nothing has been decided when nothing has', async () => {
      await render(item([APPROVE]));

      expect(text()).toContain('Aucune décision pour l’instant.');
    });

    it('lists who did what, when and why, oldest first', async () => {
      await render(
        item([REINSTATE], {
          trail: [
            { id: 't1', action: APPROVE, moderatorName: 'Camille', at: '2026-10-02T08:30:00Z' },
            {
              id: 't2',
              action: REMOVE,
              reasonCode: ModerationReason.SUSPECTED_FRAUD,
              note: 'Signalée par trois acheteurs.',
              moderatorName: 'Dominique',
              at: '2026-10-05T16:45:00Z',
            },
          ],
        }),
      );
      const lines = Array.from(el().querySelectorAll('.trail__line')).map((line) => line.textContent?.replace(/\s+/g, ' ').trim());

      expect(lines).toHaveLength(2);
      expect(lines[0]).toContain('Approuvée par Camille');
      expect(lines[0]).toContain('2 octobre 2026');
      expect(lines[1]).toContain('Retirée par Dominique');
      expect(lines[1]).toContain('Suspicion de fraude');
      expect(lines[1]).toContain('Signalée par trois acheteurs.');
      expect(lines[1]).not.toContain('SUSPECTED_FRAUD');
    });

    it('still prints a line whose reason code this build does not know', async () => {
      // The API sends no reason for a retired code. The action, the person and the date
      // are still the record.
      await render(item([REINSTATE], { trail: [{ id: 't1', action: REJECT, moderatorName: 'Camille', at: '2026-10-02T08:30:00Z' }] }));

      expect(el().querySelector('.trail__line')?.textContent).toContain('Refusée');
      expect(el().querySelector('.trail__reason')).toBeNull();
    });
  });

  describe('the buttons offered', () => {
    it('offers approve and refuse on a listing awaiting review — one way of taking it down, not two', async () => {
      await render(item([APPROVE, REJECT, REMOVE]));

      expect(actions()).toEqual(['Approuver', 'Refuser']);
    });

    it('offers a takedown on a listing that was already approved', async () => {
      await render(item([REMOVE]));

      expect(actions()).toEqual(['Retirer l’annonce']);
    });

    it('offers reinstatement on a removed listing', async () => {
      await render(item([REINSTATE]));

      expect(actions()).toEqual(['Réintégrer']);
    });

    it('offers nothing the API did not list', async () => {
      await render(item([]));

      expect(actions()).toEqual([]);
    });

    it('reads a Set as well as the array that really arrives', async () => {
      await render(item([], { allowedActions: new Set([APPROVE]) }));

      expect(actions()).toEqual(['Approuver']);
    });

    it('disables them while a decision is on its way', async () => {
      await render(item([APPROVE, REJECT]), { busy: true });

      const buttons = Array.from(el().querySelectorAll('[data-action]')) as HTMLButtonElement[];
      expect(buttons.every((button) => button.disabled)).toBe(true);
      expect(el().querySelector('article')?.getAttribute('aria-busy')).toBe('true');
    });
  });

  describe('approving', () => {
    it('is one click', async () => {
      await render(item([APPROVE, REJECT]));
      await press('approve');

      expect(decisions).toEqual([{ action: APPROVE }]);
    });
  });

  describe('refusing', () => {
    it('asks for a reason before anything is decided', async () => {
      await render(item([APPROVE, REJECT]));
      await press('refuse');

      expect(decisions).toEqual([]);
      expect(Array.from((control('Motif') as HTMLSelectElement).options).map((option) => option.textContent?.trim())).toEqual([
        'Choisir…',
        'Suspicion de fraude',
        'Informations inexactes ou trompeuses',
        'Annonce en double',
        'Ne concerne pas un véhicule à vendre',
        'Photos inadaptées',
        'Contenu contraire aux conditions d’utilisation',
        'Coordonnées dans le titre ou la description',
        'Autre motif',
      ]);
      // The moderator is told who reads what.
      expect(text()).toContain('Le vendeur verra ce motif.');
      expect(text()).toContain('Visible des modérateurs uniquement.');
    });

    it('refuses to go without a reason', async () => {
      await render(item([APPROVE, REJECT]));
      await press('refuse');
      await press('confirm-refusal');

      expect(errors()).toEqual(['Ce champ est obligatoire.']);
      expect(decisions).toEqual([]);
    });

    it('sends the reason alone when no note was written', async () => {
      await render(item([APPROVE, REJECT]));
      await press('refuse');
      await fill('Motif', ModerationReason.DUPLICATE);
      await press('confirm-refusal');

      expect(decisions).toEqual([{ action: REJECT, reasonCode: ModerationReason.DUPLICATE, note: undefined }]);
    });

    it('sends the note, trimmed, with the reason', async () => {
      await render(item([APPROVE, REJECT]));
      await press('refuse');
      await fill('Motif', ModerationReason.MISLEADING_INFORMATION);
      await fill('Note interne', '  Le compteur affiche 187 000 km.  ');
      await press('confirm-refusal');

      expect(decisions).toEqual([
        { action: REJECT, reasonCode: ModerationReason.MISLEADING_INFORMATION, note: 'Le compteur affiche 187 000 km.' },
      ]);
    });

    it('requires a note for "autre motif", which says nothing alone', async () => {
      await render(item([APPROVE, REJECT]));
      await press('refuse');
      expect(el().querySelector('.field__optional')).not.toBeNull();

      await fill('Motif', ModerationReason.OTHER);
      expect(el().querySelector('.field__optional')).toBeNull();
      await press('confirm-refusal');
      expect(errors()).toEqual(['Ce champ est obligatoire.']);
      expect(decisions).toEqual([]);

      await fill('Note interne', 'À la demande du vendeur.');
      await press('confirm-refusal');
      expect(decisions).toEqual([{ action: REJECT, reasonCode: ModerationReason.OTHER, note: 'À la demande du vendeur.' }]);
    });

    it('stops requiring the note when the reason changes back', async () => {
      await render(item([APPROVE, REJECT]));
      await press('refuse');
      await fill('Motif', ModerationReason.OTHER);
      await fill('Motif', ModerationReason.DUPLICATE);
      await press('confirm-refusal');

      expect(decisions).toHaveLength(1);
    });

    it('is a takedown, not a rejection, on a listing that had been approved', async () => {
      await render(item([REMOVE]));
      await press('refuse');
      await fill('Motif', ModerationReason.SUSPECTED_FRAUD);
      expect(el().querySelector('[data-action=confirm-refusal]')?.textContent?.trim()).toBe('Retirer l’annonce');
      await press('confirm-refusal');

      expect(decisions[0].action).toBe(REMOVE);
    });

    it('can be abandoned, leaving nothing behind', async () => {
      await render(item([APPROVE, REJECT]));
      await press('refuse');
      await fill('Motif', ModerationReason.DUPLICATE);
      (Array.from(el().querySelectorAll('button')).find((button) => button.textContent?.includes('Annuler')) as HTMLButtonElement).click();
      await settle();

      expect(actions()).toEqual(['Approuver', 'Refuser']);
      expect(decisions).toEqual([]);
      await press('refuse');
      expect((control('Motif') as HTMLSelectElement).value).toBe('');
    });
  });

  describe('reinstating', () => {
    it('asks first, and says where the listing may come back to', async () => {
      await render(item([REINSTATE]));
      await press('reinstate');

      expect(decisions).toEqual([]);
      expect(text()).toContain('Réintégrer cette annonce ?');
      expect(text()).toContain('sauf si elle a expiré entre-temps');
    });

    it('goes ahead on confirmation, with the note if there is one', async () => {
      await render(item([REINSTATE]));
      await press('reinstate');
      await fill('Note interne', 'Carte grise reçue.');
      await press('confirm-reinstatement');

      expect(decisions).toEqual([{ action: REINSTATE, note: 'Carte grise reçue.' }]);
    });

    it('needs no note', async () => {
      await render(item([REINSTATE]));
      await press('reinstate');
      await press('confirm-reinstatement');

      expect(decisions).toEqual([{ action: REINSTATE, note: undefined }]);
    });
  });

  describe('when the API refuses', () => {
    it('shows the explanation on the card, with the form still open', async () => {
      const value = item([APPROVE, REJECT]);
      await render(value);
      await press('refuse');
      await fill('Motif', ModerationReason.DUPLICATE);

      fixture.componentRef.setInput('failure', 'Cette action n’est pas possible dans l’état actuel.');
      await settle();

      expect(el().querySelector('.alert--error')?.textContent).toContain('Cette action n’est pas possible');
      expect((control('Motif') as HTMLSelectElement).value).toBe(ModerationReason.DUPLICATE);
    });

    it('closes the form when the listing is replaced by its new state', async () => {
      await render(item([APPROVE, REJECT]));
      await press('refuse');

      const removed = item([REINSTATE]);
      fixture.componentRef.setInput('item', { ...removed, listing: { ...removed.listing, status: ListingStatus.REMOVED } });
      await settle();

      expect(actions()).toEqual(['Réintégrer']);
    });
  });
});
