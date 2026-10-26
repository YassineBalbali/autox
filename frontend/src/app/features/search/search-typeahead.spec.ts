import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting, TestRequest } from '@angular/common/http/testing';
import { Component, signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { BASE_PATH, CatalogueSuggestion, CatalogueSuggestionTypeEnum } from '../../core/api';
import { matchedLength, SUGGEST_DEBOUNCE_MS, SearchTypeahead } from './search-typeahead';

const BASE = 'http://api.test';
const SUGGEST = `${BASE}/api/v1/catalogue/suggestions`;

function model(name: string, count: number, make = 'Audi'): CatalogueSuggestion {
  return {
    type: CatalogueSuggestionTypeEnum.MODEL,
    makeId: `id-${make.toLowerCase()}`,
    makeSlug: make.toLowerCase(),
    makeName: make,
    modelId: `id-${name.toLowerCase()}`,
    modelSlug: name.toLowerCase(),
    modelName: name,
    label: `${make} ${name}`,
    listingCount: count,
  };
}

const AUDI_A = [model('A3', 12), model('A4', 3), model('A1', 1)];

@Component({
  imports: [SearchTypeahead],
  template: `
    <form (submit)="$event.preventDefault(); submits = submits + 1">
      <app-search-typeahead inputId="b-q" [value]="value()" (valueChange)="value.set($event)"
        (picked)="picks.push($event)" />
    </form>
  `,
})
class Host {
  readonly value = signal('');
  readonly picks: CatalogueSuggestion[] = [];
  submits = 0;
}

/**
 * The text field's suggestions: when they are asked for, which answer is shown, and how a
 * keyboard moves through them and picks one.
 */
describe('SearchTypeahead', () => {
  let fixture: ComponentFixture<Host>;
  let host: Host;
  let backend: HttpTestingController;
  let root: HTMLElement;

  /**
   * Settles the fixture without moving the clock the debounce reads. Under fake timers
   * whenStable() can wait for a render the zoneless scheduler never gets to run, so the
   * pending timers due now are run and change detection is called directly.
   */
  async function stable(): Promise<void> {
    await vi.advanceTimersByTimeAsync(0);
    fixture.detectChanges();
  }

  beforeEach(async () => {
    vi.useFakeTimers();
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting(), { provide: BASE_PATH, useValue: BASE }],
    });
    backend = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(Host);
    host = fixture.componentInstance;
    await stable();
    root = fixture.nativeElement;
  });

  afterEach(() => {
    backend.verify();
    vi.useRealTimers();
  });

  const field = () => root.querySelector('#b-q') as HTMLInputElement;
  const list = () => root.querySelector('[role="listbox"]') as HTMLElement;
  const options = () => Array.from(root.querySelectorAll('[role="option"]')) as HTMLElement[];
  const labels = () => options().map((option) => option.querySelector('.typeahead__label')?.textContent);

  async function type(value: string): Promise<void> {
    field().value = value;
    field().dispatchEvent(new Event('input'));
    await stable();
  }

  async function pause(ms = SUGGEST_DEBOUNCE_MS): Promise<void> {
    await vi.advanceTimersByTimeAsync(ms);
    await stable();
  }

  function requested(q: string): TestRequest {
    return backend.expectOne((request) => request.url === SUGGEST && decodeURIComponent(request.params.get('q') ?? '') === q);
  }

  async function answer(q: string, suggestions: CatalogueSuggestion[]): Promise<void> {
    requested(q).flush(suggestions);
    await stable();
  }

  async function press(key: string): Promise<KeyboardEvent> {
    const event = new KeyboardEvent('keydown', { key, bubbles: true, cancelable: true });
    field().dispatchEvent(event);
    await stable();
    return event;
  }

  describe('when it asks', () => {
    it('waits for a 200 ms pause in typing, then asks once, for the last text', async () => {
      await type('au');
      await pause(SUGGEST_DEBOUNCE_MS - 1);
      await type('aud');
      await type('audi a');
      await pause(SUGGEST_DEBOUNCE_MS - 1);
      backend.expectNone(() => true);

      await pause(1);
      await answer('audi a', AUDI_A);
      expect(labels()).toEqual(['Audi A3', 'Audi A4', 'Audi A1']);
    });

    it('does not ask below two characters, outer spaces not counting', async () => {
      await type('a');
      await pause();
      await type(' a ');
      await pause();
      backend.expectNone(() => true);
      expect(list().hidden).toBe(true);
    });

    it('empties the list at once when the text falls below two characters', async () => {
      await type('au');
      await pause();
      await answer('au', [model('A3', 2)]);
      expect(options()).toHaveLength(1);

      await type('a');
      expect(options()).toHaveLength(0);
      expect(field().getAttribute('aria-expanded')).toBe('false');
    });

    it('shows no list at all when nothing matches', async () => {
      await type('zz');
      await pause();
      await answer('zz', []);

      expect(list().hidden).toBe(true);
      expect(field().getAttribute('aria-expanded')).toBe('false');
    });

    it('treats a failed request as no suggestions, and the field still works', async () => {
      await type('au');
      await pause();
      requested('au').flush({ code: 'INTERNAL_ERROR' }, { status: 500, statusText: 'Server Error' });
      await stable();

      expect(list().hidden).toBe(true);
      expect(host.value()).toBe('au');
    });
  });

  describe('which answer is shown', () => {
    it('cancels the answer to an older text, so it cannot replace the newer one', async () => {
      await type('au');
      await pause();
      const older = requested('au');

      await type('audi a');
      await pause();
      await answer('audi a', AUDI_A);

      // The slow answer to "au" was cancelled when "audi a" was asked; it lands nowhere.
      expect(older.cancelled).toBe(true);
      expect(labels()).toEqual(['Audi A3', 'Audi A4', 'Audi A1']);
    });

    it('cancels a request still on its way when a suggestion is picked', async () => {
      await type('audi a');
      await pause();
      await answer('audi a', AUDI_A);
      await type('audi a3');
      await pause();
      const late = requested('audi a3');

      await press('ArrowDown');
      await press('Enter');
      await pause();

      expect(late.cancelled).toBe(true);
      expect(list().hidden).toBe(true);
    });
  });

  describe('as a combobox', () => {
    beforeEach(async () => {
      await type('audi a');
      await pause();
      await answer('audi a', AUDI_A);
    });

    it('is announced as one, with its list', async () => {
      expect(field().getAttribute('role')).toBe('combobox');
      expect(field().getAttribute('aria-autocomplete')).toBe('list');
      expect(field().getAttribute('aria-controls')).toBe(list().id);
      expect(field().getAttribute('aria-expanded')).toBe('true');
      expect(list().getAttribute('role')).toBe('listbox');
      // Nothing highlighted until the keyboard moves.
      expect(field().hasAttribute('aria-activedescendant')).toBe(false);
    });

    it('moves the highlight with the arrow keys, round the ends, and keeps the focus in the field', async () => {
      field().focus();
      const down = await press('ArrowDown');
      expect(down.defaultPrevented).toBe(true);
      expect(field().getAttribute('aria-activedescendant')).toBe(options()[0].id);
      expect(options()[0].getAttribute('aria-selected')).toBe('true');

      await press('ArrowDown');
      await press('ArrowDown');
      expect(field().getAttribute('aria-activedescendant')).toBe(options()[2].id);
      await press('ArrowDown');
      expect(field().getAttribute('aria-activedescendant')).toBe(options()[0].id);
      await press('ArrowUp');
      expect(field().getAttribute('aria-activedescendant')).toBe(options()[2].id);

      expect(options().filter((option) => option.getAttribute('aria-selected') === 'true')).toHaveLength(1);
      expect(document.activeElement).toBe(field());
    });

    it('picks the highlighted entry on Enter, instead of submitting the form', async () => {
      await press('ArrowDown');
      await press('ArrowDown');
      const enter = await press('Enter');

      expect(enter.defaultPrevented).toBe(true);
      expect(host.picks.map((pick) => pick.label)).toEqual(['Audi A4']);
      expect(list().hidden).toBe(true);
    });

    it('leaves Enter to the form when nothing is highlighted: a free-text search, as before', async () => {
      const enter = await press('Enter');

      expect(enter.defaultPrevented).toBe(false);
      expect(host.picks).toEqual([]);
      expect(list().hidden).toBe(true);
    });

    it('closes on Escape, and only closes', async () => {
      await press('ArrowDown');
      const escape = await press('Escape');

      expect(escape.defaultPrevented).toBe(true);
      expect(list().hidden).toBe(true);
      expect(field().hasAttribute('aria-activedescendant')).toBe(false);
      expect(field().value).toBe('audi a');
      // The arrow brings the same list back.
      await press('ArrowDown');
      expect(list().hidden).toBe(false);
      expect(field().getAttribute('aria-activedescendant')).toBe(options()[0].id);
    });

    it('picks with a click, the field keeping its focus while the button is down', async () => {
      const down = new MouseEvent('mousedown', { bubbles: true, cancelable: true });
      options()[2].dispatchEvent(down);
      options()[2].click();
      await stable();

      expect(down.defaultPrevented).toBe(true);
      expect(host.picks.map((pick) => pick.label)).toEqual(['Audi A1']);
    });

    it('bolds the part that was typed', async () => {
      expect(options().map((option) => option.querySelector('strong')?.textContent)).toEqual([
        'Audi A',
        'Audi A',
        'Audi A',
      ]);
      expect(options()[0].querySelector('.typeahead__count')?.textContent?.trim()).toBe('12 annonces');
      expect(options()[2].querySelector('.typeahead__count')?.textContent?.trim()).toBe('1 annonce');
    });
  });

  it('bolds the model’s name when that is what matched', async () => {
    await type('a3');
    await pause();
    await answer('a3', [model('A3', 2)]);

    const option = options()[0];
    expect(option.querySelector('strong')?.textContent).toBe('A3');
    expect(option.querySelector('.typeahead__label')?.textContent).toBe('Audi A3');
  });
});

describe('matchedLength', () => {
  it('counts the typed prefix in the text’s own characters, ignoring case, accents and extra spaces', () => {
    expect(matchedLength('Citroën C5 Aircross', 'citroe')).toBe(6);
    expect(matchedLength('Citroën C5 Aircross', 'CITROËN C')).toBe(9);
    expect(matchedLength('Renault Scénic', 'renault  sce')).toBe(11);
    expect(matchedLength('Škoda', 'sk')).toBe(2);
  });

  it('is 0 when the text does not start with what was typed', () => {
    expect(matchedLength('Audi A3', 'a3')).toBe(0);
    expect(matchedLength('Audi', 'audi a')).toBe(0);
    expect(matchedLength('Audi', '  ')).toBe(0);
  });
});
