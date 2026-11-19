import { NgTemplateOutlet } from '@angular/common';
import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';
import { RouterLink } from '@angular/router';

import { guide as guideById } from './guide';
import { ContactAddress, InfoPage } from './info-page';

/**
 * One help page from frontend/content/help.fr.json, in the frame every footer page uses. The
 * elements are written straight into InfoPage's body, as the pages' own templates did, so its
 * styles apply to them unchanged.
 */
@Component({
  selector: 'app-guide-page',
  imports: [InfoPage, ContactAddress, RouterLink, NgTemplateOutlet],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <app-info-page [heading]="page().title" [lede]="page().lede ?? ''">
      @for (section of page().sections; track section.id) {
        @if (section.heading) {
          <h2>{{ section.heading }}</h2>
        }
        @for (block of section.blocks; track $index) {
          @switch (block.kind) {
            @case ('p') {
              <p>
                <ng-container *ngTemplateOutlet="text; context: { $implicit: block.segments }" />
              </p>
            }
            @case ('ul') {
              <ul>
                @for (item of block.items; track $index) {
                  <li><ng-container *ngTemplateOutlet="text; context: { $implicit: item }" /></li>
                }
              </ul>
            }
            @case ('ol') {
              <ol>
                @for (item of block.items; track $index) {
                  <li><ng-container *ngTemplateOutlet="text; context: { $implicit: item }" /></li>
                }
              </ol>
            }
          }
        }
      }
    </app-info-page>

    <ng-template #text let-segments>
      @for (segment of segments; track $index) {
        @if (segment.contact) {
          <app-contact-address />
        } @else if (segment.path && segment.strong) {
          <strong
            ><a [routerLink]="segment.path">{{ segment.text }}</a></strong
          >
        } @else if (segment.path) {
          <a [routerLink]="segment.path">{{ segment.text }}</a>
        } @else if (segment.strong) {
          <strong>{{ segment.text }}</strong>
        } @else {
          <ng-container>{{ segment.text }}</ng-container>
        }
      }
    </ng-template>
  `,
})
export class GuidePage {
  /** The page's id in help.fr.json, which is its path without the slash. */
  readonly guide = input.required<string>();

  protected readonly page = computed(() => guideById(this.guide()));
}
