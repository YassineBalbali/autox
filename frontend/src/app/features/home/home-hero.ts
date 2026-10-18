import { ChangeDetectionStrategy, Component } from '@angular/core';

import { HomeFinder } from './home-finder';

/**
 * The top of the home page: a full-width photo band with the search card on the left and
 * the headline on the right.
 *
 * <p>The photo is decorative — a street of cars, saying nothing the headline does not — so
 * it carries no alternative text. Its source and licence are in
 * docs/third-party-assets.md.
 */
@Component({
  selector: 'app-home-hero',
  imports: [HomeFinder],
  templateUrl: './home-hero.html',
  styleUrl: './home-hero.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class HomeHero {}
