package com.autox.demo;

import com.autox.catalog.BodyType;
import com.autox.listing.ExteriorColor;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Path2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;

/**
 * Draws a placeholder picture of a car for a demo listing.
 *
 * <p>A drawing, not a photograph, and marked as one in the corner. The demo listings need
 * images to go through the real upload pipeline and to fill the photo slot in a results
 * row, but shipping photographs in the repository means shipping somebody's copyright and
 * several megabytes of binary — and a stock photo of a specific car next to an invented
 * price is a small lie. A silhouette in the listing's own colour and body shape says
 * "there is a picture here" and nothing more.
 *
 * <p>To look at the screen with real photographs, point {@code autox.demo.photo-dir} at a
 * folder of JPEGs; see {@link DemoDataSeeder}.
 */
final class DemoPhotoPainter {

    static final int WIDTH = 1600;
    static final int HEIGHT = 1200;

    private static final int GROUND = 860;
    private static final double CAR_SCALE = 1.28;

    private DemoPhotoPainter() {
    }

    static BufferedImage paint(BodyType body, ExteriorColor colour, String caption) {
        BufferedImage image = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

            // A studio backdrop: pale wall, slightly darker floor.
            g.setPaint(new GradientPaint(0, 0, new Color(0xE9EDF2), 0, GROUND, new Color(0xD3DAE3)));
            g.fillRect(0, 0, WIDTH, GROUND);
            g.setPaint(new GradientPaint(0, GROUND, new Color(0xB4BDC9), 0, HEIGHT, new Color(0xCBD2DC)));
            g.fillRect(0, GROUND, WIDTH, HEIGHT - GROUND);

            Shape shape = Shape.of(body);
            Color paint = rgb(colour);

            // Drawn larger than its measurements, scaled about the point where it meets
            // the floor. A results row shows this at 144 pixels wide, and at that size a
            // car that fills two thirds of the frame is a smudge.
            java.awt.geom.AffineTransform unscaled = g.getTransform();
            g.translate(WIDTH / 2.0, GROUND);
            g.scale(CAR_SCALE, CAR_SCALE);
            g.translate(-WIDTH / 2.0, -GROUND);

            // Contact shadow first, so the car sits on the floor rather than floating.
            g.setColor(new Color(20, 30, 45, 70));
            g.fill(new Ellipse2D.Double(shape.x0 - 30, GROUND - 22, shape.length + 60, 60));

            g.setColor(paint);
            g.fill(shape.body());
            g.setColor(darker(paint, 0.72));
            g.setStroke(new BasicStroke(5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.draw(shape.body());

            g.setColor(new Color(0x1F2A3A));
            g.fill(shape.glass());

            // The pillar between the two side windows.
            g.setColor(paint);
            g.fill(new RoundRectangle2D.Double(shape.pillarX - 14, shape.roofY + 14, 28,
                    shape.beltY - shape.roofY - 8, 8, 8));

            // A sill line and the lights: just enough detail to read as a car.
            g.setColor(darker(paint, 0.8));
            g.setStroke(new BasicStroke(6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.drawLine(shape.x0 + 200, shape.sillY, shape.x0 + shape.length - 200, shape.sillY);
            g.setColor(new Color(0xF6E7A8));
            g.fill(new RoundRectangle2D.Double(shape.x0 + shape.length - 78, shape.beltY + 34, 62, 30, 14, 14));
            g.setColor(new Color(0xB3262D));
            g.fill(new RoundRectangle2D.Double(shape.x0 + 14, shape.beltY + 34, 48, 28, 12, 12));

            for (int centre : new int[] {shape.x0 + shape.rearAxle, shape.x0 + shape.frontAxle}) {
                wheel(g, centre, GROUND - shape.wheelRadius + 6, shape.wheelRadius);
            }

            g.setTransform(unscaled);

            // Says what it is. Nobody should mistake this for the vehicle.
            g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 34));
            g.setColor(new Color(0x46546A));
            g.drawString(caption, 56, HEIGHT - 96);
            g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 28));
            g.setColor(new Color(0x647082));
            g.drawString("Image de démonstration — ne représente pas un véhicule réel", 56, HEIGHT - 52);
        } finally {
            g.dispose();
        }
        return image;
    }

    private static void wheel(Graphics2D g, int cx, int cy, int r) {
        g.setColor(new Color(0x14181F));
        g.fill(new Ellipse2D.Double(cx - r, cy - r, 2.0 * r, 2.0 * r));
        g.setColor(new Color(0xC5CCD6));
        double rim = r * 0.58;
        g.fill(new Ellipse2D.Double(cx - rim, cy - rim, 2 * rim, 2 * rim));
        g.setColor(new Color(0x6B7686));
        double hub = r * 0.18;
        g.fill(new Ellipse2D.Double(cx - hub, cy - hub, 2 * hub, 2 * hub));
    }

    /**
     * The side profile of a body type, as a handful of measurements. The car faces right.
     *
     * <p>Everything a silhouette needs is where the roof starts and ends and how tall the
     * thing is: a saloon's roof stops short of the tail, an estate's runs to it, an SUV is
     * the estate drawn taller on bigger wheels.
     */
    private record Shape(int x0, int length, int roofY, int beltY, int sillY, int wheelRadius,
                         int rearAxle, int frontAxle, int roofStart, int roofEnd,
                         int tailTop, int noseTop, int pillarX) {

        static Shape of(BodyType body) {
            BodyType type = body == null ? BodyType.HATCHBACK : body;
            return switch (type) {
                // length, roofY, beltY, wheel, roofStart, roofEnd, tailTop
                case SEDAN -> build(1180, 520, 640, 96, 330, 800, 150);
                case COUPE, CONVERTIBLE -> build(1140, 548, 650, 98, 380, 760, 170);
                case ESTATE -> build(1200, 510, 636, 96, 110, 830, 40);
                case SUV, PICKUP -> build(1160, 440, 600, 116, 110, 800, 40);
                case MPV, VAN -> build(1180, 410, 590, 100, 70, 860, 20);
                default -> build(1060, 505, 636, 94, 150, 740, 60);
            };
        }

        private static Shape build(int length, int roofY, int beltY, int wheel,
                                   int roofStart, int roofEnd, int tailTop) {
            int x0 = (WIDTH - length) / 2;
            return new Shape(x0, length, roofY, beltY, GROUND - wheel + 18, wheel,
                    (int) (length * 0.19), (int) (length * 0.80), roofStart, roofEnd,
                    tailTop, length - 30, x0 + (roofStart + roofEnd) / 2 - 10);
        }

        Path2D body() {
            int floor = GROUND - wheelRadius + 34;
            Path2D path = new Path2D.Double();
            path.moveTo(x0 + 20, floor);
            // Tail, up to the belt line.
            path.quadTo(x0 - 10, beltY + 60, x0 + 30, beltY + 6);
            // Rear screen or boot, up to the roof.
            path.lineTo(x0 + tailTop, beltY - 4);
            path.quadTo(x0 + tailTop + 20, roofY + 10, x0 + roofStart, roofY);
            // Roof.
            path.lineTo(x0 + roofEnd, roofY);
            // Windscreen, bonnet, nose.
            path.quadTo(x0 + roofEnd + 120, beltY - 40, x0 + roofEnd + 210, beltY - 2);
            path.quadTo(x0 + noseTop - 40, beltY + 6, x0 + noseTop, beltY + 44);
            path.quadTo(x0 + length + 14, beltY + 110, x0 + length - 20, floor);
            path.closePath();
            return path;
        }

        Path2D glass() {
            Path2D path = new Path2D.Double();
            path.moveTo(x0 + Math.max(tailTop + 46, 90), beltY - 6);
            path.quadTo(x0 + tailTop + 50, roofY + 26, x0 + roofStart + 14, roofY + 18);
            path.lineTo(x0 + roofEnd - 8, roofY + 18);
            path.quadTo(x0 + roofEnd + 86, beltY - 44, x0 + roofEnd + 150, beltY - 6);
            path.closePath();
            return path;
        }
    }

    private static Color darker(Color colour, double factor) {
        return new Color((int) (colour.getRed() * factor), (int) (colour.getGreen() * factor),
                (int) (colour.getBlue() * factor));
    }

    private static Color rgb(ExteriorColor colour) {
        ExteriorColor value = colour == null ? ExteriorColor.GREY : colour;
        return switch (value) {
            case BLACK -> new Color(0x23272E);
            case WHITE -> new Color(0xF4F5F7);
            case GREY -> new Color(0x7B8592);
            case SILVER -> new Color(0xBCC3CC);
            case BLUE -> new Color(0x1F4E9C);
            case RED -> new Color(0xB5232B);
            case GREEN -> new Color(0x3F6B4F);
            case BROWN -> new Color(0x6B4A33);
            case BEIGE -> new Color(0xD8C9A8);
            case YELLOW -> new Color(0xE2B91C);
            case ORANGE -> new Color(0xD9691E);
            case PURPLE -> new Color(0x5B3B8C);
            case GOLD -> new Color(0xB6923A);
            // Anything the palette does not name is drawn in a neutral grey: this is a
            // placeholder picture, and a new colour in the enum should not break the seed.
            default -> new Color(0x8A93A0);
        };
    }
}
