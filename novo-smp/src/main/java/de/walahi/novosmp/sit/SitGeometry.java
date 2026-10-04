package de.walahi.novosmp.sit;

import org.bukkit.util.BoundingBox;

import java.util.Collection;

/** Reads the actual collision top at the player's X/Z, not the block's material. */
final class SitGeometry {
    private static final double FEET_TOLERANCE = 0.125;
    private static final double EDGE_EPSILON = 0.001;

    private SitGeometry() {
    }

    static double surfaceAt(Collection<BoundingBox> boxes, int blockX, int blockY, int blockZ,
                            double playerX, double feetY, double playerZ) {
        double localX = playerX - blockX;
        double localZ = playerZ - blockZ;
        double best = Double.NaN;
        for (BoundingBox box : boxes) {
            if (box.getWidthX() < 0.2 || box.getWidthZ() < 0.2) continue;
            if (localX <= box.getMinX() + EDGE_EPSILON || localX >= box.getMaxX() - EDGE_EPSILON
                    || localZ <= box.getMinZ() + EDGE_EPSILON || localZ >= box.getMaxZ() - EDGE_EPSILON) continue;
            double top = blockY + box.getMaxY();
            if (Math.abs(top - feetY) > FEET_TOLERANCE) continue;
            if (Double.isNaN(best) || top > best) best = top;
        }
        return best;
    }

    static boolean collides(BoundingBox body, Collection<BoundingBox> localBoxes,
                            int blockX, int blockY, int blockZ) {
        for (BoundingBox local : localBoxes) {
            if (body.overlaps(local.clone().shift(blockX, blockY, blockZ))) return true;
        }
        return false;
    }
}
