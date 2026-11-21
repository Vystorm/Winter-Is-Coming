package at.vystorm.winter.world;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Where wind-blown snow may settle (1.25.0), without Bukkit types: which blocks stop a ray, which cells under a roof or
 * canopy are sheltered surfaces, a cheap filter for the columns worth tracing, and how many layers a share of free
 * rays gives. Open columns (the block top above the heightmap) are never touched here.
 */
public final class Shelter {
    private Shelter() { }

    /** Single-block plants snow may cover; the original comes back on thaw. By name, so newer plants also work on older servers. */
    static final Set<String> SMALL_PLANTS = Set.of("SHORT_GRASS", "FERN", "DANDELION", "POPPY", "BLUE_ORCHID", "ALLIUM", "AZURE_BLUET", "RED_TULIP", "ORANGE_TULIP", "WHITE_TULIP", "PINK_TULIP", "OXEYE_DAISY", "CORNFLOWER", "LILY_OF_THE_VALLEY", "TORCHFLOWER", "DEAD_BUSH", "PINK_PETALS", "WILDFLOWERS", "LEAF_LITTER", "SHORT_DRY_GRASS", "BUSH");
    private static final Set<String> AIR = Set.of("AIR", "CAVE_AIR", "VOID_AIR");

    /**
     * Whether a block of this material stops wind-blown snow: everything except air, small plants and the plugin's own
     * snow. Glass, panes, bars, fences, walls, slabs and stairs count as full blocks; doors and trapdoors block open or
     * closed; leaves block unless {@code leavesBlock} is false.
     */
    public static boolean blocksRay(String material, boolean ownSnow, boolean leavesBlock) {
        if (AIR.contains(material) || SMALL_PLANTS.contains(material)) return false;
        if (ownSnow && material.equals("SNOW")) return false;
        if (material.endsWith("_LEAVES")) return leavesBlock;
        return true;
    }

    /** What the finder reads: the rays' view plus the raw heightmap and the cells that can take sheltered snow. */
    public interface World extends WindRay.Voxels {
        /** Motion-blocking heightmap as the server keeps it (the plugin's own snow included). */
        int rawHeight(int x, int z);
        /**
         * The cell takes sheltered snow: air, a small plant (when plants may be covered) or the plugin's snow, standing
         * on a block that takes snow by the plugin's usual rules (never on snow: sheltered snow is one block at most).
         */
        boolean surface(int x, int y, int z);
        /** Nothing is in the cell that snow could touch: air or a small plant. */
        boolean room(int x, int y, int z);
        /** Lowest block of the world. */
        int minY();
        /** Layers of the plugin's own snow in the cell (1..8), 0 when the cell holds no snow of the plugin. */
        int snowLayers(int x, int y, int z);
    }

    /** Offsets of the five directions the filter looks along, relative to where the wind comes from. */
    private static final double[] FILTER_DIRECTIONS = {-45, -22.5, 0, 22.5, 45};

    /**
     * The filter's column offsets for one wind, nearest first, each offset once: the five directions round to the same
     * column often (at distance 1 three of them name the column straight upwind). Worked out once per wind, so a
     * column costs no trigonometry and no repeated heightmap read.
     */
    static int[] filterOffsets(WindRay.Wind wind) {
        FilterKey key = new FilterKey(wind.from(), WindRay.horizontalReach(wind));
        Filter cached = filterCache;
        if (cached != null && cached.key().equals(key)) return cached.offsets();
        java.util.LinkedHashSet<Long> seen = new java.util.LinkedHashSet<>();
        for (int d = 1; d <= key.reach(); d++) for (double offset : FILTER_DIRECTIONS) {
            double az = StrictMath.toRadians(wind.from() + offset);
            int dx = (int)Math.round(StrictMath.sin(az) * d), dz = (int)Math.round(-StrictMath.cos(az) * d);
            if (dx != 0 || dz != 0) seen.add((long)dx << 32 | (dz & 0xFFFFFFFFL));
        }
        int[] out = new int[seen.size() * 2];
        int i = 0;
        for (long v : seen) { out[i++] = (int)(v >> 32); out[i++] = (int)v; }
        filterCache = new Filter(key, out);
        return out;
    }
    private record FilterKey(double from, int reach) { }
    private record Filter(FilterKey key, int[] offsets) { }
    private static volatile Filter filterCache;

