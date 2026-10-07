package com.autox.listing.image;

import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.Arrays;

/**
 * Reads and removes image metadata.
 *
 * <p>A phone photograph carries EXIF, and EXIF carries GPS coordinates — frequently the
 * seller's home address. Nothing in this product uses that data, so it is removed from both
 * the variants and the stored original.
 *
 * <p>The complication is <b>orientation</b>. Cameras usually write the sensor's pixels
 * unrotated and record "turn this 90° right" as an EXIF tag. Strip the tag without acting on
 * it and every portrait photo ends up sideways. So orientation is read first, applied to the
 * pixels, and only then discarded — which is also why a rotated original has to be re-encoded
 * rather than merely stripped.
 *
 * <p>Stripping itself is lossless: JPEG metadata lives in APPn and COM marker segments that
 * can be dropped without touching the compressed image data, and PNG keeps it in ancillary
 * chunks that can be filtered out the same way.
 */
public final class ImageMetadata {

    /** EXIF value meaning "already the right way up". */
    public static final int ORIENTATION_NORMAL = 1;

    private static final int MARKER_PREFIX = 0xFF;
    private static final int SOI = 0xD8;
    private static final int SOS = 0xDA;
    private static final int EOI = 0xD9;
    private static final int COM = 0xFE;
    private static final int APP0 = 0xE0;
    private static final int APP15 = 0xEF;

    private ImageMetadata() {
    }

    // --- orientation ------------------------------------------------------

    /**
     * Reads the EXIF orientation of a JPEG, or {@link #ORIENTATION_NORMAL} when there is no
     * EXIF, it is unreadable, or the file is not a JPEG. A malformed tag is never a reason to
     * reject an upload — the photograph is still fine.
     */
    public static int readJpegOrientation(byte[] content) {
        try {
            return findOrientation(content);
        } catch (RuntimeException unreadable) {
            return ORIENTATION_NORMAL;
        }
    }

    private static int findOrientation(byte[] content) {
        if (!isJpeg(content)) {
            return ORIENTATION_NORMAL;
        }

        int offset = 2;
        while (offset + 4 <= content.length) {
            if ((content[offset] & 0xFF) != MARKER_PREFIX) {
                return ORIENTATION_NORMAL;
            }
            int marker = content[offset + 1] & 0xFF;
            if (marker == SOS || marker == EOI) {
                return ORIENTATION_NORMAL;
            }

            int length = ((content[offset + 2] & 0xFF) << 8) | (content[offset + 3] & 0xFF);
            if (length < 2 || offset + 2 + length > content.length) {
                return ORIENTATION_NORMAL;
            }

            // APP1 holding "Exif\0\0" is the one that matters.
            if (marker == 0xE1 && length >= 8 && startsWithExif(content, offset + 4)) {
                return readOrientationFromTiff(content, offset + 10, offset + 2 + length);
            }
            offset += 2 + length;
        }
        return ORIENTATION_NORMAL;
    }

    private static boolean startsWithExif(byte[] content, int at) {
        return content.length > at + 5
                && content[at] == 'E' && content[at + 1] == 'x' && content[at + 2] == 'i'
                && content[at + 3] == 'f' && content[at + 4] == 0;
    }

    /** Walks the TIFF header inside the EXIF segment to tag 0x0112 (Orientation). */
    private static int readOrientationFromTiff(byte[] content, int tiffStart, int limit) {
        if (tiffStart + 8 > limit) {
            return ORIENTATION_NORMAL;
        }
        boolean bigEndian = content[tiffStart] == 'M' && content[tiffStart + 1] == 'M';
        boolean littleEndian = content[tiffStart] == 'I' && content[tiffStart + 1] == 'I';
        if (!bigEndian && !littleEndian) {
            return ORIENTATION_NORMAL;
        }

        int ifdOffset = readInt(content, tiffStart + 4, bigEndian);
        int ifd = tiffStart + ifdOffset;
        if (ifd + 2 > limit) {
            return ORIENTATION_NORMAL;
        }

        int entries = readShort(content, ifd, bigEndian);
        for (int i = 0; i < entries; i++) {
            int entry = ifd + 2 + (i * 12);
            if (entry + 12 > limit) {
                return ORIENTATION_NORMAL;
            }
            if (readShort(content, entry, bigEndian) == 0x0112) {
                int value = readShort(content, entry + 8, bigEndian);
                return value >= 1 && value <= 8 ? value : ORIENTATION_NORMAL;
            }
        }
        return ORIENTATION_NORMAL;
    }

    /** Rotates and flips pixels so the image is upright without any metadata. */
    public static BufferedImage applyOrientation(BufferedImage source, int orientation) {
        if (orientation <= ORIENTATION_NORMAL || orientation > 8) {
            return source;
        }

        int width = source.getWidth();
        int height = source.getHeight();
        // Orientations 5-8 transpose the image, so the canvas swaps dimensions.
        boolean swaps = orientation >= 5;
        BufferedImage target = new BufferedImage(swaps ? height : width, swaps ? width : height,
                BufferedImage.TYPE_INT_RGB);

        AffineTransform transform = switch (orientation) {
            case 2 -> flipHorizontal(width);
            case 3 -> rotate(Math.PI, width, height);
            case 4 -> flipVertical(height);
            case 5 -> transpose();
            case 6 -> rotateRight(height);
            case 7 -> transverse(height, width);
            case 8 -> rotateLeft(width);
            default -> new AffineTransform();
        };

        // Drawn through Graphics2D rather than AffineTransformOp.filter: the decoder hands
        // back whatever colour model the file used (JPEG usually 3BYTE_BGR), and filter()
        // refuses to write that into an INT_RGB destination. The rendering pipeline converts.
        java.awt.Graphics2D graphics = target.createGraphics();
        try {
            graphics.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION,
                    java.awt.RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            graphics.setRenderingHint(java.awt.RenderingHints.KEY_RENDERING,
                    java.awt.RenderingHints.VALUE_RENDER_QUALITY);
            graphics.drawImage(source, transform, null);
        } finally {
            graphics.dispose();
        }
        return target;
    }

