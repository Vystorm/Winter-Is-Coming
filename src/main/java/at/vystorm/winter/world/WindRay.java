package at.vystorm.winter.world;

/**
 * Geometry of wind-blown snow (1.25.0), without Bukkit types: does snow that drifts in at an angle reach a sheltered
 * block top? The rule is the one of the design study (variant A, recommended cone): a cone of {@link #CONE_RAYS} rays
 * is traced from the block top upwards and towards where the wind comes from, and the share of rays that reach open sky
 * decides how much snow lies there.
 *
 * <p>Hard requirement: snow never gets through a wall. A ray is therefore checked one block layer at a time, and in each
 * layer every block whose closed box touches the ray segment, widened by the flake half width, must be free; shared
 * edges and corners count as touching. Point sampling along the ray is not allowed: it slips through diagonal block
 * edges (the tests keep a point-sampling ray as proof of that).
 */
public final class WindRay {
    /** Half width of a flake in blocks; the footprint of a ray is widened by it. */
    public static final double FLAKE_HALF_WIDTH = 0.05;
    /** Rays per block top. */
    public static final int CONE_RAYS = 8;
    /** The cone's angles lie within the angle plus or minus this many degrees. */
    public static final double CONE_ANGLE_SPREAD = 5;
    /** The cone's directions lie within the prevailing wind plus or minus this many degrees. */
    public static final double CONE_DIRECTION_SPREAD = 45;
    /** Landing points spread over the block top up to this far from its middle (blocks). */
    public static final double LANDING_SPREAD = 0.45;
    /** Below this share of free rays no snow settles. */
    public static final double MIN_SHARE = 0.25;
    private static final int SEED_SALT = 0x51;

    private WindRay() { }

    /** What the rays see: blocks, the column heights and which chunks are loaded. */
    public interface Voxels {
        /** False when the column's chunk is not loaded: a ray that would enter it fails (no chunk is ever loaded). */
        boolean loaded(int x, int z);
        /** True when the block stops snow: everything except air, small plants and the plugin's own snow. */
        boolean blocks(int x, int y, int z);
        /** The column's highest blocking block (motion-blocking heightmap below the plugin's own snow). */
        int height(int x, int z);
    }

    /**
     * The wind: {@code angle} from the vertical in degrees, {@code from} = compass direction the wind comes from
     * (0 north, 90 east, 180 south, 270 west), {@code maxHeight} = block layers a ray is traced at most.
     */
    public record Wind(double angle, double from, int maxHeight) { }

    /**
     * One ray from the point {@code (sx, y0, sz)} on a block top, upwards at {@code angle} from the vertical towards
     * the compass direction {@code from}. True when, in one layer, its whole footprint is above the heights of the
     * columns it is in before it touches a blocking block, within {@code maxRise} layers and loaded chunks.
     */
    public static boolean ray(Voxels v, double sx, double sz, int y0, double angle, double from, int maxRise, double w) {
        double tn = StrictMath.tan(StrictMath.toRadians(angle));
        double dx = StrictMath.sin(StrictMath.toRadians(from)) * tn, dz = -StrictMath.cos(StrictMath.toRadians(from)) * tn;
        for (int k = y0; k < y0 + maxRise; k++) {
            int a = k - y0;
            double x0 = sx + dx * a, z0 = sz + dz * a, x1 = x0 + dx, z1 = z0 + dz;
            // Every block whose closed box meets the footprint of this layer: a block touched only at an edge counts.
            int xl = (int)Math.ceil(Math.min(x0, x1) - w) - 1, xh = (int)Math.floor(Math.max(x0, x1) + w);
            int zl = (int)Math.ceil(Math.min(z0, z1) - w) - 1, zh = (int)Math.floor(Math.max(z0, z1) + w);
            boolean open = true;
            for (int zi = zl; zi <= zh; zi++) for (int xi = xl; xi <= xh; xi++) {
                if (!v.loaded(xi, zi) || v.blocks(xi, k, zi)) return false;
                if (v.height(xi, zi) > k) open = false;
            }
            if (open) return true;
        }
        return false;
    }

    /** Share (0..1) of the cone's rays that reach open sky from the block top of the cell {@code (x, y0, z)}. */
    public static double exposure(Voxels v, int x, int y0, int z, Wind wind) {
        return free(v, x, y0, z, wind) / (double)CONE_RAYS;
    }

    /** Number of the cone's rays (0..{@link #CONE_RAYS}) that reach open sky. */
    public static int free(Voxels v, int x, int y0, int z, Wind wind) {
        int n = CONE_RAYS, g = (int)Math.round(Math.sqrt(n)), free = 0;
        double cx = x + 0.5, cz = z + 0.5;
        int[] state = {seed(x, y0, z, SEED_SALT)};
        for (int i = 0; i < n; i++) {
            // Landing points spread in a 3x3 grid over the block top (stable per block), angles and directions spread
            // evenly over the cone, the same set for every block (less speckle).
            double gx = (i % g + next(state)) / g - 0.5, gz = (Math.floorDiv(i, g) % g + next(state)) / g - 0.5;
            double angle = wind.angle() + CONE_ANGLE_SPREAD * ((((i * 3) % n) + 0.5) / n * 2 - 1);
            double from = wind.from() + CONE_DIRECTION_SPREAD * ((i + 0.5) / n * 2 - 1);
            if (ray(v, cx + gx * 2 * LANDING_SPREAD, cz + gz * 2 * LANDING_SPREAD, y0, angle, from, wind.maxHeight(), FLAKE_HALF_WIDTH)) free++;
        }
        return free;
    }

    /**
     * Cells from the windward edge of a roof with {@code clearance} free blocks beneath that a single ray at
     * {@code angle} reaches (the middle of the cell must be reached): floor(h * tan(angle) + 0.5 - w).
     */
    public static int singleRayReach(int clearance, double angle) {
        return (int)Math.floor(clearance * StrictMath.tan(StrictMath.toRadians(angle)) + 0.5 - FLAKE_HALF_WIDTH);
    }

    /** Blocks within which a ray of this wind can leave its column: ceil(maxHeight * tan(angle + 5)) + 1. */
    public static int horizontalReach(Wind wind) {
        double steepest = Math.min(89, wind.angle() + CONE_ANGLE_SPREAD);
        return (int)Math.ceil(wind.maxHeight() * StrictMath.tan(StrictMath.toRadians(steepest))) + 1;
    }

    /** Stable seed of a cell (the study's cellSeed). */
    static int seed(int x, int y, int z, int salt) {
        return x * 73856093 ^ y * 19349663 ^ z * 83492791 ^ salt;
    }

    /** xorshift32 step (the study's rng): returns [0, 1). */
    static double next(int[] state) {
        int s = state[0] == 0 ? 1 : state[0];
        s ^= s << 13;
        s ^= s >>> 17;
        s ^= s << 5;
        state[0] = s;
        return (s & 0xFFFFFFFFL) / 4294967296.0;
    }
}