    /**
     * Cheap pre-check before any ray: a column is worth tracing when within {@link WindRay#horizontalReach} blocks
     * upwind (the prevailing wind plus or minus 45°) a loaded column lies at least two blocks lower. It may only
     * withhold columns, never add snow.
     */
    public static boolean candidate(World w, int x, int z, WindRay.Wind wind) {
        int here = w.rawHeight(x, z);
        int[] offsets = filterOffsets(wind);
        for (int i = 0; i < offsets.length; i += 2) {
            int cx = x + offsets[i], cz = z + offsets[i + 1];
            if (!w.loaded(cx, cz)) continue;
            int up = w.rawHeight(cx, cz);
            if (up <= here - 2) return true;
            // one block lower in the heightmap: lower still once the plugin's own snow on it is left out
            if (up == here - 1 && w.height(cx, cz) <= here - 2) return true;
        }
        return false;
    }

    /** Face step one block upwind along the wind's dominant axis: {dx, dz}. */
    static int[] upwindStep(WindRay.Wind wind) {
        double az = StrictMath.toRadians(wind.from()), sx = StrictMath.sin(az), sz = -StrictMath.cos(az);
        return Math.abs(sx) >= Math.abs(sz) ? new int[]{sx > 0 ? 1 : -1, 0} : new int[]{0, sz > 0 ? 1 : -1};
    }

    /**
     * How many cells the surface {@code (x, y, z)} lies under its roof, measured upwind along the prevailing wind: 0 for
     * the first sheltered cell behind the roof's edge. Steps upwind like the filter (straight into the wind) and returns
     * d - 1 at the first column whose height is below the surface; the filter's reach when there is none (or the
     * column's chunk is not loaded) within it.
     */
    public static int under(World w, int x, int y, int z, WindRay.Wind wind) {
        int reach = WindRay.horizontalReach(wind);
        double az = StrictMath.toRadians(wind.from());
        double sx = StrictMath.sin(az), sz = -StrictMath.cos(az);
        for (int d = 1; d <= reach; d++) {
            int cx = x + (int)Math.round(sx * d), cz = z + (int)Math.round(sz * d);
            if (!w.loaded(cx, cz)) return reach;
            if (w.height(cx, cz) < y) return d - 1;
        }
        return reach;
    }

    /**
     * Layers of the plugin's snow in the face neighbour one step upwind ({@link #upwindStep}), counted only when that
     * cell is itself a sheltered surface at the same level: it takes sheltered snow (so never a door, pane, fence or wall
     * cell), lies below its column's highest blocking block (never open ground) and is a face neighbour (never across a
     * corner). Two face neighbours at the same level have nothing between them, so this snow ("creep") only ever comes
     * from a cell that is itself connected to the open air.
     */
    public static int upwindLayers(World w, int x, int y, int z, WindRay.Wind wind) {
        int[] step = upwindStep(wind);
        int nx = x + step[0], nz = z + step[1];
        if (!w.loaded(nx, nz)) return 0;
        int layers = w.snowLayers(nx, y, nz);
        if (layers <= 0 || !w.surface(nx, y, nz) || y >= w.height(nx, nz)) return 0;
        return layers;
    }

    /**
     * The sheltered surfaces of a column, from the top down: cells below the column's highest blocking block that take
     * snow, at most {@code maxHeight + 1} blocks below it.
     */
    public static List<Integer> surfaces(World w, int x, int z, int maxHeight) {
        int top = w.height(x, z);
        List<Integer> out = new ArrayList<>(2);
        for (int y = top - 1; y >= top - maxHeight - 1 && y > w.minY(); y--) if (w.surface(x, y, z)) out.add(y);
        return out;
    }

    /** Most layers under an opening only two blocks high: a standing player (1.8 high) still walks in, see {@link #layers}. */
    public static final int LOW_CEILING_LAYERS = 2;

