/**
 * How a photo from a phone is made ready before it is sent.
 *
 * <p>A phone's camera roll is the opposite of what the upload expects: photos of 12 to 48
 * megapixels, several megabytes each and sometimes over the API's size limit, sometimes WebP,
 * occasionally with no type reported by the picker at all — and sent over a mobile
 * connection. So a photo the API would refuse, or that is simply heavy, is redrawn in the
 * browser first: turned the right way up from its EXIF orientation, scaled to at most
 * {@link PREPARE.maxEdge} pixels on its long edge — more than the 1600px the site ever
 * shows — and encoded as a JPEG.
 *
 * <p>Redrawing also drops the file's metadata, the photo's GPS position among it: the
 * seller's home address is not something a car listing should carry by accident.
 *
 * <p>A photo that is already small and in a type the API takes is sent as it is: a second
 * JPEG encoding only loses quality. One the browser cannot decode — a HEIC file in a
 * browser without HEIC support — is left alone, and the checks that follow say why it
 * cannot be sent. (iOS needs no help here: it converts HEIC to JPEG itself when the file
 * input does not ask for HEIC, which is why HEIC is not in the input's accept list.)
 */
/**
 * The types the API takes as uploaded, which the file input and the conversion of WebP are
 * built around (and the French "JPEG ou PNG" with them). The numbers — largest file, fewest
 * pixels, most photos — are the API's, read from it: see `PhotoLimits`.
 */
export const PHOTO_TYPES: readonly string[] = ['image/jpeg', 'image/png'];

export const PREPARE = {
  maxEdge: 2560,
  quality: 0.85,
  /** A supported file up to this size is sent untouched. */
  keepBelowBytes: 3 * 1024 * 1024,
} as const;

/** What the file input offers: the types the API takes, and WebP, which is converted. */
export const ACCEPTED_TYPES = [...PHOTO_TYPES, 'image/webp'].join(',');

/** Whether a file is to be redrawn before it is sent. Decided at once, without decoding. */
export function needsPreparing(file: File): boolean {
  const supported = PHOTO_TYPES.includes(file.type);
  if (supported) {
    return file.size > PREPARE.keepBelowBytes;
  }
  // An image of another type, or a file the picker gave no type at all, which on Android
  // is usually a JPEG all the same: worth trying to decode. Anything else is not a photo.
  return file.type === '' || file.type.startsWith('image/');
}

/**
 * The photo as it will be sent: redrawn as described above, or the file itself when it
 * cannot be decoded or redrawing would not make it smaller.
 */
export async function preparePhoto(file: File): Promise<File> {
  let bitmap: ImageBitmap;
  try {
    bitmap = await createImageBitmap(file, { imageOrientation: 'from-image' });
  } catch {
    return file;
  }

  const scale = Math.min(1, PREPARE.maxEdge / Math.max(bitmap.width, bitmap.height));
  const width = Math.max(1, Math.round(bitmap.width * scale));
  const height = Math.max(1, Math.round(bitmap.height * scale));
  const canvas = document.createElement('canvas');
  canvas.width = width;
  canvas.height = height;
  const context = canvas.getContext('2d');
  if (!context) {
    bitmap.close();
    return file;
  }
  // White under a transparent PNG: a JPEG has no transparency, and black would show.
  context.fillStyle = '#ffffff';
  context.fillRect(0, 0, width, height);
  context.drawImage(bitmap, 0, 0, width, height);
  bitmap.close();

  const blob = await new Promise<Blob | null>((resolve) => canvas.toBlob(resolve, 'image/jpeg', PREPARE.quality));
  const supported = PHOTO_TYPES.includes(file.type);
  if (!blob || (supported && blob.size >= file.size)) {
    return file;
  }
  return new File([blob], jpegName(file.name), { type: 'image/jpeg', lastModified: file.lastModified });
}

/**
 * "IMG_2041.HEIC" → "IMG_2041.jpg": the name the seller recognises, with the type it now has.
 * The extension is replaced, never added to: every image extension at the end goes, so a file
 * saved from a web page as "photo.jpg.webp" becomes "photo.jpg", not "photo.jpg.jpg".
 */
export function jpegName(name: string): string {
  const imageExtensions = /(\.(jpe?g|jfif|png|webp|heic|heif|avif|gif|bmp|tiff?))+$/i;
  const stem = imageExtensions.test(name) ? name.replace(imageExtensions, '') : name.replace(/\.[^./\\]+$/, '');
  return `${stem || 'photo'}.jpg`;
}

/**
 * The photo's width and height in pixels, or `null` when the browser cannot decode it — the
 * API then decides. The shorter edge is the same whichever way up the photo is shown.
 */
export async function photoSize(file: Blob): Promise<{ width: number; height: number } | null> {
  try {
    const bitmap = await createImageBitmap(file);
    const size = { width: bitmap.width, height: bitmap.height };
    bitmap.close();
    return size;
  } catch {
    return null;
  }
}
