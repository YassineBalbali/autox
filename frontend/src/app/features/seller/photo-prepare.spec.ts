import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { ACCEPTED_TYPES, PREPARE, jpegName, needsPreparing, preparePhoto } from './photo-prepare';

const MB = 1024 * 1024;
const file = (name: string, type: string, size: number) => new File([new Uint8Array(size)], name, { type });

/**
 * Making a phone's photo ready to send.
 *
 * <p>jsdom decodes no image and draws on no canvas, so both are stood in for: a bitmap of
 * a stated size, and a canvas that records what it was asked to draw and encodes to a blob
 * of a stated size.
 */
describe('photo preparation', () => {
  describe('which photos are redrawn', () => {
    it('leaves a small JPEG or PNG as it is: a second encoding only loses quality', () => {
      expect(needsPreparing(file('a.jpg', 'image/jpeg', 2 * MB))).toBe(false);
      expect(needsPreparing(file('a.png', 'image/png', PREPARE.keepBelowBytes))).toBe(false);
    });

    it('redraws a heavy one, a WebP, and a file the picker gave no type', () => {
      expect(needsPreparing(file('a.jpg', 'image/jpeg', 7 * MB))).toBe(true);
      expect(needsPreparing(file('a.webp', 'image/webp', 1 * MB))).toBe(true);
      // Android's picker does this; it is usually a JPEG all the same.
      expect(needsPreparing(file('IMG_2041', '', 3 * MB))).toBe(true);
    });

    it('does not try to decode what is not an image', () => {
      expect(needsPreparing(file('carte-grise.pdf', 'application/pdf', 1 * MB))).toBe(false);
    });

    it('offers WebP in the file picker, but not HEIC, which iOS converts itself when not asked for', () => {
      expect(ACCEPTED_TYPES.split(',')).toEqual(['image/jpeg', 'image/png', 'image/webp']);
    });
  });

  describe('the redrawing', () => {
    const drawn: { width: number; height: number; fill: string }[] = [];
    let encodedSize = 900 * 1024;
    let decodable = true;

    beforeEach(() => {
      drawn.length = 0;
      encodedSize = 900 * 1024;
      decodable = true;
      vi.stubGlobal('createImageBitmap', async (_source: Blob, options: ImageBitmapOptions) => {
        if (!decodable) {
          throw new DOMException('The source image could not be decoded.', 'InvalidStateError');
        }
        // Turned the right way up from its EXIF orientation: asked of the decoder.
        expect(options.imageOrientation).toBe('from-image');
        return { width: 4000, height: 3000, close: vi.fn() };
      });
      vi.spyOn(HTMLCanvasElement.prototype, 'getContext').mockImplementation(() => {
        const context = {
          fillStyle: '',
          fillRect: () => undefined,
          drawImage: (_image: unknown, _x: number, _y: number, width: number, height: number) =>
            drawn.push({ width, height, fill: context.fillStyle }),
        };
        return context as unknown as CanvasRenderingContext2D;
      });
      vi.spyOn(HTMLCanvasElement.prototype, 'toBlob').mockImplementation((callback, type) => {
        callback(new Blob([new Uint8Array(encodedSize)], { type: type ?? 'image/png' }));
      });
    });

    afterEach(() => {
      vi.unstubAllGlobals();
      vi.restoreAllMocks();
    });

    it('scales to the long edge, on white, and sends a JPEG named like the original', async () => {
      const prepared = await preparePhoto(file('IMG_2041.webp', 'image/webp', 5 * MB));

      expect(drawn).toEqual([{ width: 2560, height: 1920, fill: '#ffffff' }]);
      expect(prepared.type).toBe('image/jpeg');
      expect(prepared.name).toBe('IMG_2041.jpg');
      expect(prepared.size).toBe(900 * 1024);
    });

    it('keeps the original when redrawing would not make a supported photo smaller', async () => {
      encodedSize = 8 * MB;
      const original = file('big.jpg', 'image/jpeg', 7 * MB);

      expect(await preparePhoto(original)).toBe(original);
    });

    it('leaves a photo it cannot decode to the checks that follow, which say why', async () => {
      decodable = false;
      const heic = file('IMG_2041.HEIC', 'image/heic', 2 * MB);

      expect(await preparePhoto(heic)).toBe(heic);
    });
  });

  it('names the result after the original, with the type it now has', () => {
    expect(jpegName('IMG_2041.HEIC')).toBe('IMG_2041.jpg');
    expect(jpegName('voiture.face.webp')).toBe('voiture.face.jpg');
    expect(jpegName('sans-extension')).toBe('sans-extension.jpg');
    expect(jpegName('.webp')).toBe('photo.jpg');
  });
});