    /**
     * The drift line of one row under a roof (look of 2026-11-21): where the wind-blown snow of the row ends and how its
     * surface falls on the way, so the rows of one roof differ and the steps wander along the edge instead of forming a
     * staircase. Only stable hashes of the row's first sheltered cell (no random numbers, see {@link DriftLines}): the
     * same position gives the same line at every pass and after every restart.
     * <ul>
     * <li>{@code reach}: the single ray's reach for the clearance above the cell ({@link WindRay#singleRayReach});</li>
     * <li>{@code end}: the drift ends {@code reach + 0.5 .. reach + 2} cells under the roof (never at reach + 2);</li>
     * <li>{@code power}: 0.6 .. 1.8, the shape (falls early, or stays full first);</li>
     * <li>{@code phase}: 0 .. 1, where the one-layer steps land.</li>
     * </ul>
     * Each comes from smooth noises along the edge (lobes of two to three blocks, two octaves) plus grain per row.
     */
    public static final class Line {
        private final int reach;
        private final double end, power, phase;
        /** Cells per full depth 0 .. 8, worked out on first use (a pure function of the line). */
        private final java.util.concurrent.atomic.AtomicReferenceArray<int[]> memo = new java.util.concurrent.atomic.AtomicReferenceArray<>(9);

        public Line(int reach, double end, double power, double phase) {
            this.reach = reach;
            this.end = end;
            this.power = power;
            this.phase = phase;
        }
        public int reach() { return reach; }
        public double end() { return end; }
        public double power() { return power; }
        public double phase() { return phase; }
        @Override public boolean equals(Object o) {
            return o instanceof Line l && l.reach == reach && Double.compare(l.end, end) == 0 && Double.compare(l.power, power) == 0 && Double.compare(l.phase, phase) == 0;
        }
        @Override public int hashCode() { return java.util.Objects.hash(reach, end, power, phase); }
        @Override public String toString() { return "Line[reach=" + reach + ", end=" + end + ", power=" + power + ", phase=" + phase + "]"; }

        /** {@link #compute} for this full, kept per line; callers must not change the array. */
        int[] cells(int full) {
            if (full < 0 || full > 8) return compute(full);
            int[] r = memo.get(full);
            if (r == null) { r = compute(full); memo.set(full, r); }
            return r;
        }
        /**
         * The line's layers at cells 0 .. reach + 2 for a drift {@code full} layers deep: full at the first cell, then
         * {@code full - floor(full x min(1, d / end)^power + phase)}, smoothed so that neighbouring cells differ by at
         * most two layers, and 0 from reach + 2 on. Never smaller for a larger full.
         */
        private int[] compute(int full) {
            int n = reach + 2;
            int[] r = new int[n + 1];
            r[0] = full;
            for (int i = 1; i < n; i++) r[i] = Math.max(0, full - (int)Math.floor(full * StrictMath.pow(Math.min(1, i / end), power) + phase));
            r[n] = 0;
            for (int i = 1; i < n; i++) r[i] = Math.max(r[i], r[i - 1] - 2);
            for (int i = n - 1; i >= 1; i--) r[i] = Math.min(r[i], r[i + 1] + 2);
            return r;
        }
    }

