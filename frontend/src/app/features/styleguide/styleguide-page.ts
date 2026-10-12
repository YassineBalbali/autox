import { ChangeDetectionStrategy, Component, signal } from '@angular/core';
import { FormControl, ReactiveFormsModule } from '@angular/forms';

import { Logo } from '../../shared/brand/logo';
import { TextField } from '../../shared/forms/text-field';
import { emailValidators } from '../../shared/forms/validation';
import { Price } from '../../shared/listing/price';
import { SpecLine } from '../../shared/listing/spec-line';
import { Alert } from '../../shared/ui/alert';
import { FilterChip } from '../../shared/ui/filter-chip';

interface Swatch {
  readonly token: string;
  readonly use: string;
}

interface SampleRow {
  readonly title: string;
  readonly version: string;
  readonly priceMinor: number;
  readonly year: number;
  readonly mileage: number;
  readonly fuel: string;
  readonly gearbox: string;
  readonly powerHp: number;
  readonly place: string;
  readonly pro: boolean;
}

/**
 * The design reference: every token and every shared component, rendered.
 *
 * <p>It exists for review. Three of the components here — the price, the spec line and
 * the filter chip — are what the listing screens will be assembled from, and those
 * screens are not built yet; without this page the pieces that define the look of the
 * site would have nowhere to be seen. The vehicles in the sample list are invented, for
 * the purpose of showing a row at real density, and are labelled as such on the page.
 *
 * <p>Nothing here is a second definition of anything. The swatches read the live custom
 * properties and the components are the real ones, so the page cannot disagree with the
 * application — if it looks wrong here, it is wrong everywhere.
 */
@Component({
  selector: 'app-styleguide-page',
  imports: [ReactiveFormsModule, Logo, Price, SpecLine, FilterChip, Alert, TextField],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './styleguide-page.html',
  styleUrl: './styleguide-page.scss',
})
export class StyleguidePage {
  protected readonly brand: Swatch[] = [
    { token: '--c-navy', use: 'Logo « Auto », texte, filet de l’en-tête' },
    { token: '--c-accent', use: 'Logo « X », prix, bouton principal, filtre actif' },
    { token: '--c-accent-strong', use: 'Survol du bouton principal' },
    { token: '--c-accent-soft', use: 'Fond d’un filtre actif' },
  ];

  protected readonly neutrals: Swatch[] = [
    { token: '--c-page', use: 'Fond de page' },
    { token: '--c-surface', use: 'Panneaux, lignes, champs' },
    { token: '--c-surface-sunk', use: 'En-têtes de panneau, survol' },
    { token: '--c-line', use: 'Séparateurs entre lignes' },
    { token: '--c-line-strong', use: 'Bordures de panneau et de champ' },
    { token: '--c-ink', use: 'Texte principal' },
    { token: '--c-ink-soft', use: 'Libellés, texte secondaire' },
    { token: '--c-ink-faint', use: 'Légendes, unités' },
  ];

  protected readonly status: Swatch[] = [
    { token: '--c-danger', use: 'Erreur, action destructive' },
    { token: '--c-success', use: 'Confirmation' },
    { token: '--c-warn', use: 'Avertissement' },
  ];

  protected readonly typeScale = [
    { token: '--t-2xl', px: 32, use: 'Prix sur une annonce' },
    { token: '--t-xl', px: 26, use: 'Titre de page' },
    { token: '--t-lg', px: 20, use: 'Prix dans une liste' },
    { token: '--t-md', px: 17, use: 'Titre de ligne' },
    { token: '--t-body', px: 15, use: 'Texte courant, champs, boutons' },
    { token: '--t-sm', px: 13, use: 'Caractéristiques, libellés' },
    { token: '--t-xs', px: 12, use: 'Légendes' },
    { token: '--t-2xs', px: 11, use: 'Étiquettes, unités' },
  ];

  protected readonly spacing = [
    { token: '--s-0', px: 2 },
    { token: '--s-1', px: 4 },
    { token: '--s-2', px: 8 },
    { token: '--s-3', px: 12 },
    { token: '--s-4', px: 16 },
    { token: '--s-5', px: 24 },
    { token: '--s-6', px: 32 },
    { token: '--s-7', px: 48 },
  ];

  protected readonly samples: SampleRow[] = [
    {
      title: 'Peugeot 308',
      version: '1.5 BlueHDi 130 Allure Pack',
      priceMinor: 1899000,
      year: 2021,
      mileage: 62400,
      fuel: 'Diesel',
      gearbox: 'Manuelle',
      powerHp: 130,
      place: 'Lyon (69)',
      pro: true,
    },
    {
      title: 'Renault Clio V',
      version: '1.0 TCe 90 Evolution',
      priceMinor: 1349000,
      year: 2022,
      mileage: 38150,
      fuel: 'Essence',
      gearbox: 'Manuelle',
      powerHp: 90,
      place: 'Nantes (44)',
      pro: false,
    },
    {
      title: 'Volkswagen Golf VIII',
      version: '2.0 TDI 150 DSG7 Style',
      priceMinor: 2649000,
      year: 2020,
      mileage: 104300,
      fuel: 'Diesel',
      gearbox: 'Automatique',
      powerHp: 150,
      place: 'Strasbourg (67)',
      pro: true,
    },
    {
      title: 'Tesla Model 3',
      version: 'Grande Autonomie AWD',
      priceMinor: 3199000,
      year: 2022,
      mileage: 47800,
      fuel: 'Électrique',
      gearbox: 'Automatique',
      powerHp: 440,
      place: 'Paris (75)',
      pro: false,
    },
  ];

  protected readonly filters = signal([
    { label: 'Diesel', count: 1284, active: true },
    { label: 'Essence', count: 2051, active: false },
    { label: 'Électrique', count: 312, active: false },
    { label: 'Boîte automatique', count: 977, active: true },
    { label: 'Professionnel', count: 1630, active: false },
  ]);

  protected readonly email = new FormControl('pas-une-adresse', {
    nonNullable: true,
    validators: emailValidators,
  });

  protected readonly name = new FormControl('Garage Bellevue', { nonNullable: true });

  constructor() {
    // Touched, so the reference shows the field's error state without anyone having to
    // click into it and out again.
    this.email.markAsTouched();
  }

  protected toggle(label: string, active: boolean): void {
    this.filters.update((filters) =>
      filters.map((filter) => (filter.label === label ? { ...filter, active } : filter)),
    );
  }
}
