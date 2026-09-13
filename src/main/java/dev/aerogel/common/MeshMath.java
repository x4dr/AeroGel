package dev.aerogel.common;

import net.minecraft.world.level.ChunkPos;

/**
 * Shared mesh math (server clamp, client clamp, renderer all use the same surface definition).
 *
 * <p>Two topologies (see {@link ProxyTopology}):
 * <ul>
 *   <li>{@link ProxyTopology#NINE} — a 3x3 grid at 8-block spacing, bilinear between points.</li>
 *   <li>{@link ProxyTopology#FIVE} — 4 corners + centre, four triangles fanning from the centre.</li>
 * </ul>
 * (x,z) outside the chunk is clamped to the border — good enough for the clamp, the renderer never
 * interpolates cross-chunk.</p>
 */
public final class MeshMath {
    private MeshMath() {
    }

    public static double surfaceAt(long chunkPos, byte topology, short[] grid, double x, double z) {
        int x0 = ChunkPos.getX(chunkPos) << 4;
        int z0 = ChunkPos.getZ(chunkPos) << 4;
        double lx = clamp(x - x0, 0.0, 16.0);
        double lz = clamp(z - z0, 0.0, 16.0);
        return topology == ProxyTopology.FIVE ? fan(lx, lz, grid) : grid3(lx, lz, grid);
    }

    private static double grid3(double lx, double lz, short[] h) {
        int i = Math.min((int) (lx / 8.0), 1);
        int k = Math.min((int) (lz / 8.0), 1);
        double fx = (lx - i * 8.0) / 8.0;
        double fz = (lz - k * 8.0) / 8.0;
        double h00 = h[i * 3 + k];
        double h10 = h[(i + 1) * 3 + k];
        double h01 = h[i * 3 + k + 1];
        double h11 = h[(i + 1) * 3 + k + 1];
        double a = h00 + (h10 - h00) * fx;
        double b = h01 + (h11 - h01) * fx;
        return a + (b - a) * fz;
    }

    /** FIVE order: 0=(0,0) 1=(0,16) 2=(16,0) 3=(16,16) 4=(8,8). Pick the fan triangle, then barycentric. */
    private static double fan(double lx, double lz, short[] h) {
        double dx = lx - 8.0;
        double dz = lz - 8.0;
        if (Math.abs(dx) >= Math.abs(dz)) {
            if (dx >= 0.0) {
                return tri(lx, lz, 16, 0, h[2], 16, 16, h[3], 8, 8, h[4]); // east
            }
            return tri(lx, lz, 0, 16, h[1], 0, 0, h[0], 8, 8, h[4]); // west
        }
        if (dz >= 0.0) {
            return tri(lx, lz, 16, 16, h[3], 0, 16, h[1], 8, 8, h[4]); // north
        }
        return tri(lx, lz, 0, 0, h[0], 16, 0, h[2], 8, 8, h[4]); // south
    }

    private static double tri(double px, double pz,
            double ax, double az, double ha, double bx, double bz, double hb, double cx, double cz, double hc) {
        double denom = (bz - cz) * (ax - cx) + (cx - bx) * (az - cz);
        if (denom == 0.0) {
            return ha;
        }
        double wa = ((bz - cz) * (px - cx) + (cx - bx) * (pz - cz)) / denom;
        double wb = ((cz - az) * (px - cx) + (ax - cx) * (pz - cz)) / denom;
        double wc = 1.0 - wa - wb;
        return wa * ha + wb * hb + wc * hc;
    }

    private static double clamp(double v, double min, double max) {
        return v < min ? min : Math.min(v, max);
    }
}
