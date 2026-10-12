import { Component, signal } from '@angular/core';
import { FormControl, ReactiveFormsModule } from '@angular/forms';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it } from 'vitest';

import { TextField } from './text-field';
import { emailValidators } from './validation';

/**
 * The field component: when the message appears, and how it is attached.
 *
 * <p>The ARIA wiring is the part worth testing. A validation message that is visible but
 * not associated with its input is a message a screen reader never reads next to the field
 * it is about — the field is announced as invalid with no reason given, which is the
 * failure mode this component owns the markup specifically to prevent.
 */
@Component({
  imports: [ReactiveFormsModule, TextField],
  template: `
    <app-text-field
      [label]="'Adresse e-mail'"
      type="email"
      [control]="control"
      [hint]="hint()"
      [rejectedByServer]="rejected()"
    />
  `,
})
class Host {
  readonly control = new FormControl('', { nonNullable: true, validators: emailValidators });
  readonly hint = signal<string | null>(null);
  readonly rejected = signal(false);
}

describe('TextField', () => {
  let fixture: ComponentFixture<Host>;
  let host: Host;

  const input = () => fixture.nativeElement.querySelector('input') as HTMLInputElement;
  const error = () => fixture.nativeElement.querySelector('.field__error') as HTMLElement | null;

  beforeEach(async () => {
    await TestBed.configureTestingModule({ imports: [Host] }).compileComponents();
    fixture = TestBed.createComponent(Host);
    host = fixture.componentInstance;
    await fixture.whenStable();
  });

  it('labels the input it renders', () => {
    const label = fixture.nativeElement.querySelector('label') as HTMLLabelElement;
    expect(label.textContent).toContain('Adresse e-mail');
    // The association, not just the text: a label with the wrong `for` is no label at all.
    expect(label.getAttribute('for')).toBe(input().id);
  });

  it('says nothing while the field is untouched, however invalid it is', async () => {
    // Telling somebody their email is malformed after one keystroke is the most common way
    // a form becomes unpleasant to use.
    host.control.setValue('not-an-email');
    await fixture.whenStable();

    expect(error()).toBeNull();
    expect(input().getAttribute('aria-invalid')).toBe('false');
  });

  it('explains the problem once the field has been touched', async () => {
    host.control.setValue('not-an-email');
    host.control.markAsTouched();
    await fixture.whenStable();

    expect(error()?.textContent).toContain('adresse e-mail valide');
  });

  it('attaches the message to the input through aria-describedby', async () => {
    host.control.markAsTouched();
    await fixture.whenStable();

    const described = input().getAttribute('aria-describedby');
    expect(described).toBe(error()?.id);
    expect(input().getAttribute('aria-invalid')).toBe('true');
  });

  it('describes the input by both the hint and the message when both are present', async () => {
    host.hint.set('Nous ne la communiquons à personne.');
    host.control.markAsTouched();
    await fixture.whenStable();

    const described = input().getAttribute('aria-describedby')?.split(' ') ?? [];
    const hintId = (fixture.nativeElement.querySelector('.field__hint') as HTMLElement).id;

    expect(described).toContain(hintId);
    expect(described).toContain(error()?.id);
  });

  it('reports a server rejection without claiming to know the reason', async () => {
    // The API names the field but not the constraint, and its per-field text is
    // untranslated developer wording that is explicitly not a contract. So this says the
    // value was refused and nothing more — inventing a reason would be worse than vague.
    host.control.setValue('taken@exemple.fr');
    host.rejected.set(true);
    await fixture.whenStable();

    expect(error()?.textContent).toContain('refusée');
  });

  it('prefers the client-side reason over the server rejection when it has one', async () => {
    // The local rule knows *why*; the server flag only knows *that*. Showing the vaguer of
    // the two would throw away the only explanation available.
    host.control.setValue('not-an-email');
    host.control.markAsTouched();
    host.rejected.set(true);
    await fixture.whenStable();

    expect(error()?.textContent).toContain('adresse e-mail valide');
  });

  it('renders a textarea when asked, keeping the same wiring', async () => {
    @Component({
      imports: [ReactiveFormsModule, TextField],
      template: `
        <app-text-field [label]="'Présentation'" type="textarea" [control]="control" [rows]="5" />
      `,
    })
    class TextareaHost {
      readonly control = new FormControl('', { nonNullable: true });
    }

    const area = TestBed.createComponent(TextareaHost);
    await area.whenStable();

    const element = area.nativeElement.querySelector('textarea') as HTMLTextAreaElement;
    expect(element).not.toBeNull();
    expect(element.getAttribute('rows')).toBe('5');
    expect(
      (area.nativeElement.querySelector('label') as HTMLLabelElement).getAttribute('for'),
    ).toBe(element.id);
  });

  it('gives each field its own ids, so two on a page cannot collide', async () => {
    @Component({
      imports: [ReactiveFormsModule, TextField],
      template: `
        <app-text-field [label]="'Un'" [control]="a" />
        <app-text-field [label]="'Deux'" [control]="b" />
      `,
    })
    class TwoHost {
      readonly a = new FormControl('', { nonNullable: true });
      readonly b = new FormControl('', { nonNullable: true });
    }

    const two = TestBed.createComponent(TwoHost);
    await two.whenStable();

    const [first, second] = Array.from(
      two.nativeElement.querySelectorAll('input'),
    ) as HTMLInputElement[];
    expect(first.id).not.toBe(second.id);
  });
});
