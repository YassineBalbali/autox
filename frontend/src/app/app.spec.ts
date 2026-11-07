import { describe, expect, it } from 'vitest';

import { isBackOffice } from './app';

describe('isBackOffice', () => {
  it('is the admin pages, whatever their query or fragment', () => {
    expect(isBackOffice('/admin')).toBe(true);
    expect(isBackOffice('/admin/moderation?vue=retirees')).toBe(true);
    expect(isBackOffice('/admin/utilisateurs/42')).toBe(true);
    expect(isBackOffice('/admin?periode=jour')).toBe(true);
    expect(isBackOffice('/admin#contenu')).toBe(true);
  });

  it('is not a public page, even one whose path starts the same way', () => {
    expect(isBackOffice('/')).toBe(false);
    expect(isBackOffice('/administration')).toBe(false);
    expect(isBackOffice('/recherche?q=admin')).toBe(false);
    expect(isBackOffice('/compte')).toBe(false);
  });
});