    /**
     * The drift line of a row and of its neighbouring rows along the edge ({@link #lines}, the row itself in the middle):
     * the row's profile is lifted to at least each neighbour's line minus two layers per row of distance, so a lobe
     * boundary never makes a step of three layers sideways (on a straight edge: the lift is the largest of lines that
     * each change by at most two layers per row).
     */
    public record Drift(List<Line> lines) {
        /** A single line without neighbours (tests and fixed profiles). */
        public Drift(int reach, double end, double power, double phase) {
            this(List.of(new Line(reach, end, power, phase)));
        }
        public Drift {
            lines = List.copyOf(lines);
            if (lines.isEmpty() || lines.size() % 2 == 0) throw new IllegalArgumentException("odd number of lines: " + lines.size());
        }
        /** West wind, max-layers 4 (tests). */
        public static Drift of(int anchorX, int anchorZ, int clearance, double angle) {
            return of(anchorX, anchorZ, clearance, new WindRay.Wind(angle, 270, 12), 4);
        }
        /**
         * The drift of the row anchored at {@code (anchorX, anchorZ)} under a roof of this clearance; {@code maxLayers}
         * only sets the depth at which neighbouring rows are compared (the choice of line does not depend on the frost,
         * so the profile stays monotone in it).
         */
        public static Drift of(int anchorX, int anchorZ, int clearance, WindRay.Wind wind, int maxLayers) {
            return DriftLines.of(anchorX, anchorZ, clearance, wind, maxLayers);
        }
        /** The single ray's reach of the row's clearance. */
        public int reach() {
            return lines.get(lines.size() / 2).reach();
        }
        /**
         * Layers of the row's surface {@code distance} cells under the roof for a drift {@code full} layers deep: the
         * largest of the lines' cells minus two layers per row away from the middle. Full at the first cell, falling by
         * at most two layers per cell, 0 from reach + 2 on, never smaller for a larger full.
         */
        public int profile(int full, double distance) {
            int d = (int)Math.floor(distance + 1e-9);
            if (d <= 0 || full <= 0) return Math.max(0, full);
            int middle = lines.size() / 2, best = 0;
            for (int i = 0; i < lines.size(); i++) {
                int[] r = lines.get(i).cells(full);
                if (d < r.length) best = Math.max(best, r[d] - 2 * Math.abs(i - middle));
            }
            return best;
        }
    }

    /**
     * Distance of the cell from the open air in cells, for the drift line: the cells under the roof straight upwind
     * ({@code under}) or, when nearer, along the cone's outer directions (+-45°, diagonal steps, each 1.41 cells), so the
     * rims of a roof that are open to the side get their own drift and the corners round off.
     */
    public static double distance(World w, int x, int y, int z, WindRay.Wind wind, int under) {
        return Math.min(under, 1.41 * Math.min(underAlong(w, x, y, z, wind, -WindRay.CONE_DIRECTION_SPREAD), underAlong(w, x, y, z, wind, WindRay.CONE_DIRECTION_SPREAD)));
    }
    /** Cells under the roof along the wind turned by {@code offset} degrees, in whole diagonal (or axis) steps, as {@link #under}. */
    static int underAlong(World w, int x, int y, int z, WindRay.Wind wind, double offset) {
        int reach = WindRay.horizontalReach(wind);
        double az = StrictMath.toRadians(wind.from() + offset);
        int sx = step(StrictMath.sin(az)), sz = step(-StrictMath.cos(az));
        if (sx == 0 && sz == 0) return reach;
        for (int d = 1; d <= reach; d++) {
            int cx = x + sx * d, cz = z + sz * d;
            if (!w.loaded(cx, cz)) return reach;
            if (w.height(cx, cz) < y) return d - 1;
        }
        return reach;
    }
    private static int step(double v) {
        return Math.abs(v) < 1e-9 ? 0 : v > 0 ? 1 : -1;
    }
    /** The row's first sheltered cell: {@code under} cells upwind of the cell, along the same steps as {@link #under}. */
    static int[] anchor(int x, int z, WindRay.Wind wind, int under) {
        double az = StrictMath.toRadians(wind.from());
        return new int[]{x + (int)Math.round(StrictMath.sin(az) * under), z + (int)Math.round(-StrictMath.cos(az) * under)};
    }

