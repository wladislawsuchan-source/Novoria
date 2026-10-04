package de.walahi.novosmp.sit;

import org.bukkit.util.BoundingBox;

import java.util.List;

public final class SitGeometryCheck {
    public static void main(String[] args) {
        check("full block", List.of(box(0, 0, 0, 1, 1, 1)), 0.5, 65, 0.5, 65);
        check("bottom slab", List.of(box(0, 0, 0, 1, 0.5, 1)), 0.5, 64.5, 0.5, 64.5);
        check("top slab", List.of(box(0, 0.5, 0, 1, 1, 1)), 0.5, 65, 0.5, 65);
        List<BoundingBox> stair = List.of(box(0, 0, 0, 1, 0.5, 1), box(0.5, 0.5, 0, 1, 1, 1));
        check("stair lower", stair, 0.25, 64.5, 0.5, 64.5);
        check("stair upper", stair, 0.75, 65, 0.5, 65);
        check("fence", List.of(box(0.25, 0, 0.25, 0.75, 1.5, 0.75)), 0.5, 65.5, 0.5, 65.5);
        check("wall", List.of(box(0.375, 0, 0.375, 0.625, 1.5, 0.625)), 0.5, 65.5, 0.5, 65.5);
        check("air", List.of(), 0.5, 65, 0.5, Double.NaN);
        check("off edge", List.of(box(0.25, 0, 0.25, 0.75, 1.5, 0.75)), 0.9, 65.5, 0.5, Double.NaN);
        BoundingBox player = box(0.2, 65, 0.2, 0.8, 66.8, 0.8);
        collision("solid wall", player, List.of(box(0, 0, 0, 1, 1, 1)), 0, 65, 0, true);
        collision("low ceiling", player, List.of(box(0, 0, 0, 1, 1, 1)), 0, 66, 0, true);
        collision("floor at feet", player, List.of(box(0, 0, 0, 1, 1, 1)), 0, 64, 0, false);
        collision("adjacent clear block", player, List.of(box(0, 0, 0, 1, 1, 1)), 1, 65, 0, false);
    }

    private static BoundingBox box(double minX, double minY, double minZ,
                                   double maxX, double maxY, double maxZ) {
        return new BoundingBox(minX, minY, minZ, maxX, maxY, maxZ);
    }

    private static void check(String label, List<BoundingBox> boxes, double x, double feetY,
                              double z, double expected) {
        double actual = SitGeometry.surfaceAt(boxes, 0, 64, 0, x, feetY, z);
        if (Double.compare(actual, expected) != 0) {
            throw new AssertionError(label + ": expected " + expected + ", got " + actual);
        }
    }

    private static void collision(String label, BoundingBox body, List<BoundingBox> boxes,
                                  int x, int y, int z, boolean expected) {
        boolean actual = SitGeometry.collides(body, boxes, x, y, z);
        if (actual != expected) throw new AssertionError(label + ": expected " + expected);
    }
}
