import { FormControl, FormGroup, Validators } from '@angular/forms';
import { describe, expect, it } from 'vitest';

import {
  MAX_LENGTHS,
  PASSWORD_MAX_LENGTH,
  PASSWORD_MIN_LENGTH,
  PHONE_PATTERN,
  emailValidators,
  firstErrorFr,
  matchesPassword,
  mirrorPassword,
  passwordValidators,
  parseWholeNumber,
  phoneValidators,
  requiredText,
  wholeNumber,
} from './validation';

/**
 * The client-side rules and their French text.
 *
 * <p>Worth testing thoroughly rather than lightly, because this file is the only place in
 * the application that can tell a user *why* a value was rejected: the API reports a field
 * error with untranslated developer text whose wording is not a contract, so a server-side
 * rejection can never say more than "this field is wrong". If a message here is missing,
 * the user is left with a red field and no explanation.
 */
describe('validation rules', () => {
  describe('requiredText', () => {
    it('rejects whitespace, which Validators.required accepts', () => {
      // The reason this exists. `Validators.required` passes a value of "   ", so a display
      // name of three spaces would reach the API and be rejected there instead — with a
      // message the client cannot show.
      const control = new FormControl('   ', { nonNullable: true, validators: [requiredText()] });
      expect(control.errors).toEqual({ required: true });
    });

    it('accepts a value with content', () => {
      const control = new FormControl(' Jean ', {
        nonNullable: true,
        validators: [requiredText()],
      });
      expect(control.valid).toBe(true);
    });
  });

  describe('password rules', () => {
    it('mirrors the server: length only, no composition requirement', () => {
      // A form that demanded a digit or a symbol would reject passwords the API accepts —
      // a rule invented by the client, which the user cannot discover from anywhere else.
      const control = new FormControl('a'.repeat(PASSWORD_MIN_LENGTH), {
        nonNullable: true,
        validators: passwordValidators,
      });
      expect(control.valid).toBe(true);
    });

    it('rejects one character under the minimum', () => {
      const control = new FormControl('a'.repeat(PASSWORD_MIN_LENGTH - 1), {
        nonNullable: true,
        validators: passwordValidators,
      });
      expect(firstErrorFr(control.errors)).toBe(`Au moins ${PASSWORD_MIN_LENGTH} caractères.`);
    });

    it('rejects one character over the maximum', () => {
      const control = new FormControl('a'.repeat(PASSWORD_MAX_LENGTH + 1), {
        nonNullable: true,
        validators: passwordValidators,
      });
      expect(firstErrorFr(control.errors)).toBe(`${PASSWORD_MAX_LENGTH} caractères au maximum.`);
    });

    it('reports the missing value before the length, since that is what to fix first', () => {
      const control = new FormControl('', { nonNullable: true, validators: passwordValidators });
      expect(firstErrorFr(control.errors)).toBe('Ce champ est obligatoire.');
    });
  });

  describe('phone pattern', () => {
    it.each(['+33612345678', '+491701234567', '+1234567'])('accepts %s', (value) => {
      expect(PHONE_PATTERN.test(value)).toBe(true);
    });

    it.each([
      // The mistake a French user makes first, and the reason the field carries a hint.
      '0612345678',
      '+33 6 12 34 56 78',
      '+0612345678',
      '33612345678',
    ])('rejects %s', (value) => {
      expect(PHONE_PATTERN.test(value)).toBe(false);
    });

    it('treats an empty value as valid, because the field is optional', () => {
      // Validators.pattern skips an empty value. That matters: the profile endpoint accepts
      // an empty phone as "clear it", so an empty field has to be submittable.
      const control = new FormControl('', { nonNullable: true, validators: phoneValidators });
      expect(control.valid).toBe(true);
    });
  });

  describe('email rules', () => {
    it('accepts an ordinary address', () => {
      const control = new FormControl('jean.dupont@exemple.fr', {
        nonNullable: true,
        validators: emailValidators,
      });
      expect(control.valid).toBe(true);
    });

    it('explains what a valid address looks like', () => {
      const control = new FormControl('jean.dupont', {
        nonNullable: true,
        validators: emailValidators,
      });
      expect(firstErrorFr(control.errors)).toContain('adresse e-mail valide');
    });
  });

  describe('matchesPassword', () => {
    /**
     * Built the way a screen builds it: the group, then `mirrorPassword`.
     *
     * <p>Both steps are needed and the tests below prove why. A control validates once at
     * construction, before it has a parent to find the password through, and Angular
     * re-runs a control's validators only when that control changes — never when a sibling
     * does.
     */
    const group = (password: string, confirmation: string) => {
      const form = new FormGroup({
        password: new FormControl(password, { nonNullable: true }),
        confirmation: new FormControl(confirmation, {
          nonNullable: true,
          validators: [matchesPassword('password')],
        }),
      });
      mirrorPassword(form.controls.password, form.controls.confirmation);
      return form;
    };

    it('puts the error on the confirmation field, not on the group', () => {
      // On the field on purpose: an error on the group has nowhere to render beside an
      // input, so it becomes a banner at the top of the form — above the fold on a phone,
      // away from where the user is typing.
      const form = group('correct-horse-battery', 'correct-horse-staple');
      expect(form.controls.confirmation.errors).toEqual({ passwordMismatch: true });
      expect(form.errors).toBeNull();
    });

    it('says which of the two is wrong, in French', () => {
      const form = group('correct-horse-battery', 'correct-horse-staple');
      expect(firstErrorFr(form.controls.confirmation.errors)).toBe(
        'Les deux mots de passe ne correspondent pas.',
      );
    });

    it('passes when they match', () => {
      expect(group('correct-horse-battery', 'correct-horse-battery').valid).toBe(true);
    });

    it('stays quiet while the confirmation is still empty', () => {
      // Otherwise the mismatch message appears before the user has typed anything into the
      // field it is about.
      expect(group('correct-horse-battery', '').controls.confirmation.errors).toBeNull();
    });

    it('catches a password edited after it was confirmed', () => {
      // The bug `mirrorPassword` exists for, and the one worth having a test for: without
      // it the form reports itself valid with two different passwords in it, and submits
      // the one in the password field — which is not the one the user believes they have
      // just confirmed twice.
      const form = group('correct-horse-battery', 'correct-horse-battery');
      expect(form.valid).toBe(true);

      form.controls.password.setValue('a-completely-different-one');

      expect(form.controls.confirmation.errors).toEqual({ passwordMismatch: true });
      // And the form itself, or the submit button stays enabled.
      expect(form.valid).toBe(false);
    });

    it('clears the mismatch when the password is edited back into agreement', () => {
      const form = group('correct-horse-battery', 'correct-horse-battery');
      form.controls.password.setValue('something-else-entirely');
      form.controls.password.setValue('correct-horse-battery');

      expect(form.controls.confirmation.errors).toBeNull();
      expect(form.valid).toBe(true);
    });
  });

  describe('wholeNumber', () => {
    const errorsOf = (value: string) =>
      new FormControl(value, { nonNullable: true, validators: [wholeNumber(1900, 2027)] }).errors;

    it('accepts a number inside the bounds, and both bounds themselves', () => {
      expect(errorsOf('2021')).toBeNull();
      expect(errorsOf('1900')).toBeNull();
      expect(errorsOf('2027')).toBeNull();
    });

    it('leaves an empty field to the required rule', () => {
      // "Missing" and "not a number" are two messages; this validator only owns one.
      expect(errorsOf('')).toBeNull();
      expect(errorsOf('   ')).toBeNull();
    });

    it('ignores the spaces people type as thousands separators', () => {
      const mileage = (value: string) =>
        new FormControl(value, { nonNullable: true, validators: [wholeNumber(0, 3_000_000)] }).errors;
      expect(mileage('42 500')).toBeNull();
      // The two no-break spaces a French number formatter produces, pasted back in.
      expect(mileage('42\u00a0500')).toBeNull();
      expect(mileage('42\u202f500')).toBeNull();
    });

    it.each(['abc', '12,5', '12.5', '-3', '1e3', '12 km'])('refuses %s as not a whole number', (value) => {
      expect(errorsOf(value)).toEqual({ wholeNumber: true });
    });

    it('reports the bounds when the number is outside them', () => {
      expect(errorsOf('1850')).toEqual({ range: { min: 1900, max: 2027 } });
      expect(errorsOf('2028')).toEqual({ range: { min: 1900, max: 2027 } });
    });

    it('says both in French, with the bounds grouped by a space every font draws', () => {
      expect(firstErrorFr({ wholeNumber: true })).toBe('Saisissez un nombre entier, sans décimale.');
      expect(firstErrorFr({ range: { min: 0, max: 3_000_000 } })).toBe(
        'Saisissez une valeur entre 0 et 3\u00a0000\u00a0000.',
      );
    });
  });

  describe('parseWholeNumber', () => {
    it('reads what wholeNumber accepts', () => {
      expect(parseWholeNumber('42 500')).toBe(42500);
      expect(parseWholeNumber('0')).toBe(0);
    });

    it('answers null, not NaN or zero, for anything else', () => {
      // Zero is a real mileage. "Nothing typed" must not turn into it.
      expect(parseWholeNumber('')).toBeNull();
      expect(parseWholeNumber(null)).toBeNull();
      expect(parseWholeNumber('12,5')).toBeNull();
    });
  });

  describe('firstErrorFr', () => {
    it('returns null when there is nothing wrong', () => {
      expect(firstErrorFr(null)).toBeNull();
    });

    it('reads the length out of the error rather than from a constant', () => {
      const control = new FormControl('a'.repeat(200), {
        nonNullable: true,
        validators: [Validators.maxLength(MAX_LENGTHS.companyName)],
      });
      expect(firstErrorFr(control.errors)).toBe(
        `${MAX_LENGTHS.companyName} caractères au maximum.`,
      );
    });

    it('falls back to a generic sentence for a validator it does not know', () => {
      // A field marked invalid with no message is a dead end for the user, so an unmapped
      // validator has to say something.
      expect(firstErrorFr({ somethingNew: true })).toBe('Cette valeur n’est pas valide.');
    });
  });
});
