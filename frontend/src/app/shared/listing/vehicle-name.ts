/**
 * A model typed as "Audi A3", under the make "Audi", is the A3. The same rule as `ListingLabel`
 * on the backend, which names a listing in e-mails: so the site never reads "Audi Audi A3" either.
 */
export function modelWithoutMake(make: string | null | undefined, model: string | null | undefined): string {
  const name = model?.trim() ?? '';
  const prefix = `${make?.trim() ?? ''} `.toLocaleLowerCase('fr-FR');
  if (prefix.trim() && name.toLocaleLowerCase('fr-FR').startsWith(prefix)) {
    return name.slice(prefix.length).trim();
  }
  return name;
}

/** "Audi A3": the make, then the model without the make in it. Either may be missing. */
export function vehicleName(make: string | null | undefined, model: string | null | undefined): string {
  return [make?.trim() ?? '', modelWithoutMake(make, model)].filter(Boolean).join(' ');
}
