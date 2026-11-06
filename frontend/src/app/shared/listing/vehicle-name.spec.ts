import { describe, expect, it } from 'vitest';

import { modelWithoutMake, vehicleName } from './vehicle-name';

describe('vehicleName', () => {
  it('names the make once when the model was typed with it', () => {
    expect(vehicleName('Audi', 'Audi A3')).toBe('Audi A3');
    expect(vehicleName('AUDI', 'audi a3')).toBe('AUDI a3');
    expect(vehicleName(' Audi ', '  Audi   A3 ')).toBe('Audi A3');
  });

  it('leaves a model that only begins like the make', () => {
    expect(vehicleName('Audi', 'Audition')).toBe('Audi Audition');
    expect(vehicleName('Mini', 'Mini')).toBe('Mini Mini');
  });

  it('copes with either part missing', () => {
    expect(vehicleName('Audi', null)).toBe('Audi');
    expect(vehicleName(null, 'A3')).toBe('A3');
    expect(vehicleName(undefined, undefined)).toBe('');
    expect(modelWithoutMake('', 'Audi A3')).toBe('Audi A3');
  });
});
