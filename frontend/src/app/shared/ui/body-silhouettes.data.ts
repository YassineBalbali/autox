// Side views of six kinds of car, for the home page's body-type tiles.
//
// Drawn for AutoX, not traced from anything: generic proportions — a short hatch, a
// three-box saloon, a long roof, a tall SUV, a one-box people carrier, a van — that match no
// model and no manufacturer's drawing. Single-colour outlines in the icon set's style
// (Lucide: 2-unit round strokes, no fill), on a 64 x 32 grid, wider than an icon's because a
// car is. Recorded in docs/third-party-assets.md as the project's own artwork.
//
// Each is a body outline (with the wheel arches cut into its sill), the side windows, and
// two wheels. The drawing component renders paths and circles only.

export interface Silhouette {
  /** SVG path data: the body, then the windows. */
  readonly paths: readonly string[];
  /** The wheels: [cx, cy, r]. */
  readonly wheels: readonly (readonly [number, number, number])[];
}

export const SILHOUETTES = {
  // Short, with a steep tailgate: the hatchback of every town.
  citadine: {
    paths: [
      'M8 25H12.5A5.5 5.5 0 0 1 23.5 25H40.5A5.5 5.5 0 0 1 51.5 25H56V19.5Q56 16.5 53 16L46 15L39 8H21Q15 8 12 12.5L8 17.5Z',
      'M15 14.5L18.8 10H37L41.5 14.5Z',
      'M28 10V14.5',
    ],
    wheels: [
      [18, 25, 4],
      [46, 25, 4],
    ],
  },
  // Three boxes: bonnet, cabin, boot.
  berline: {
    paths: [
      'M3 25H9.5A5.5 5.5 0 0 1 20.5 25H43.5A5.5 5.5 0 0 1 54.5 25H61V19.5Q61 17 58 16.5L49 15.5L41 8.5H23L15.5 15L6 15.8Q3 16.2 3 18.5Z',
      'M19.5 14.5L23.8 10.5H39.5L44 14.5Z',
      'M31 10.5V14.5',
    ],
    wheels: [
      [15, 25, 4],
      [49, 25, 4],
    ],
  },
  // The saloon's roof carried on to the back: a long glasshouse, an upright tailgate.
  break: {
    paths: [
      'M3 25H9.5A5.5 5.5 0 0 1 20.5 25H43.5A5.5 5.5 0 0 1 54.5 25H61V19.5Q61 17 58 16.5L49 15.5L42 8H8Q4.5 8 4 11L3 16Z',
      'M8 14.5V11.5Q8 10.3 9.2 10.3H40L44.5 14.5Z',
      'M20 10.3V14.5',
      'M31 10.3V14.5',
    ],
    wheels: [
      [15, 25, 4],
      [49, 25, 4],
    ],
  },
  // Tall, upright, high off the ground, on bigger wheels.
  suv: {
    paths: [
      'M5 23.5H11A6 6 0 0 1 23 23.5H41A6 6 0 0 1 53 23.5H59V16Q59 13.5 56.5 13L50 12.5L44 6H11Q7 6 6 9.5L5 14Z',
      'M9.5 12L10.7 8.5H42L45.5 12Z',
      'M22 8.5V12',
      'M33 8.5V12',
    ],
    wheels: [
      [17, 24.5, 4.5],
      [47, 24.5, 4.5],
    ],
  },
  // One box: a short nose, a long raked windscreen, a tall roof to the very back.
  monospace: {
    paths: [
      'M5 25H10.5A5.5 5.5 0 0 1 21.5 25H42.5A5.5 5.5 0 0 1 53.5 25H59V19Q59 16.7 57 16.3L51.5 15.5L40 6H10Q6 6 5.5 10Z',
      'M9.5 13.5V9.5Q9.5 8.3 10.7 8.3H39L45.5 13.5Z',
      'M21 8.3V13.5',
      'M32 8.3V13.5',
    ],
    wheels: [
      [16, 25, 4],
      [48, 25, 4],
    ],
  },
  // A van: a flat roof, a square back, a cab window and a panel where the rear windows
  // would be.
  utilitaire: {
    paths: [
      'M3 25H8.5A5.5 5.5 0 0 1 19.5 25H44.5A5.5 5.5 0 0 1 55.5 25H61V18Q61 15.7 59 15.3L54.5 14.5L48 5H3Z',
      'M44.5 13.5V8H47.5L51.5 13.5Z',
      'M40.5 5V21',
    ],
    wheels: [
      [14, 25, 4],
      [50, 25, 4],
    ],
  },
} as const satisfies Record<string, Silhouette>;

export type SilhouetteName = keyof typeof SILHOUETTES;
