import { Component, signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { FormControl, ReactiveFormsModule } from '@angular/forms';
import { beforeEach, describe, expect, it } from 'vitest';

import { SelectField, SelectOption } from './select-field';
import { requiredText } from './validation';

/**
 * The select field: the same guarantees as `TextField`, for a dropdown.
 *
 * <p>Two things are specific to a select and are what these tests are for. The empty
 * option must always be there, or a required select silently submits its first entry; and
 * a value set before its options arrive — an edit form whose catalogue is still loading —
 * must be selected once they do.
 */
@Component({
  imports: [ReactiveFormsModule, SelectField],
  template: `
    <app-select-field label="Carburant" [control]="control" [options]="options()" [hint]="hint()" [optional]="optional()" />
  `,
})
class Host {
  readonly control = new FormControl('', { nonNullable: true, validators: [requiredText()] });
  readonly options = signal<SelectOption[]>([
    { value: 'PETROL', label: 'Essence' },
    { value: 'DIESEL', label: 'Diesel' },
  ]);
  readonly hint = signal<string | null>(null);
  readonly optional = signal(false);
}

describe('SelectField', () => {
  let fixture: ComponentFixture<Host>;
  let host: Host;

  const select = () => fixture.nativeElement.querySelector('select') as HTMLSelectElement;
  const error = () => fixture.nativeElement.querySelector('.field__error') as HTMLElement | null;

  beforeEach(async () => {
    await TestBed.configureTestingModule({ imports: [Host] }).compileComponents();
    fixture = TestBed.createComponent(Host);
    host = fixture.componentInstance;
    await fixture.whenStable();
  });

  it('labels the select it renders', () => {
    const label = fixture.nativeElement.querySelector('label') as HTMLLabelElement;
    expect(label.textContent).toContain('Carburant');
    expect(label.getAttribute('for')).toBe(select().id);
  });

  it('always starts with an empty option, then the options in the order given', () => {
    expect(Array.from(select().options).map((option) => [option.value, option.textContent?.trim()])).toEqual([
      ['', 'Choisir…'],
      ['PETROL', 'Essence'],
      ['DIESEL', 'Diesel'],
    ]);
    // Nothing is chosen on the seller's behalf.
    expect(select().value).toBe('');
  });

  it('writes the chosen value to the control', async () => {
    select().value = 'DIESEL';
    select().dispatchEvent(new Event('change'));
    await fixture.whenStable();

    expect(host.control.value).toBe('DIESEL');
  });

  it('selects a value that was set before its options arrived', async () => {
    host.options.set([]);
    host.control.setValue('DIESEL');
    await fixture.whenStable();

    host.options.set([
      { value: 'PETROL', label: 'Essence' },
      { value: 'DIESEL', label: 'Diesel' },
    ]);
    await fixture.whenStable();

    expect(select().value).toBe('DIESEL');
  });

  it('says nothing until the field has been touched', async () => {
    expect(host.control.invalid).toBe(true);
    expect(error()).toBeNull();
    expect(select().getAttribute('aria-invalid')).toBe('false');
  });

  it('shows the French message once touched, attached to the select', async () => {
    host.control.markAsTouched();
    await fixture.whenStable();

    expect(error()?.textContent).toContain('Ce champ est obligatoire.');
    expect(select().getAttribute('aria-invalid')).toBe('true');
    expect(select().getAttribute('aria-describedby')).toBe(error()!.id);
  });

  it('clears the message when a value is chosen', async () => {
    host.control.markAsTouched();
    await fixture.whenStable();
    host.control.setValue('PETROL');
    await fixture.whenStable();

    expect(error()).toBeNull();
  });

  it('describes the select by its hint, and by both when there is an error too', async () => {
    host.hint.set('Tel qu’indiqué sur la carte grise.');
    await fixture.whenStable();
    const hint = fixture.nativeElement.querySelector('.field__hint') as HTMLElement;
    expect(select().getAttribute('aria-describedby')).toBe(hint.id);

    host.control.markAsTouched();
    await fixture.whenStable();
    expect(select().getAttribute('aria-describedby')).toBe(`${hint.id} ${error()!.id}`);
  });

  it('marks an optional field as such', async () => {
    expect(fixture.nativeElement.querySelector('.field__optional')).toBeNull();
    host.optional.set(true);
    await fixture.whenStable();
    expect(fixture.nativeElement.querySelector('.field__optional')?.textContent).toContain('optionnel');
  });
});