    /**
     * Layers of sheltered snow (amount 2026-11-21, look of 2026-11-21 "drift line"):
     * <ul>
     * <li>{@code full}: the column's normal depth at this frost, at most {@code maxLayers}, at most 7 when a block lies
     * right above (the snow never touches it) and at most {@link #LOW_CEILING_LAYERS} under an opening only two blocks
     * high. Snow of n layers has a collision height of (n - 1) / 8 block; a standing player is 1.8 high, so under two
     * free blocks n - 1 &lt;= 1.6, i.e. 2 layers (1.925) keep the doorway walkable and 3 layers (2.05) close it;</li>
     * <li>rays reach the cell (share at least {@link WindRay#MIN_SHARE}): the row's drift line at the cell's distance,
     * at most the share of free rays times {@code full}, at least 1;</li>
     * <li>creep (whether rays reach the cell or not): when the sheltered face neighbour upwind holds snow
     * ({@link #upwindLayers}), at least the drift line up to one layer less than that neighbour, so a weakly lit cell
     * never forms a ledge. Always less than the neighbour: the chain is acyclic and ends; without rays and without such
     * a neighbour there is no snow.</li>
     * </ul>
     */
    public static int layers(double share, int normalDepth, int maxLayers, boolean roomAbove, boolean lowCeiling, Drift drift, double distance, int upwindLayers) {
        if (normalDepth <= 0) return 0;
        int full = Math.min(Math.min(normalDepth, maxLayers), Math.min(roomAbove ? 8 : 7, lowCeiling ? LOW_CEILING_LAYERS : 8));
        int line = Math.min(full, drift.profile(full, distance));
        int creep = upwindLayers > 0 ? Math.max(0, Math.min(line, upwindLayers - 1)) : 0;
        if (share >= WindRay.MIN_SHARE) return Math.max(creep, Math.max(1, Math.min(line, (int)Math.round(share * full))));
        return creep;
    }

    /**
     * Whether a block placed at {@code (px, py, pz)} can change what the wind brings to the block top {@code (sx, sy, sz)}:
     * the block lies at or above it, upwind (the cone's directions plus slack for landing points and footprints) and
     * within the horizontal distance a ray covers up to that height. Used to re-check sheltered snow early after a
     * player builds next to it; it may name more positions than needed, never fewer.
     */
    public static boolean mayShade(int px, int py, int pz, int sx, int sy, int sz, WindRay.Wind wind) {
        return Shade.of(wind).test(px, py, pz, sx, sy, sz);
    }
    /** {@link #mayShade} with the wind's angles worked out once, for many positions. */
    public record Shade(double tan, double upX, double upZ, int maxHeight) {
        public static Shade of(WindRay.Wind wind) {
            double az = StrictMath.toRadians(wind.from());
            return new Shade(StrictMath.tan(StrictMath.toRadians(Math.min(89, wind.angle() + WindRay.CONE_ANGLE_SPREAD))),
                    StrictMath.sin(az), -StrictMath.cos(az), wind.maxHeight());
        }
        public boolean test(int px, int py, int pz, int sx, int sy, int sz) {
            int dy = py - sy;
            if (dy < 0 || dy > maxHeight) return false;
            double ex = px - sx, ez = pz - sz, reach = (dy + 1) * tan + 2;
            if (ex * ex + ez * ez > reach * reach) return false;
            return ex * upX + ez * upZ >= -1.5;
        }
    }

    /**
     * Target layers of the sheltered surface {@code (x, y, z)}: rays of the cone, the row's drift line (from its first
     * sheltered cell and the clearance above the cell), the distance from the open air, the snow of the sheltered
     * neighbour upwind and the room above, then {@link #layers}. No ray beyond the cone; the rest are height reads the
     * rays already cached and hashes.
     */
    public static int target(World w, int x, int y, int z, WindRay.Wind wind, int normalDepth, int maxLayers) {
        if (normalDepth <= 0) return 0;
        double share = WindRay.exposure(w, x, y, z, wind);
        boolean roomAbove = w.room(x, y + 1, z);
        int under = under(w, x, y, z, wind);
        int[] a = anchor(x, z, wind, under);
        Drift drift = Drift.of(a[0], a[1], w.height(x, z) - y, wind, maxLayers);
        // reach band: from reach + 2 cells straight under the roof on the drift line is empty, also where the diagonal
        // distance to a side is shorter (a row next to a narrow roof's side)
        double distance = under >= drift.reach() + 2 ? under : distance(w, x, y, z, wind, under);
        return layers(share, normalDepth, maxLayers, roomAbove, roomAbove && !w.room(x, y + 2, z), drift, distance, upwindLayers(w, x, y, z, wind));
    }
}
