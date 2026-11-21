package at.vystorm.winter.world;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The drift lines of the rows under a roof (look of 2026-11-21, second pass "lobes"; study: tools winter-slant
 * soft-edge/lobes.cjs). Every row gets its line from stable hashes of its first sheltered cell (end, shape, phase: smooth
 * noises of short periods with a second octave, plus grain per row), so the same position gives the same line at every
 * pass and after every restart.
 *
 * <p>No three neighbouring rows look the same: rows are compared by what they would look like at rest under a straight
 * roof of their clearance (the rule's fixed point on a virtual roof, rays worked out once per clearance and wind) at the
 * depth a full winter brings ({@code maxLayers}); the k-th row of a run of equal rows takes an alternative line when k is
 * a multiple of three, one that looks different from itself and from the next row. The choice never depends on the
 * frost, so the profile stays monotone in it. Then the row's profile is lifted by its neighbours' lines (at least theirs
 * minus two layers per row), so no lobe boundary makes a sideways step of three layers.
 */
final class DriftLines {
    /** Neighbouring rows on each side that lift a row's profile. */
    static final int LIFT = 3;
    /** Rows looked back to find where a run of equal rows starts. */
    static final int LOOK = 12;
    /** Noise periods of the end (two octaves), the shape and the phase, in blocks. */
    private static final int PERIOD_END = 3, PERIOD_END_FINE = 2, PERIOD_POWER = 3, PERIOD_PHASE = 2;
    /** Weights of the grain (per row) in end, shape and phase, and of the second octave in shape and phase. */
    private static final double GRAIN_END = 0.15, GRAIN_POWER = 0.2, GRAIN_PHASE = 0.3, OCTAVE = 0.35;
    private static final double GOLDEN_PHASE = 0.999;

    private DriftLines() { }

    private record Key(int anchorX, int anchorZ, int clearance, WindRay.Wind wind, int maxLayers) { }
    /** The cells of one row share their drift: a small cache (a pure function of the key, so dropping it changes nothing). */
    private static final Map<Key, Shelter.Drift> CACHE = new ConcurrentHashMap<>();
    private static final int CACHE_LIMIT = 4096;

    static Shelter.Drift of(int anchorX, int anchorZ, int clearance, WindRay.Wind wind, int maxLayers) {
        Key key = new Key(anchorX, anchorZ, clearance, wind, maxLayers);
        Shelter.Drift d = CACHE.get(key);
        if (d != null) return d;
        if (CACHE.size() >= CACHE_LIMIT) CACHE.clear();
        d = compute(anchorX, anchorZ, clearance, wind, maxLayers);
        CACHE.put(key, d);
        return d;
    }

    private static Shelter.Drift compute(int anchorX, int anchorZ, int clearance, WindRay.Wind wind, int maxLayers) {
        int reach = Math.max(0, WindRay.singleRayReach(clearance, wind.angle()));
        int[] up = Shelter.upwindStep(wind);
        int sx = up[1], sz = -up[0];
        int ref = referenceDepth(maxLayers, clearance);
        double[] shares = virtualShares(clearance, wind, reach);
        int lo = -LIFT - LOOK + 1, hi = LIFT + 1;
        Shelter.Line[] base = new Shelter.Line[hi - lo + 1];
        int[][] key = new int[hi - lo + 1][];
        for (int j = lo; j <= hi; j++) {
            base[j - lo] = base(anchorX + sx * j, anchorZ + sz * j, reach);
            key[j - lo] = rest(base[j - lo], shares, ref);
        }
        List<Shelter.Line> lines = new ArrayList<>(2 * LIFT + 1);
        for (int s = -LIFT; s <= LIFT; s++) {
            int k = 1;
            while (k < LOOK && Arrays.equals(key[s - k - lo], key[s - lo])) k++;
            Shelter.Line line = base[s - lo];
            if (k % 3 == 0) {
                int[] own = key[s - lo], next = key[s + 1 - lo];
                for (Shelter.Line a : alternatives(line)) {
                    int[] kk = rest(a, shares, ref);
                    if (!Arrays.equals(kk, own) && !Arrays.equals(kk, next)) { line = a; break; }
                }
            }
            lines.add(line);
        }
        return new Shelter.Drift(lines);
    }

    /** Depth at which rows are compared: what a straight roof of this clearance holds in a full winter. */
    static int referenceDepth(int maxLayers, int clearance) {
        return Math.min(maxLayers, Math.min(clearance <= 1 ? 7 : 8, clearance == 2 ? Shelter.LOW_CEILING_LAYERS : 8));
    }

    /** The row's own line from stable hashes of its anchor. */
    static Shelter.Line base(int ax, int az, int reach) {
        double t = (0.85 - GRAIN_END) * SnowDepth.noise(ax + 7919, az - 104729, PERIOD_END) + 0.15 * SnowDepth.noise(ax - 3571, az + 2741, PERIOD_END_FINE)
                + GRAIN_END * SnowDepth.hash(ax + 86028121, az - 49979687);
        double p = (1 - GRAIN_POWER - OCTAVE) * SnowDepth.noise(ax - 15485863, az + 32452843, PERIOD_POWER) + GRAIN_POWER * SnowDepth.hash(ax + 49979687, az + 86028121);
        double ph = (1 - GRAIN_PHASE - OCTAVE) * SnowDepth.noise(ax + 104395301, az - 122949823, PERIOD_PHASE) + GRAIN_PHASE * SnowDepth.hash(ax - 122949823, az + 104395301);
        if (OCTAVE > 0) {
            p += OCTAVE * SnowDepth.noise(ax - 7477, az + 19391, Math.max(1, (PERIOD_POWER + 1) / 2));
            ph += OCTAVE * SnowDepth.noise(ax + 65537, az - 999331, Math.max(1, (PERIOD_PHASE + 1) / 2));
        }
        return new Shelter.Line(reach, reach + 0.5 + 1.5 * spread(t), 0.6 + 1.2 * spread(p), spread(ph) * GOLDEN_PHASE);
    }
    /** Noise values cluster around 0.5: 0.3 .. 0.7 spread to 0 .. 1. */
    private static double spread(double v) {
        return Math.max(0, Math.min(1, (v - 0.3) / 0.4));
    }

    /** Alternatives for the third row of a run: phase shifted, end mirrored or at its ends, shape mirrored or at its ends. */
    static List<Shelter.Line> alternatives(Shelter.Line d) {
        double t = (d.end() - d.reach() - 0.5) / 1.5;
        List<Shelter.Line> out = new ArrayList<>(63);
        for (double ph : new double[]{0.5, 0.25, 0.75, 0}) for (double tt : new double[]{t, 1 - t, 0, 1}) for (double pw : new double[]{d.power(), 2.4 - d.power(), 0.6, 1.8}) {
            if (ph == 0 && tt == t && pw == d.power()) continue;
            double v = d.phase() + ph;
            out.add(new Shelter.Line(d.reach(), d.reach() + 0.5 + 1.5 * tt, pw, (v - Math.floor(v)) * GOLDEN_PHASE));
        }
        return out;
    }

    /** The row at rest under the virtual straight roof at depth {@code full}: the amount rule worked out along the row. */
    static int[] rest(Shelter.Line line, double[] shares, int full) {
        int[] prof = line.cells(full), out = new int[prof.length];
        int up = 0;
        for (int i = 0; i < prof.length; i++) {
            int l = Math.min(full, prof[i]);
            int creep = up > 0 ? Math.max(0, Math.min(l, up - 1)) : 0;
            double share = i < shares.length ? shares[i] : 0;
            out[i] = share >= WindRay.MIN_SHARE ? Math.max(creep, Math.max(1, Math.min(l, (int)Math.round(share * full)))) : creep;
            up = out[i];
        }
        return out;
    }

    private record SharesKey(int clearance, WindRay.Wind wind) { }
    private static final Map<SharesKey, double[]> SHARES = new ConcurrentHashMap<>();

    /**
     * Shares of free rays of the cells 0 .. 9 under a virtual straight roof of this clearance whose windward edge faces
     * the dominant wind axis (open on that side only, endless otherwise), cells along the wind from the edge. Worked out
     * once per clearance and wind.
     */
    static double[] virtualShares(int clearance, WindRay.Wind wind, int reach) {
        double[] s = SHARES.computeIfAbsent(new SharesKey(clearance, wind), k -> {
            int[] up = Shelter.upwindStep(wind);
            int ux = up[0], uz = up[1], top = clearance + 1;
            WindRay.Voxels roof = new WindRay.Voxels() {
                private boolean roofed(int x, int z) { return x * ux + z * uz <= 0; }
                @Override public boolean loaded(int x, int z) { return true; }
                @Override public boolean blocks(int x, int y, int z) { return y <= 0 || (y == top && roofed(x, z)); }
                @Override public int height(int x, int z) { return roofed(x, z) ? top : 0; }
            };
            double[] out = new double[10];
            for (int d = 0; d < out.length; d++) out[d] = WindRay.free(roof, -ux * d, 1, -uz * d, wind) / (double)WindRay.CONE_RAYS;
            return out;
        });
        return s;
    }
}
