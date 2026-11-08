package com.autox.demo;

import static org.assertj.core.api.Assertions.assertThat;

import com.autox.catalog.BodyType;
import com.autox.listing.ExteriorColor;
import java.awt.image.BufferedImage;
import java.util.EnumMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The demo's drawings: one silhouette per body type, so a van does not look like a citadine. */
class DemoPhotoPainterTest {

    @Test
    @DisplayName("every body type is drawn with its own silhouette")
    void eachBodyHasItsOwnSilhouette() {
        Map<BodyType, boolean[]> silhouettes = new EnumMap<>(BodyType.class);
        for (BodyType body : BodyType.values()) {
            silhouettes.put(body, silhouette(DemoPhotoPainter.paint(body, ExteriorColor.RED, "", 0)));
        }

        for (BodyType one : BodyType.values()) {
            for (BodyType other : BodyType.values()) {
                // OTHER is drawn as the plainest car, a citadine.
                boolean plain = one == BodyType.OTHER || other == BodyType.OTHER;
                if (one.ordinal() < other.ordinal() && !plain) {
                    // A share of the frame, not a pixel or two: the difference has to show in a
                    // thumbnail.
                    assertThat(difference(silhouettes.get(one), silhouettes.get(other)))
                            .as(one + " against " + other)
                            .isGreaterThan(0.015);
                }
            }
        }
    }

    @Test
    @DisplayName("a tall body is drawn taller: a van over a monospace over an SUV over a saloon")
    void heightsFollowTheBody() {
        int van = top(BodyType.VAN);
        int mpv = top(BodyType.MPV);
        int suv = top(BodyType.SUV);
        int sedan = top(BodyType.SEDAN);
        // Smaller y is higher in the picture.
        assertThat(van).isLessThan(mpv);
        assertThat(mpv).isLessThan(suv);
        assertThat(suv).isLessThan(sedan);
    }

    /** Where the car is: the pixels of its red body, its dark glass and its wheels. */
    private static boolean[] silhouette(BufferedImage image) {
        boolean[] car = new boolean[image.getWidth() * image.getHeight()];
        // Above the caption, which is the same in every picture.
        for (int y = 0; y < 1000; y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                int rgb = image.getRGB(x, y);
                int r = (rgb >> 16) & 0xFF;
                int g = (rgb >> 8) & 0xFF;
                int b = rgb & 0xFF;
                boolean red = r > 120 && g < 90 && b < 90;
                boolean dark = r < 80 && g < 80 && b < 90;
                car[y * image.getWidth() + x] = red || dark;
            }
        }
        return car;
    }

    private static double difference(boolean[] one, boolean[] other) {
        int differ = 0;
        for (int i = 0; i < one.length; i++) {
            if (one[i] != other[i]) {
                differ++;
            }
        }
        return differ / (double) one.length;
    }

    private static int top(BodyType body) {
        boolean[] car = silhouette(DemoPhotoPainter.paint(body, ExteriorColor.RED, "", 0));
        for (int i = 0; i < car.length; i++) {
            if (car[i]) {
                return i / DemoPhotoPainter.WIDTH;
            }
        }
        return Integer.MAX_VALUE;
    }
}