    private static AffineTransform flipHorizontal(int width) {
        AffineTransform transform = AffineTransform.getScaleInstance(-1, 1);
        transform.translate(-width, 0);
        return transform;
    }

    private static AffineTransform flipVertical(int height) {
        AffineTransform transform = AffineTransform.getScaleInstance(1, -1);
        transform.translate(0, -height);
        return transform;
    }

    private static AffineTransform rotate(double radians, int width, int height) {
        AffineTransform transform = AffineTransform.getTranslateInstance(width, height);
        transform.rotate(radians);
        return transform;
    }

    private static AffineTransform rotateRight(int height) {
        AffineTransform transform = AffineTransform.getTranslateInstance(height, 0);
        transform.rotate(Math.PI / 2);
        return transform;
    }

    private static AffineTransform rotateLeft(int width) {
        AffineTransform transform = AffineTransform.getTranslateInstance(0, width);
        transform.rotate(3 * Math.PI / 2);
        return transform;
    }

    private static AffineTransform transpose() {
        AffineTransform transform = AffineTransform.getRotateInstance(Math.PI / 2);
        transform.scale(1, -1);
        return transform;
    }

    private static AffineTransform transverse(int height, int width) {
        AffineTransform transform = AffineTransform.getTranslateInstance(height, width);
        transform.rotate(3 * Math.PI / 2);
        transform.scale(1, -1);
        return transform;
    }

    // --- stripping --------------------------------------------------------

    /** Removes metadata from a JPEG or PNG, returning the input unchanged for anything else. */
    public static byte[] strip(byte[] content) {
        if (isJpeg(content)) {
            return stripJpeg(content);
        }
        if (isPng(content)) {
            return stripPng(content);
        }
        return content;
    }

    /**
     * Drops every APPn and COM segment. The compressed scan data is copied verbatim, so this
     * cannot degrade the image.
     *
     * <p>APP0/JFIF is dropped with the rest: it holds only density hints, and decoders treat
     * its absence as the default.
     */
    private static byte[] stripJpeg(byte[] content) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(content.length);
        out.write(MARKER_PREFIX);
        out.write(SOI);

        int offset = 2;
        while (offset + 4 <= content.length) {
            if ((content[offset] & 0xFF) != MARKER_PREFIX) {
                break;
            }
            int marker = content[offset + 1] & 0xFF;

            if (marker == SOS) {
                // Everything from here to the end is entropy-coded data; copy it as-is.
                out.write(content, offset, content.length - offset);
                return out.toByteArray();
            }

            int length = ((content[offset + 2] & 0xFF) << 8) | (content[offset + 3] & 0xFF);
            if (length < 2 || offset + 2 + length > content.length) {
                break;
            }

            boolean isMetadata = (marker >= APP0 && marker <= APP15) || marker == COM;
            if (!isMetadata) {
                out.write(content, offset, 2 + length);
            }
            offset += 2 + length;
        }

        // Anything unparseable is returned untouched rather than corrupted.
        return offset >= content.length ? out.toByteArray() : content;
    }

    /**
     * Keeps only the chunks needed to render: the critical ones plus transparency and colour
     * information. Everything else — {@code eXIf}, {@code tEXt}, {@code iTXt}, timestamps — is
     * dropped.
     */
    private static byte[] stripPng(byte[] content) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(content.length);
        out.write(content, 0, 8);

        int offset = 8;
        while (offset + 12 <= content.length) {
            int length = readInt(content, offset, true);
            if (length < 0 || offset + 12 + length > content.length) {
                return content;
            }
            String type = new String(content, offset + 4, 4, java.nio.charset.StandardCharsets.US_ASCII);

            if (KEPT_PNG_CHUNKS.contains(type)) {
                out.write(content, offset, 12 + length);
            }
            offset += 12 + length;

            if ("IEND".equals(type)) {
                break;
            }
        }
        return out.toByteArray();
    }

    private static final java.util.Set<String> KEPT_PNG_CHUNKS =
            java.util.Set.of("IHDR", "PLTE", "IDAT", "IEND", "tRNS", "gAMA", "cHRM", "sRGB", "iCCP");

    // --- primitives -------------------------------------------------------

    public static boolean isJpeg(byte[] content) {
        return content.length > 3 && (content[0] & 0xFF) == MARKER_PREFIX && (content[1] & 0xFF) == SOI;
    }

    public static boolean isPng(byte[] content) {
        return content.length > 8 && Arrays.equals(Arrays.copyOf(content, 8), PNG_SIGNATURE);
    }

    private static final byte[] PNG_SIGNATURE =
            {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'};

    private static int readShort(byte[] content, int at, boolean bigEndian) {
        int first = content[at] & 0xFF;
        int second = content[at + 1] & 0xFF;
        return bigEndian ? (first << 8) | second : (second << 8) | first;
    }

    private static int readInt(byte[] content, int at, boolean bigEndian) {
        int b0 = content[at] & 0xFF;
        int b1 = content[at + 1] & 0xFF;
        int b2 = content[at + 2] & 0xFF;
        int b3 = content[at + 3] & 0xFF;
        return bigEndian
                ? (b0 << 24) | (b1 << 16) | (b2 << 8) | b3
                : (b3 << 24) | (b2 << 16) | (b1 << 8) | b0;
    }
}
