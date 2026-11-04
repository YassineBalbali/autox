import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { BASE_PATH, SavedSearchView } from '../../core/api';
import { MySearchesPage } from './my-searches-page';
import { UnsubscribePage } from './unsubscribe-page';

const BASE = 'http://api.test';
const LIST = `${BASE}/api/v1/saved-searches`;

const search = (overrides: Partial<SavedSearchView> = {}): SavedSearchView => ({
  id: 's1',
  name: 'Audi diesel',
  paused: false,
  params: { marque: 'audi', carburant: 'diesel' },
  newCount: 0,
  newCountCapped: false,
  readable: true,
  createdAt: '2026-11-01T08:00:00Z',
  ...overrides,
});

describe('MySearchesPage', () => {
  let fixture: ComponentFixture<MySearchesPage>;
  let backend: HttpTestingController;
  let root: HTMLElement;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting(), provideRouter([]), { provide: BASE_PATH, useValue: BASE }],
    });
    backend = TestBed.inject(HttpTestingController);
  });

  async function show(searches: SavedSearchView[]): Promise<void> {
    fixture = TestBed.createComponent(MySearchesPage);
    root = fixture.nativeElement;
    fixture.detectChanges();
    backend.expectOne(LIST).flush(searches);
    await fixture.whenStable();
  }

  const click = async (action: string) => {
    (root.querySelector(`[data-action="${action}"]`) as HTMLButtonElement).click();
    await fixture.whenStable();
  };

  it('says how to make one when there are none', async () => {
    await show([]);

    expect(root.querySelector('[data-empty]')?.textContent).toContain('Créer une alerte');
  });

  it('lists each with what is new since the last visit, in French', async () => {
    await show([
      search({ id: 'a', newCount: 1 }),
      search({ id: 'b', newCount: 3 }),
      search({ id: 'c', newCount: 99, newCountCapped: true }),
      search({ id: 'd' }),
    ]);

    const labels = Array.from(root.querySelectorAll('[data-new]')).map((each) => each.textContent?.trim());
    expect(labels).toEqual([
      '1 nouvelle annonce',
      '3 nouvelles annonces',
      'Plus de 99 nouvelles annonces',
      'Rien de nouveau depuis votre dernière visite',
    ]);
    expect(root.querySelector('[data-searches-count]')?.textContent).toContain('4 / 10');
  });

  it('opens the search: marks the visit, then the results with its filters', async () => {
    await show([search()]);
    const navigate = vi.spyOn(TestBed.inject(Router), 'navigate').mockResolvedValue(true);

    await click('open');
    expect(navigate).not.toHaveBeenCalled();
    backend.expectOne(`${LIST}/s1/visit`).flush(null, { status: 204, statusText: 'No Content' });

    expect(navigate).toHaveBeenCalledWith(['/recherche'], { queryParams: { marque: 'audi', carburant: 'diesel' } });
  });

  it('still opens it if the visit could not be recorded', async () => {
    await show([search()]);
    const navigate = vi.spyOn(TestBed.inject(Router), 'navigate').mockResolvedValue(true);

    await click('open');
    backend.expectOne(`${LIST}/s1/visit`).flush(null, { status: 500, statusText: 'Server Error' });

    expect(navigate).toHaveBeenCalled();
  });

  it('renames', async () => {
    await show([search()]);

    await click('rename');
    const input = root.querySelector('input') as HTMLInputElement;
    expect(input.value).toBe('Audi diesel');
    input.value = ' Mes Audi ';
    input.dispatchEvent(new Event('input'));
    await fixture.whenStable();
    (root.querySelector('form') as HTMLFormElement).dispatchEvent(new Event('submit', { cancelable: true }));

    const request = backend.expectOne(`${LIST}/s1`);
    expect(request.request.method).toBe('PATCH');
    expect(request.request.body).toEqual({ name: 'Mes Audi' });
    request.flush(search({ name: 'Mes Audi' }));
    await fixture.whenStable();

    expect(root.querySelector('.saved__name')?.textContent).toBe('Mes Audi');
    expect(root.querySelector('form')).toBeNull();
  });

  it('pauses and resumes', async () => {
    await show([search()]);

    await click('pause');
    const pause = backend.expectOne(`${LIST}/s1`);
    expect(pause.request.body).toEqual({ paused: true });
    pause.flush(search({ paused: true }));
    await fixture.whenStable();
    expect(root.querySelector('[data-paused]')?.textContent).toContain('En pause');
    expect(root.querySelector('[data-action="pause"]')?.textContent).toContain('Reprendre');

    await click('pause');
    const resume = backend.expectOne(`${LIST}/s1`);
    expect(resume.request.body).toEqual({ paused: false });
    resume.flush(search());
    await fixture.whenStable();
    expect(root.querySelector('[data-paused]')).toBeNull();
  });

  it('asks before deleting, and removes the row on yes', async () => {
    await show([search()]);

    await click('delete');
    backend.expectNone(`${LIST}/s1`);
    expect(root.querySelector('[data-confirm-delete]')?.textContent).toContain('Supprimer cette recherche');

    await click('confirm-delete');
    const request = backend.expectOne(`${LIST}/s1`);
    expect(request.request.method).toBe('DELETE');
    request.flush(null, { status: 204, statusText: 'No Content' });
    await fixture.whenStable();

    expect(root.querySelector('[data-search]')).toBeNull();
  });

  it('says when a make or a model has gone, and that the search carries on', async () => {
    await show([
      search({ id: 'a', missingMake: 'Saab' }),
      search({ id: 'b', missingModel: '9-3' }),
      search({ id: 'c' }),
    ]);

    expect(root.querySelector('[data-search="a"] [data-missing-make]')?.textContent).toContain('Saab');
    expect(root.querySelector('[data-search="a"] [data-missing-make]')?.textContent).toContain('continue sans elle');
    expect(root.querySelector('[data-search="b"] [data-missing-model]')?.textContent).toContain('9-3');
    expect(root.querySelector('[data-search="c"] [data-missing-make]')).toBeNull();
  });

  it('says when a search cannot be read, and only offers to rename, pause or delete it', async () => {
    await show([search({ readable: false })]);

    expect(root.querySelector('[data-unreadable]')).not.toBeNull();
    expect(root.querySelector('[data-new]')).toBeNull();
    expect((root.querySelector('[data-action="open"]') as HTMLButtonElement).disabled).toBe(true);
  });
});

