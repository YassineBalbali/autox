package com.autox.listing.image;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Iterator;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import org.springframework.stereotype.Component;

/**
 * Decodes an uploaded image and produces resized variants.
 *
 * <p>Uses the JDK's own codecs — no native dependency, nothing to install in a container.
 * Two details matter for output that does not look amateurish:
 * <ul>
 *   <li>Downscaling happens in halving steps rather than one jump. A single bilinear pass
 *       from 4000px to 320px skips most source pixels and produces visible aliasing.</li>
 *   <li>Variants are always written as JPEG, whatever came in. A 4 MB PNG photograph
 *       becomes a 200 KB JPEG at indistinguishable quality, and a phone on mobile data is
 *       the main consumer.</li>
 * </ul>
 */
@Component
public class ImageResizer {

    /** JPEG has no alpha, so transparency is composited onto white before encoding. */
    private static final int WHITE = 0xFFFFFFFF;

    /**
     * Decodes the bytes.
     *
     * @throws UnreadableImageException if no decoder recognises the content — which is also
     *     how a file that merely claims to be an image is caught
     */
    public BufferedImage decode(byte[] content) {
        try {
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(content));
            if (image == null) {
                throw new UnreadableImageException("The uploaded file is not a readable JPEG or PNG image.");
            }
            return image;
        } catch (IOException e) {
            throw new UnreadableImageException("The uploaded file could not be decoded as an image.");
        }
    }

    /**
     * Scales to fit inside {@code maxEdge} without cropping, never enlarging, and encodes.
     *
     * <p>Returns the dimensions alongside the bytes because the caller needs both and
     * recomputing the scale separately would resize every variant twice.
     */
    public Rendition render(BufferedImage source, int maxEdge, float quality) {
        BufferedImage scaled = scaleToFit(source, maxEdge);
        return new Rendition(encodeJpeg(scaled, quality), scaled.getWidth(), scaled.getHeight());
    }

    BufferedImage scaleToFit(BufferedImage source, int maxEdge) {
        int width = source.getWidth();
        int height = source.getHeight();

        if (width <= maxEdge && height <= maxEdge) {
            return flatten(source);
        }

        double scale = (double) maxEdge / Math.max(width, height);
        int targetWidth = Math.max(1, (int) Math.round(width * scale));
        int targetHeight = Math.max(1, (int) Math.round(height * scale));

        BufferedImage current = flatten(source);
        // Halve repeatedly while that stays above the target, then do the final step.
        while (current.getWidth() / 2 >= targetWidth && current.getHeight() / 2 >= targetHeight) {
            current = redraw(current, current.getWidth() / 2, current.getHeight() / 2);
        }
        return redraw(current, targetWidth, targetHeight);
    }

    private static BufferedImage redraw(BufferedImage source, int width, int height) {
        BufferedImage target = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = target.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            graphics.drawImage(source, 0, 0, width, height, null);
        } finally {
            graphics.dispose();
        }
        return target;
    }

    /** Drops alpha and any exotic colour model, so every variant encodes predictably. */
    private static BufferedImage flatten(BufferedImage source) {
        if (source.getType() == BufferedImage.TYPE_INT_RGB) {
            return source;
        }
        BufferedImage flat = new BufferedImage(source.getWidth(), source.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = flat.createGraphics();
        try {
            graphics.setColor(new java.awt.Color(WHITE, true));
            graphics.fillRect(0, 0, source.getWidth(), source.getHeight());
            graphics.drawImage(source, 0, 0, null);
        } finally {
            graphics.dispose();
        }
        return flat;
    }

    private static byte[] encodeJpeg(BufferedImage image, float quality) {
        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpeg");
        if (!writers.hasNext()) {
            throw new IllegalStateException("No JPEG writer available in this JVM");
        }
        ImageWriter writer = writers.next();

        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (MemoryCacheImageOutputStream output = new MemoryCacheImageOutputStream(bytes)) {
            writer.setOutput(output);

            ImageWriteParam params = writer.getDefaultWriteParam();
            if (params.canWriteCompressed()) {
                params.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
                params.setCompressionQuality(quality);
            }
            writer.write(null, new IIOImage(image, null, null), params);
            output.flush();
            return bytes.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("Failed to encode a JPEG variant", e);
        } finally {
            writer.dispose();
        }
    }

    /** An encoded variant and the dimensions it ended up with. */
    public record Rendition(byte[] content, int width, int height) {
    }

    /** Thrown when bytes that claimed to be an image are not one. */
    public static class UnreadableImageException extends RuntimeException {

        private static final long serialVersionUID = 1L;

        public UnreadableImageException(String message) {
            super(message);
        }
    }
}
