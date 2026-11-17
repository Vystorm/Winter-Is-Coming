package at.vystorm.winter.world;

import java.util.Objects;

/** Total snow depth in eighths of a block; each owned block stores at most eight layers. */
public final class SnowDepth {
    public static final int MAX_LAYERS = 64;
    public static final int MAX_BLOCKS = MAX_LAYERS / 8;
    private SnowDepth() { }

    public static int target(int maximum, double intensity, int x, int z) {
        double noise = Math.floorMod(Objects.hash(x, z), 1000) / 1000.0 * 0.3;
        return Math.max(0, Math.min(maximum, (int) Math.ceil((intensity - noise) * maximum)));
    }

    /** Smooth world-coordinate drifts. Adjacent columns share a continuous snow field. */
    public static int target(int maximum, double intensity, int x, int z, double driftStrength, double edge) {
        if (intensity <= 0 || edge <= 0) return 0;
        double field = 0.75 * noise(x, z, 12) + 0.25 * noise(x, z, 5);
        double depth = 1 - driftStrength * (1 - field);
        return Math.max(0, Math.min(maximum, (int)Math.ceil(maximum * Math.min(1, intensity) * depth * edge)));
    }
    private static double noise(int x, int z, int size) {
        int gx = Math.floorDiv(x, size), gz = Math.floorDiv(z, size);
        double tx = smooth(Math.floorMod(x, size) / (double)size);
        double tz = smooth(Math.floorMod(z, size) / (double)size);
        return mix(mix(hash(gx,gz),hash(gx+1,gz),tx),mix(hash(gx,gz+1),hash(gx+1,gz+1),tx),tz);
    }
    private static double smooth(double t) { return t*t*(3-2*t); }
    private static double mix(double a,double b,double t) { return a+(b-a)*t; }
    private static double hash(int x,int z) {
        long h=(long)x*0x9E3779B97F4A7C15L ^ (long)z*0xC2B2AE3D27D4EB4FL;
        h=(h^(h>>>30))*0xBF58476D1CE4E5B9L;
        h=(h^(h>>>27))*0x94D049BB133111EBL;
        return ((h^(h>>>31))>>>11)*0x1.0p-53;
    }

    public static int atHeight(int total, int blockOffset) {
        if (blockOffset < 0 || blockOffset >= MAX_BLOCKS) return 0;
        return Math.max(0, Math.min(8, total - blockOffset * 8));
    }

    /** Blocks over which snow ramps up towards a one-block step. */
    public static final int RAMP_DISTANCE = 3;
    /**
     * Adds a ramp to the snow depth: 6, 4 or 2 extra layers at 1, 2 or 3 blocks from a step that is one block higher, so
     * the snow surface climbs the step. {@code distance} 0 means no step nearby; no snow stays no snow.
     */
    public static int withRamp(int base, int distance, double intensity) {
        if (base <= 0 || distance < 1 || distance > RAMP_DISTANCE) return base;
        return Math.min(MAX_LAYERS, base + (int) Math.round((8 - distance * 2) * intensity));
    }
}