describe('UnsubscribePage', () => {
  let fixture: ComponentFixture<UnsubscribePage>;
  let backend: HttpTestingController;
  const URL = `${LIST}/unsubscribe`;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting(), provideRouter([]), { provide: BASE_PATH, useValue: BASE }],
    });
    backend = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(UnsubscribePage);
  });

  const open = (token: string) => {
    fixture.componentRef.setInput('token', token);
    fixture.detectChanges();
  };
  const text = () => (fixture.nativeElement as HTMLElement).textContent ?? '';

  it('unsubscribes as it opens, with one click and no sign-in, and never shows the token', async () => {
    open('jeton.signe');
    const request = backend.expectOne(URL);
    expect(request.request.method).toBe('POST');
    expect(request.request.body).toEqual({ token: 'jeton.signe' });
    expect(request.request.headers.has('Authorization')).toBe(false);

    request.flush({ name: 'Audi diesel' });
    await fixture.whenStable();

    expect(text()).toContain('Vous ne recevrez plus l’alerte');
    expect(text()).toContain('Audi diesel');
    expect(text()).not.toContain('jeton.signe');
  });

  it('says so if the search was deleted since', async () => {
    open('jeton.signe');
    backend.expectOne(URL).flush({});
    await fixture.whenStable();

    expect(text()).toContain('Cette recherche a été supprimée');
  });

  it('says a forged or broken link is not valid, and where to go instead', async () => {
    open('faux');
    backend.expectOne(URL).flush({ code: 'INVALID_UNSUBSCRIBE_TOKEN' }, { status: 400, statusText: 'Bad Request' });
    await fixture.whenStable();

    expect(text()).toContain('Ce lien n’est pas valable');
    expect((fixture.nativeElement as HTMLElement).querySelector('a')?.getAttribute('href')).toBe('/mes-recherches');
  });

  it('asks the server nothing with no token', async () => {
    open('');
    await fixture.whenStable();

    backend.expectNone(URL);
    expect(text()).toContain('Ce lien n’est pas valable');
  });
});
