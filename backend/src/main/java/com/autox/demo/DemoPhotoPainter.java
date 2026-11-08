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
import java.awt.geom.Line2D;
import java.awt.geom.Path2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.util.List;

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
 * <p>Each body type has its own outline ({@link Shape}): a short citadine, a saloon with its
 * boot, an estate's long roof, a tall SUV, a one-box monospace, a low coupé, a cabriolet with
 * its top down and a van with no windows behind the cab. An earlier version drew them all from
 * one profile, and at the size of a results row they all read as the same minivan.
 *
 * <p>To look at the screen with real photographs, point {@code autox.demo.photo-dir} at a
 * folder of JPEGs; see {@link DemoData}.
 */
final class DemoPhotoPainter {

    static final int WIDTH = 1600;
    static final int HEIGHT = 1200;

    private static final int GROUND = 860;
    private static final double CAR_SCALE = 1.28;

    private DemoPhotoPainter() {
    }

    /**
     * @param view which of the three pictures: {@code 0} the car facing right, {@code 1}
     *             facing left on a warmer backdrop, {@code 2} a closer view of the front
     */
    static BufferedImage paint(BodyType body, ExteriorColor colour, String caption, int view) {
        BufferedImage image = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

            // A studio backdrop: pale wall, slightly darker floor.
            // The second view gets a warmer wall, so the three thumbnails are visibly three
            // pictures rather than the same one repeated.
            Color wallTop = view == 1 ? new Color(0xEFEBE4) : new Color(0xE9EDF2);
            Color wallFoot = view == 1 ? new Color(0xDDD6CB) : new Color(0xD3DAE3);
            g.setPaint(new GradientPaint(0, 0, wallTop, 0, GROUND, wallFoot));
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
            if (view == 2) {
                // Closer, and shifted so the front half fills the frame.
                g.scale(CAR_SCALE * 1.6, CAR_SCALE * 1.6);
                g.translate(-shape.length() * 0.24, 0);
            } else {
                // Mirrored for the second view: the same car seen from its other side.
                g.scale(view == 1 ? -CAR_SCALE : CAR_SCALE, CAR_SCALE);
            }
            g.translate(-WIDTH / 2.0, -GROUND);

            draw(g, shape, paint);

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

    private static void draw(Graphics2D g, Shape shape, Color paint) {
        int x0 = (WIDTH - shape.length()) / 2;

        // Contact shadow first, so the car sits on the floor rather than floating.
        g.setColor(new Color(20, 30, 45, 70));
        g.fill(new Ellipse2D.Double(x0 - 30, GROUND - 22, shape.length() + 60, 60));

        Path2D body = shape.body(x0);
        g.setColor(paint);
        g.fill(body);
        g.setColor(darker(paint, 0.72));
        g.setStroke(new BasicStroke(5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.draw(body);

        if (shape.open()) {
            // The top folded behind the seats, two headrests, and the windscreen's frame.
            g.setColor(new Color(0x2A2F38));
            g.fill(new RoundRectangle2D.Double(x(x0, shape.deckX() + 20), y(shape.tail() + 34),
                    150, 46, 30, 30));
            g.setColor(new Color(0x3A404B));
            for (int seat : new int[] {shape.deckX() + 230, shape.deckX() + 400}) {
                g.fill(new RoundRectangle2D.Double(x(x0, seat), y(shape.cowlH() + 92), 46, 70, 20, 20));
            }
            g.setColor(darker(paint, 0.6));
            g.setStroke(new BasicStroke(12f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.draw(new Line2D.Double(x(x0, shape.cowlX() - 10), y(shape.cowlH() + 4),
                    x(x0, shape.cowlX() - 130), y(shape.roofH())));
        } else {
            g.setColor(new Color(0x1F2A3A));
            g.fill(shape.glass(x0));
            // The pillars between the side windows, in the body's colour.
            g.setColor(paint);
            for (int pillar : shape.pillars()) {
                g.fill(new RoundRectangle2D.Double(x(x0, pillar) - 13, y(shape.roofH()) + 10, 26,
                        shape.roofH() - shape.belt() - 4, 8, 8));
            }
        }

        // A sill line and the lights: just enough detail to read as a car.
        g.setColor(darker(paint, 0.8));
        g.setStroke(new BasicStroke(6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        int sill = shape.clearance() + 34;
        g.draw(new Line2D.Double(x(x0, shape.rearWheel() + shape.wheel() + 20), y(sill),
                x(x0, shape.frontWheel() - shape.wheel() - 20), y(sill)));
        g.setColor(new Color(0xF6E7A8));
        g.fill(new RoundRectangle2D.Double(x(x0, shape.length() - 66), y(shape.noseH() - 18), 56, 28, 14, 14));
        g.setColor(new Color(0xB3262D));
        g.fill(new RoundRectangle2D.Double(x(x0, 6), y(shape.tail() - 22), 30, 46, 12, 12));

        for (int centre : new int[] {shape.rearWheel(), shape.frontWheel()}) {
            wheel(g, x(x0, centre), GROUND - shape.wheel() + 6, shape.wheel());
        }
    }

    /** From the car's own coordinates — from its tail, in pixels — to the picture's. */
    private static double x(int x0, int along) {
        return x0 + along;
    }

    /** From a height above the floor to the picture's. */
    private static double y(int height) {
        return GROUND - height;
    }

    private static void wheel(Graphics2D g, double cx, double cy, int r) {
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
     * The side profile of a body type, the car facing right. Lengths along the car from its
     * tail, heights above the floor, in pixels before scaling.
     *
     * @param length     tail to nose
     * @param wheel      wheel radius: an SUV's are bigger
     * @param clearance  the underside's height
     * @param tail       the top of the tail: the roof's height on a two-box car, the boot lid's
     *                   on a saloon, the load bed's on a pick-up
     * @param deckX      where the boot lid or the bed meets the cabin; 0 on a two-box car
     * @param roofRearX  where the roof starts
     * @param roofFrontX where the roof ends, at the top of the windscreen
     * @param roofH      the roof's height; on a cabriolet, the windscreen's top
     * @param cowlX      the windscreen's foot
     * @param cowlH      its height, which is the belt line
     * @param noseH      the top of the nose
     * @param pillars    where the side windows are divided
     * @param cabOnly    no side windows behind the front doors: a van or a pick-up
     * @param open       no roof: a cabriolet with its top down
     */
    record Shape(int length, int wheel, int clearance, int tail, int deckX, int roofRearX,
                 int roofFrontX, int roofH, int cowlX, int cowlH, int noseH, List<Integer> pillars,
                 boolean cabOnly, boolean open) {

        static Shape of(BodyType body) {
            BodyType type = body == null ? BodyType.HATCHBACK : body;
            return switch (type) {
                // Citadine: short, a steep hatch, the roof over the middle. And for a body the
                // seller called "other", the most ordinary car there is.
                case HATCHBACK, OTHER -> new Shape(980, 92, 40, 296, 0, 175, 560, 380, 735, 250, 214,
                        List.of(405), false, false);
                // Berline: three boxes, the boot a step down behind the rear window.
                case SEDAN -> new Shape(1200, 96, 40, 262, 220, 410, 750, 372, 920, 252, 210,
                        List.of(590), false, false);
                // Break: long and low, the roof running to the tailgate.
                case ESTATE -> new Shape(1210, 96, 40, 350, 0, 60, 760, 376, 930, 252, 212,
                        List.of(320, 590), false, false);
                // SUV: tall, high off the floor, on big wheels.
                case SUV -> new Shape(1160, 118, 72, 418, 0, 64, 720, 456, 892, 322, 292,
                        List.of(300, 560), false, false);
                // Monospace: one box, a short bonnet and a long, raked windscreen.
                case MPV -> new Shape(1170, 100, 46, 440, 0, 54, 760, 474, 1000, 282, 236,
                        List.of(290, 560), false, false);
                // Coupé: low, two doors, the roof sloping down to a short tail.
                case COUPE -> new Shape(1150, 98, 36, 252, 120, 420, 640, 340, 850, 236, 196,
                        List.of(), false, false);
                // Cabriolet: no roof; the windscreen's top stands for it.
                case CONVERTIBLE -> new Shape(1130, 96, 38, 252, 220, 0, 0, 350, 760, 246, 202,
                        List.of(), false, true);
                // Pick-up: a cab, then an open bed at the height of the doors.
                case PICKUP -> new Shape(1220, 116, 72, 312, 470, 490, 830, 452, 1000, 314, 292,
                        List.of(), true, false);
                // Utilitaire: a tall box with a short nose, glazed only at the cab.
                case VAN -> new Shape(1210, 98, 52, 528, 0, 26, 960, 548, 1066, 300, 262,
                        List.of(), true, false);
            };
        }

        int rearWheel() {
            return (int) (length * (this.cabOnly && !isPickup() ? 0.17 : 0.19));
        }

        int frontWheel() {
            return (int) (length * (cowlX > length * 0.85 ? 0.82 : 0.80));
        }

        private boolean isPickup() {
            return deckX > 0 && cabOnly;
        }

        /** The belt line: the bottom of the side windows. */
        int belt() {
            return Math.max(cowlH, deckX > 0 && !cabOnly ? tail : 0) + 6;
        }

        Path2D body(int x0) {
            List<double[]> points = new java.util.ArrayList<>();
            points.add(new double[] {14, clearance, 20});
            points.add(new double[] {0, clearance + 46, 30});
            points.add(new double[] {4, tail, 34});
            if (open) {
                points.add(new double[] {deckX, tail + 4, 40});
                points.add(new double[] {cowlX, cowlH, 30});
            } else if (deckX > 0) {
                points.add(new double[] {deckX, tail, 24});
                if (cabOnly) {
                    // The cab's back, straight up from the bed.
                    points.add(new double[] {roofRearX, tail, 10});
                }
                points.add(new double[] {roofRearX, roofH, 60});
                points.add(new double[] {roofFrontX, roofH, 70});
                points.add(new double[] {cowlX, cowlH, 40});
            } else {
                points.add(new double[] {roofRearX, roofH, 70});
                points.add(new double[] {roofFrontX, roofH, 80});
                points.add(new double[] {cowlX, cowlH, 40});
            }
            points.add(new double[] {length - 18, noseH, 40});
            points.add(new double[] {length, noseH - 50, 30});
            points.add(new double[] {length - 4, clearance + 40, 26});
            points.add(new double[] {length - 20, clearance, 20});
            return rounded(points, x0);
        }

        Path2D glass(int x0) {
            int inset = 18;
            int belt = belt();
            double top = roofH - inset;
            List<double[]> points = new java.util.ArrayList<>();
            if (cabOnly) {
                double from = isPickup() ? roofRearX + 26 : cowlX - 250;
                points.add(new double[] {from, belt, 8});
                points.add(new double[] {from, top, 14});
                points.add(new double[] {roofFrontX - inset, top, 20});
                points.add(new double[] {cowlX - inset * 1.6, belt, 8});
                return rounded(points, x0);
            }
            double rear = deckX > 0 ? deckX + 70 : 40;
            double rearTop = deckX > 0 ? roofRearX + 14 : roofRearX + 12;
            points.add(new double[] {rear, belt, 10});
            points.add(new double[] {rearTop, top, 30});
            points.add(new double[] {roofFrontX - inset, top, 30});
            points.add(new double[] {cowlX - inset * 1.8, belt, 8});
            return rounded(points, x0);
        }

        /**
         * A closed outline through the points, each corner rounded by its own radius:
         * {@code {along, height, radius}}.
         */
        private static Path2D rounded(List<double[]> points, int x0) {
            Path2D path = new Path2D.Double();
            int n = points.size();
            for (int i = 0; i < n; i++) {
                double[] previous = points.get((i + n - 1) % n);
                double[] corner = points.get(i);
                double[] next = points.get((i + 1) % n);
                double[] in = toward(corner, previous, corner[2]);
                double[] out = toward(corner, next, corner[2]);
                if (i == 0) {
                    path.moveTo(x(x0, in[0]), y(in[1]));
                } else {
                    path.lineTo(x(x0, in[0]), y(in[1]));
                }
                path.quadTo(x(x0, corner[0]), y(corner[1]), x(x0, out[0]), y(out[1]));
            }
            path.closePath();
            return path;
        }

        /** A point from {@code from} toward {@code to}, at most halfway. */
        private static double[] toward(double[] from, double[] to, double distance) {
            double dx = to[0] - from[0];
            double dy = to[1] - from[1];
            double length = Math.hypot(dx, dy);
            if (length == 0) {
                return new double[] {from[0], from[1]};
            }
            double step = Math.min(distance, length / 2) / length;
            return new double[] {from[0] + dx * step, from[1] + dy * step};
        }

        private static double x(int x0, double along) {
            return x0 + along;
        }

        private static double y(double height) {
            return GROUND - height;
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
