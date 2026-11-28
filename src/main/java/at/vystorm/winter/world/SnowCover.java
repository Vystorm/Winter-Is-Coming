package at.vystorm.winter.world;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * The smooth snow cover (1.27.0, {@code snow.cover: smooth}): how many layers the open top of a column carries, without
 * Bukkit types. Study and pictures: tools winter-snow-cover (model.cjs, DESIGN.md); this class is a port of
 * {@code smoothCover} there and must give the same numbers (SnowCoverTest compares fixtures written by the model).
 *
 * <ol>
 * <li>Biome base depth: every biome has a share of {@code snow.max-layers} ({@link BiomeTable}); the share of a block is
 * the mean over the 5x5 biome cells (4x4 blocks each) around it, read bilinearly between cell centres, so a border
 * becomes a slope about 20 blocks wide instead of a step.</li>
 * <li>Drift: a gentle low-frequency noise (periods 32 and 11 blocks), at the default drift-strength about plus or minus
 * 11 percent.</li>
 * <li>Frost: the depth grows linearly with the season's strength; below {@link SnowDepth#FULL_COVER} only a share of
 * the columns carries snow, chosen by the same stable hash as before, in small patches; warm biomes (share below
 * {@link #SPARSE}) stay patchy all winter.</li>
 * <li>Terrain: the foot of a step gets extra snow that falls off 1.5 layers per block (a ramp, also round corners and
 * pits), the top edge of a step a little less (rounded), an even slope nothing. Never below half or above double the
 * column's own depth.</li>
 * <li>Taper: next to a roof or canopy over the column's snow level the open snow drops to {@code 4 + 2.5 x distance}
 * layers, so the roof line has no ledge of a whole block.</li>
 * </ol>
 * Inputs for later seasons per world or spot and blizzard zones: {@code localWinter} scales the frost, {@code zone}
 * the depth. Deterministic: hashes of the position (and the world seed), never of the time; never lower when the frost
 * rises.
 */
public final class SnowCover {
    private SnowCover() { }

    /** {@code snow.cover}: the look of the open snow. */
    public enum Mode { CLASSIC, SMOOTH;
        public static Mode parse(String value) {
            String v = value == null ? "smooth" : value.trim().toLowerCase(Locale.ROOT);
            return switch (v) {
                case "smooth" -> SMOOTH;
                case "classic" -> CLASSIC;
                default -> throw new IllegalArgumentException("snow.cover must be smooth or classic");
            };
        }
    }

    /** Biome cells (4x4 blocks) on each side of the mean. */
    static final int BLEND = 2;
    /** Drift amplitude per unit of drift-strength. */
    static final double DRIFT_AMPLITUDE = 0.25;
    /** Ramp: layers lost per block of distance from a step; also sets the search radius (8 / SLOPE blocks). */
    static final double SLOPE = 1.5;
    /** Weights of the ramp below a step and of the rounding on top of it. */
    static final double UP = 1, DOWN = 0.6;
    /** Taper at roofs and canopies: layers at the roof line and per block of distance. */
    static final double TAPER_BASE = 4, TAPER_STEP = 2.5;
    /** Below this biome share the cover stays patchy even in full winter. */
    static final double SPARSE = 0.35;
    /** How strongly the first snow gathers in patches (0 = single columns as in classic). */
    static final double CLUMP = 0.7;
    static final int DRIFT_SALT = 0x5EED5A17, PATCH_SALT = 0x2545F491;

    /** What the rule reads around a column: heights, room above, biome shares. All in world coordinates. */
    public interface Ground {
        /** Highest motion-blocking block of the column below the plugin's own snow; {@link Integer#MIN_VALUE} when unknown (chunk not loaded). */
        int height(int x, int z);
        /** Nothing blocks the cell for snow lying under a roof: air, a small plant or the plugin's own snow. */
        boolean room(int x, int y, int z);
        /** Biome share of the 4x4 biome cell (cell coordinates = block >> 2), {@link Double#NaN} when unknown. */
        double biomeShare(int cellX, int cellZ);
    }

    /** Everything else the rule needs; {@code edge} is the soft edge at protected regions and excluded biomes (1 = none). */
    public record Input(int maxLayers, double frost, double driftStrength, double edge, boolean ramps, int salt, double localWinter, double zone) {
        public Input(int maxLayers, double frost, double driftStrength, double edge, boolean ramps, int salt) {
            this(maxLayers, frost, driftStrength, edge, ramps, salt, 1, 1);
        }
    }

    /** {@code layers}: snow on the open top; {@code base}: the depth without terrain and taper (what roofs see). */
    public record Result(int layers, int base) {
        static final Result NONE = new Result(0, 0);
    }

    /** Open snow of the column {@code (x, z)} whose snow starts at {@code rootY} (the block above its ground). */
    public static Result target(Ground g, int x, int z, int rootY, Input in) {
        if (!(in.frost() > 0) || !(in.edge() > 0)) return Result.NONE;
        double b = biomeBlend(g, x, z);
        double f = Math.min(1, in.frost() * in.localWinter());
        double d = 1 + DRIFT_AMPLITUDE * in.driftStrength() * (2 * driftField(x, z, in.salt()) - 1);
        double share = Math.min(1, b * d * in.zone() * in.edge());
        double c = SnowDepth.coverage(f);
        double s = SnowDepth.scatter(x, z);
        double thr0 = c * mix(share, 1, c) * Math.min(1, share / SPARSE);
        double thr = thr0 < 1 ? Math.max(0, Math.min(1, thr0 * (1 + CLUMP * (2 * noise32(x, z, 5, in.salt() ^ PATCH_SALT) - 1)))) : thr0;
        if ((c < 1 || thr < 1) && s >= thr) return Result.NONE;
        double e = Math.min(in.maxLayers(), in.maxLayers() * f * b * d * in.zone() * in.edge());
        if (!(e > 0)) return Result.NONE;
        int base = Math.max(1, SnowDepth.layersFor(e, x, z));
        double t = e;
        double roof = Double.POSITIVE_INFINITY;
        if (in.ramps()) {
            Terrain tr = terrain(g, x, z, rootY - 1);
            double lift = (UP * tr.up - DOWN * tr.down) * f;
            t = Math.max(e / 2, Math.min(2 * e, e + lift));
            roof = tr.roof;
        }
        int layers = Math.max(1, SnowDepth.layersFor(t, x, z));
        if (roof < Double.POSITIVE_INFINITY) layers = Math.min(layers, Math.max(1, (int)Math.floor(TAPER_BASE + TAPER_STEP * roof)));
        return new Result(Math.min(SnowDepth.MAX_LAYERS, layers), base);
    }

    /** Neighbours of the terrain term: offsets within 8 / SLOPE blocks, with their distance. */
    static final int[][] OFFSETS;
    static final double[] DISTANCE;
    static {
        double r = 8 / SLOPE - 1e-9;
        int n = (int)Math.ceil(r);
        java.util.List<int[]> list = new java.util.ArrayList<>();
        for (int dz = -n; dz <= n; dz++) for (int dx = -n; dx <= n; dx++) {
            int q = dx * dx + dz * dz;
            if (q > 0 && q <= r * r) list.add(new int[]{dx, dz});
        }
        OFFSETS = list.toArray(new int[0][]);
        DISTANCE = new double[OFFSETS.length];
        for (int i = 0; i < OFFSETS.length; i++) DISTANCE[i] = Math.sqrt(OFFSETS[i][0] * OFFSETS[i][0] + OFFSETS[i][1] * OFFSETS[i][1]);
    }

    record Terrain(double up, double down, double roof) { }

    /**
     * Ramp below steps ({@code up}: the strongest of 8 - SLOPE x distance over the higher neighbours, any height counts
     * as one step), rounding on top ({@code down}: 8 x the share of lower neighbours, fully up to half of them, less on a
     * ridge, nothing on a peak), and the nearest roof or canopy over the snow level (higher by two or more with room at
     * the snow level). Unknown neighbours count as level.
     */
    static Terrain terrain(Ground g, int x, int z, int ground) {
        double up = 0, roof = Double.POSITIVE_INFINITY;
        int low = 0, roofs = 0;
        for (int i = 0; i < OFFSETS.length; i++) {
            int nx = x + OFFSETS[i][0], nz = z + OFFSETS[i][1];
            int hn = g.height(nx, nz);
            if (hn == Integer.MIN_VALUE || hn == ground) continue;
            double dist = DISTANCE[i];
            if (hn >= ground + 2 && g.room(nx, ground + 1, nz)) {
                roofs++;
                if (dist < roof) roof = dist;
                continue;
            }
            double e = 8 - SLOPE * dist;
            if (hn > ground) { if (e > up) up = e; }
            else low++;
        }
        int all = OFFSETS.length - roofs;
        double frac = all > 0 ? low / (double)all : 0, w = frac <= 0.5 ? 1 : Math.max(0, (1 - frac) / 0.5);
        return new Terrain(up, 8 * frac * w, roof);
    }

    /** Mean biome share over (2 BLEND + 1)^2 cells, bilinear between cell centres. Unknown cells are left out. */
    static double biomeBlend(Ground g, int x, int z) {
        double u = (x - 1.5) / 4, v = (z - 1.5) / 4;
        int qx = (int)Math.floor(u), qz = (int)Math.floor(v);
        double tx = u - qx, tz = v - qz;
        return mix(mix(cellMean(g, qx, qz), cellMean(g, qx + 1, qz), tx), mix(cellMean(g, qx, qz + 1), cellMean(g, qx + 1, qz + 1), tx), tz);
    }
    static double cellMean(Ground g, int qx, int qz) {
        double s = 0;
        int n = 0;
        for (int dz = -BLEND; dz <= BLEND; dz++) for (int dx = -BLEND; dx <= BLEND; dx++) {
            double v = g.biomeShare(qx + dx, qz + dz);
            if (Double.isNaN(v)) continue;
            s += v;
            n++;
        }
        return n == 0 ? 0 : s / n;
    }

    static double driftField(int x, int z, int salt) {
        return 0.7 * noise32(x, z, 32, salt ^ DRIFT_SALT) + 0.3 * noise32(x, z, 11, salt ^ (DRIFT_SALT + 0x9E37));
    }
    /** Stable value in [0, 1) of a position: 32-bit hash (same numbers in the study's Node model). */
    static double hash32(int x, int z, int salt) {
        int h = x * 0x27d4eb2d ^ z * 0x165667b1 ^ salt;
        h = (h ^ (h >>> 15)) * 0x85ebca6b;
        h = (h ^ (h >>> 13)) * 0xc2b2ae35;
        h ^= h >>> 16;
        return (h & 0xFFFFFFFFL) / 4294967296.0;
    }
    static double noise32(int x, int z, int size, int salt) {
        int gx = Math.floorDiv(x, size), gz = Math.floorDiv(z, size);
        double tx = smooth(Math.floorMod(x, size) / (double)size), tz = smooth(Math.floorMod(z, size) / (double)size);
        return mix(mix(hash32(gx, gz, salt), hash32(gx + 1, gz, salt), tx), mix(hash32(gx, gz + 1, salt), hash32(gx + 1, gz + 1, salt), tx), tz);
    }
    private static double smooth(double t) { return t * t * (3 - 2 * t); }
    private static double mix(double a, double b, double t) { return a + (b - a) * t; }
    /** Salt of a world from its seed. */
    public static int salt(long seed) { return (int)(seed ^ (seed >>> 32)); }

    /**
     * Base depth per biome as a share of {@code snow.max-layers} ({@code snow.biome-depth}). Keys are biome names
     * without namespace ({@code plains}) or patterns with {@code *} ({@code *_taiga}, {@code *ocean*}); an exact name
     * wins over a pattern, a longer pattern over a shorter one; the admin's keys over the built-in table. Unknown biomes
     * (other plugins, data packs) get a share from their temperature and rain.
     */
    public static final class BiomeTable {
        /** Shares for every vanilla Overworld biome of Minecraft 1.20.6 to 26.x. */
        public static final Map<String, Double> DEFAULTS = defaults();
        private final Map<String, Double> exact = new java.util.HashMap<>();
        private final java.util.List<Map.Entry<String, Double>> patterns = new java.util.ArrayList<>();
        private final Map<String, Double> builtIn;

        public BiomeTable(Map<String, Double> overrides) {
            this(overrides, DEFAULTS);
        }
        BiomeTable(Map<String, Double> overrides, Map<String, Double> builtIn) {
            this.builtIn = builtIn;
            for (Map.Entry<String, Double> e : overrides.entrySet()) {
                String key = e.getKey().trim().toLowerCase(Locale.ROOT);
                double v = e.getValue();
                if (!Double.isFinite(v) || v < 0 || v > 1) throw new IllegalArgumentException("snow.biome-depth." + e.getKey() + " must be 0..1");
                if (key.contains("*")) patterns.add(Map.entry(key, v));
                else exact.put(key, v);
            }
            patterns.sort((a, b) -> Integer.compare(b.getKey().replace("*", "").length(), a.getKey().replace("*", "").length()));
        }
        public static final BiomeTable DEFAULT = new BiomeTable(Map.of());

        /** The share of a biome; {@code temperature} and {@code humidity} (Bukkit's) are only used for unknown biomes. */
        public double share(String biome, double temperature, double humidity) {
            String key = biome.toLowerCase(Locale.ROOT);
            Double v = exact.get(key);
            if (v != null) return v;
            for (Map.Entry<String, Double> p : patterns) if (matches(p.getKey(), key)) return p.getValue();
            v = builtIn.get(key);
            if (v != null) return v;
            return fallback(temperature, humidity);
        }
        /** Has the biome an entry of its own (admin key, pattern or built-in table)? */
        public boolean known(String biome) {
            String key = biome.toLowerCase(Locale.ROOT);
            if (exact.containsKey(key) || builtIn.containsKey(key)) return true;
            for (Map.Entry<String, Double> p : patterns) if (matches(p.getKey(), key)) return true;
            return false;
        }
        static boolean matches(String pattern, String key) {
            String[] parts = pattern.split("\\*", -1);
            int at = 0;
            for (int i = 0; i < parts.length; i++) {
                String part = parts[i];
                if (i == 0) { if (!key.startsWith(part)) return false; at = part.length(); continue; }
                if (i == parts.length - 1) return key.length() - part.length() >= at && key.endsWith(part);
                int found = key.indexOf(part, at);
                if (found < 0) return false;
                at = found + part.length();
            }
            return at == key.length();
        }
        /**
         * Unknown biomes: colder is deeper ({@code 1.05 - 0.45 x temperature}, 0.2 .. 1), a biome without rain (humidity 0,
         * like deserts and savannas) at most 0.2.
         */
        static double fallback(double temperature, double humidity) {
            double v = Math.max(0.2, Math.min(1, 1.05 - 0.45 * temperature));
            return humidity <= 0 ? Math.min(v, 0.2) : v;
        }
        private static Map<String, Double> defaults() {
            Map<String, Double> m = new LinkedHashMap<>();
            for (String b : new String[]{"frozen_peaks", "jagged_peaks", "snowy_slopes", "grove", "ice_spikes"}) m.put(b, 1.0);
            m.put("snowy_taiga", 0.95); m.put("snowy_plains", 0.9); m.put("snowy_beach", 0.7); m.put("frozen_river", 0.8);
            m.put("frozen_ocean", 0.8); m.put("deep_frozen_ocean", 0.8);
            for (String b : new String[]{"stony_peaks", "old_growth_pine_taiga", "old_growth_spruce_taiga", "taiga", "windswept_hills", "windswept_forest"}) m.put(b, 0.85);
            m.put("windswept_gravelly_hills", 0.8); m.put("meadow", 0.8); m.put("cherry_grove", 0.75); m.put("pale_garden", 0.75);
            for (String b : new String[]{"plains", "sunflower_plains", "forest", "flower_forest", "birch_forest", "old_growth_birch_forest"}) m.put(b, 0.7);
            m.put("dark_forest", 0.65);
            m.put("river", 0.5); m.put("stony_shore", 0.5); m.put("beach", 0.4); m.put("swamp", 0.45); m.put("mangrove_swamp", 0.35); m.put("mushroom_fields", 0.5);
            m.put("cold_ocean", 0.6); m.put("deep_cold_ocean", 0.6); m.put("ocean", 0.5); m.put("deep_ocean", 0.5);
            m.put("lukewarm_ocean", 0.35); m.put("deep_lukewarm_ocean", 0.35); m.put("warm_ocean", 0.2);
            m.put("jungle", 0.25); m.put("bamboo_jungle", 0.25); m.put("sparse_jungle", 0.3);
            m.put("savanna", 0.2); m.put("savanna_plateau", 0.2); m.put("windswept_savanna", 0.3);
            for (String b : new String[]{"desert", "badlands", "eroded_badlands", "wooded_badlands"}) m.put(b, 0.0);
            m.put("dripstone_caves", 0.7); m.put("lush_caves", 0.6); m.put("deep_dark", 0.7);
            return Collections.unmodifiableMap(m);
        }
    }
}
