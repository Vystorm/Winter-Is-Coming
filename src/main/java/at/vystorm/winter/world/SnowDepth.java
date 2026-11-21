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

    /**
     * Smooth world-coordinate drifts. Adjacent columns share a continuous snow field. Below one layer a stable hash per
     * column decides whether that column already has its first layer, so the first snow lies scattered on open ground
     * (1.24.1); from one layer on the depth rounds up as before, so full winter is unchanged. Below {@link #FULL_COVER}
     * frost only a growing share of the columns ({@link #coverage}) carries snow at all, chosen by the same stable hash, so
     * the cover fills in over the first third of the season and recedes the same way during the thaw.
     */
    public static int target(int maximum, double intensity, int x, int z, double driftStrength, double edge) {
        if (intensity <= 0 || edge <= 0) return 0;
        double field = 0.75 * noise(x, z, 12) + 0.25 * noise(x, z, 5);
        double depth = 1 - driftStrength * (1 - field);
        double c = coverage(intensity);
        // The cover grows over the first third of the season: a column has snow once its stable hash is below the share,
        // shaped by drifts and soft edges while the share is small and the whole column set from FULL_COVER on.
        if (c < 1 && scatter(x, z) >= c * mix(depth * edge, 1, c)) return 0;
        return Math.max(0, Math.min(maximum, layersFor(maximum * Math.min(1, intensity) * depth * edge, x, z)));
    }
    /** Frost from which every column may carry snow; below it the share of covered columns grows smoothly. */
    public static final double FULL_COVER = 0.35;
    /** Share of open columns with snow at this frost: smoothstep from 0 at no frost to 1 at {@link #FULL_COVER}. */
    public static double coverage(double intensity) {
        if (!(intensity > 0)) return 0;
        return smooth(Math.min(1, intensity / FULL_COVER));
    }
    /** Whole layers for an exact depth: scattered first layer, then rounded up. Never decreases while {@code exact} grows. */
    static int layersFor(double exact, int x, int z) {
        if (!(exact > 0)) return 0;
        if (exact < 1) return scatter(x, z) < exact ? 1 : 0;
        return (int) Math.ceil(exact);
    }
    /** Stable per-column value in [0, 1), independent of the drift field. */
    static double scatter(int x, int z) {
        return hash(x + 0x3C6EF372, z - 0x1B873593);
    }
    static double noise(int x, int z, int size) {
        int gx = Math.floorDiv(x, size), gz = Math.floorDiv(z, size);
        double tx = smooth(Math.floorMod(x, size) / (double)size);
        double tz = smooth(Math.floorMod(z, size) / (double)size);
        return mix(mix(hash(gx,gz),hash(gx+1,gz),tx),mix(hash(gx,gz+1),hash(gx+1,gz+1),tx),tz);
    }
    private static double smooth(double t) { return t*t*(3-2*t); }
    private static double mix(double a,double b,double t) { return a+(b-a)*t; }
    static double hash(int x,int z) {
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
     * Adds a ramp to the snow depth: up to 6, 4 or 2 extra layers at 1, 2 or 3 blocks from a step that is one block
     * higher, so the snow surface climbs the step. {@code distance} 0 means no step nearby; no snow stays no snow.
     * Since 1.24.1 the extra layers never exceed the base depth of the same column:
     * {@code extra = min(round((8 - 2 * distance) * intensity), base)}. A ramp therefore grows only together with the
     * snow around it (no ramp on bare ground, at most double the base depth); at full winter the base is deeper than
     * six layers and the ramp is the same as before.
     */
    public static int withRamp(int base, int distance, double intensity) {
        if (base <= 0 || distance < 1 || distance > RAMP_DISTANCE) return base;
        int extra = Math.min((int) Math.round((8 - distance * 2) * Math.max(0, Math.min(1, intensity))), base);
        return Math.min(MAX_LAYERS, base + extra);
    }

    /**
     * A small plant (grass, fern, flower) is covered as soon as its column has snow at all, with one layer, like bare
     * ground next to it. Until 1.24.0 plants were only covered from four layers on and then at once with four layers, so
     * deeper spots (ramps in front of steps) turned white long before the open meadow around them.
     */
    public static boolean coversPlant(int total) {
        return total >= 1;
    }

    /** Layers the first snow on a column has, also on a covered plant. */
    public static final int FIRST_LAYERS = 1;

    private static final int[] ORDER = order();
    private static int[] order() {
        int[] order = new int[256];
        for (int i = 0; i < 256; i++) order[i] = i;
        java.util.SplittableRandom random = new java.util.SplittableRandom(0x57494E54L);
        for (int i = 255; i > 0; i--) {
            int j = random.nextInt(i + 1);
            int t = order[i]; order[i] = order[j]; order[j] = t;
        }
        return order;
    }
    /**
     * The column (0..255, x = column & 15, z = column >> 4) a chunk's terrain pass looks at in its {@code step}-th visit.
     * Every 256 steps visit each column once, in a fixed shuffled order that starts at a different place in every chunk,
     * so a pass that the change budget cuts off leaves scattered snow instead of rows (until 1.24.0 the columns were
     * walked row by row, the same row in every chunk at once).
     */
    public static int column(int chunkX, int chunkZ, int step) {
        int start = (int) (hash(chunkX, chunkZ) * 256);
        return ORDER[Math.floorMod(step + start, 256)];
    }
}
