package at.vystorm.winter.world;

import java.util.ArrayList;
import java.util.List;

/**
 * In-memory block world for the wind-blown snow tests: a port of the design study's model (tools winter-slant,
 * lib.js World and scenes.js). x = east, y = up, z = south, ground block at y = 0; outside the scene the land continues
 * as flat open ground.
 */
final class SceneWorld implements Shelter.World {
    /** SNOW = the plugin's own snow (rays pass, the server's heightmap counts it). */
    static final int AIR = 0, SOLID = 1, LEAVES = 2, GLASS = 3, FENCE = 4, SLAB = 5, STAIRS = 6, DOOR = 7, BARS = 8, TRAPDOOR = 9, LOG = 10, PLANT = 11, PLANKS = 12, SNOW = 13, PANE = 14, OPEN_DOOR = 15;
    /** Material names of the types, for the classification test. */
    static final String[] MATERIAL = {"AIR", "STONE", "OAK_LEAVES", "GLASS", "OAK_FENCE", "OAK_SLAB", "OAK_STAIRS", "OAK_DOOR", "IRON_BARS", "OAK_TRAPDOOR", "OAK_LOG", "SHORT_GRASS", "OAK_PLANKS", "SNOW", "GLASS_PANE", "OAK_DOOR"};
    /** Reads of blocks and heights, as a cost measure. */
    long reads;
    final int w, h, d;
    private final byte[] a;
    /** Layers of the plugin's snow per cell (only where the type is SNOW; a SNOW cell without an entry has 8). */
    private final java.util.Map<Integer, Integer> layers = new java.util.HashMap<>();
    /** Leaves let rays through (snow.wind-blown.leaves-block: false). */
    boolean leavesPass;
    /** Columns whose chunk counts as not loaded. */
    java.util.function.BiPredicate<Integer, Integer> unloaded = (x, z) -> false;

    SceneWorld(int w, int h, int d) {
        this.w = w;
        this.h = h;
        this.d = d;
        this.a = new byte[w * h * d];
    }
    private int idx(int x, int y, int z) { return (y * d + z) * w + x; }
    boolean inXZ(int x, int z) { return x >= 0 && z >= 0 && x < w && z < d; }
    int get(int x, int y, int z) {
        if (y < 0) return SOLID;
        if (y >= h) return AIR;
        if (!inXZ(x, z)) return y == 0 ? SOLID : AIR;
        return a[idx(x, y, z)];
    }
    void set(int x, int y, int z, int t) {
        if (!inXZ(x, z) || y < 0 || y >= h) return;
        a[idx(x, y, z)] = (byte)t;
        layers.remove(idx(x, y, z));
    }
    /** The plugin's snow of {@code n} layers in the cell, air for 0. */
    void snow(int x, int y, int z, int n) {
        set(x, y, z, n > 0 ? SNOW : AIR);
        if (n > 0 && inXZ(x, z) && y >= 0 && y < h) layers.put(idx(x, y, z), n);
    }
    @Override public int snowLayers(int x, int y, int z) {
        if (get(x, y, z) != SNOW) return 0;
        return layers.getOrDefault(idx(x, y, z), 8);
    }
    SceneWorld fill(int x0, int y0, int z0, int x1, int y1, int z1, int t) {
        for (int y = Math.min(y0, y1); y <= Math.max(y0, y1); y++)
            for (int z = Math.min(z0, z1); z <= Math.max(z0, z1); z++)
                for (int x = Math.min(x0, x1); x <= Math.max(x0, x1); x++) set(x, y, z, t);
        return this;
    }
    SceneWorld ground() { return fill(0, 0, 0, w - 1, 0, d - 1, SOLID); }

    /** Motion-blocking heightmap: the highest block that is not air or a small plant. */
    @Override public int height(int x, int z) {
        reads++;
        if (!inXZ(x, z)) return 0;
        for (int y = h - 1; y >= 0; y--) { int t = a[idx(x, y, z)]; if (t != AIR && t != PLANT && t != SNOW) return y; }
        return -1;
    }
    @Override public int rawHeight(int x, int z) {
        reads++;
        if (!inXZ(x, z)) return 0;
        for (int y = h - 1; y >= 0; y--) { int t = a[idx(x, y, z)]; if (t != AIR && t != PLANT) return y; }
        return -1;
    }
    @Override public boolean loaded(int x, int z) { return !unloaded.test(x, z); }
    @Override public boolean blocks(int x, int y, int z) {
        reads++;
        return stops(get(x, y, z));
    }
    boolean stops(int t) {
        return Shelter.blocksRay(MATERIAL[t], true, !leavesPass);
    }
    @Override public boolean surface(int x, int y, int z) {
        int t = get(x, y, z);
        return (space(t) || t == SNOW) && supports(get(x, y - 1, z));
    }
    @Override public boolean room(int x, int y, int z) { return space(get(x, y, z)); }
    @Override public int minY() { return 0; }

    /**
     * Runs the full amount rule ({@link Shelter#target}) over every sheltered surface of the scene, one layer up or down
     * per visit as the plugin does, until a whole pass changes nothing. Passes go west to east and back (both orders), so
     * the result does not depend on the order of the visits. Returns the passes needed; fails after {@code maxPasses}.
     */
    int settle(WindRay.Wind wind, int normalDepth, int maxLayers, boolean filter, int maxPasses) {
        for (int pass = 1; pass <= maxPasses; pass++) {
            boolean changed = false;
            for (int i = 0; i < w; i++) {
                int x = pass % 2 == 1 ? i : w - 1 - i;
                for (int z = 0; z < d; z++) {
                    if (filter && !Shelter.candidate(this, x, z, wind)) continue;
                    for (int y : Shelter.surfaces(this, x, z, wind.maxHeight())) {
                        int now = snowLayers(x, y, z), target = Shelter.target(this, x, y, z, wind, normalDepth, maxLayers);
                        if (now < target) { snow(x, y, z, now + 1); changed = true; }
                        else if (now > target) { snow(x, y, z, now - 1); changed = true; }
                    }
                }
            }
            if (!changed) return pass;
        }
        throw new AssertionError("the amount rule did not come to rest within " + maxPasses + " passes");
    }
    /** Layers of the plugin's snow along the row {@code z} at height {@code y}, from {@code x0} for {@code n} cells. */
    int[] row(int x0, int y, int z, int n) {
        int[] out = new int[n];
        for (int i = 0; i < n; i++) out[i] = snowLayers(x0 + i, y, z);
        return out;
    }
    static boolean supports(int t) { return t == SOLID || t == LOG || t == LEAVES || t == GLASS || t == SLAB || t == STAIRS || t == PLANKS; }
    static boolean space(int t) { return t == AIR || t == PLANT; }

    /** A block top that could hold snow: air or a small plant above a block that takes snow. */
    record Surface(int x, int y, int z, boolean top) { }
    List<Surface> surfaces() {
        List<Surface> out = new ArrayList<>();
        for (int z = 0; z < d; z++) for (int x = 0; x < w; x++) {
            int hm = height(x, z);
            for (int y = 1; y < h; y++) if (space(get(x, y, z)) && supports(get(x, y - 1, z))) out.add(new Surface(x, y, z, y == hm + 1));
        }
        return out;
    }
    /** Sheltered surfaces (below the column's heightmap). */
    List<Surface> sheltered() { return surfaces().stream().filter(s -> !s.top()).toList(); }

    /** Cells a ray may cross, face-connected to the sky or to the open land around the scene (independent flood fill). */
    boolean[] skyConnected() {
        boolean[] seen = new boolean[w * h * d];
        java.util.ArrayDeque<int[]> q = new java.util.ArrayDeque<>();
        java.util.function.Consumer<int[]> push = p -> {
            int i = idx(p[0], p[1], p[2]);
            if (!seen[i] && !stops(get(p[0], p[1], p[2]))) { seen[i] = true; q.push(p); }
        };
        for (int z = 0; z < d; z++) for (int x = 0; x < w; x++) push.accept(new int[]{x, h - 1, z});
        for (int y = 1; y < h; y++) for (int i = 0; i < w; i++) { push.accept(new int[]{i, y, 0}); push.accept(new int[]{i, y, d - 1}); }
        for (int y = 1; y < h; y++) for (int i = 0; i < d; i++) { push.accept(new int[]{0, y, i}); push.accept(new int[]{w - 1, y, i}); }
        int[][] dirs = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};
        while (!q.isEmpty()) {
            int[] p = q.pop();
            for (int[] dd : dirs) {
                int x = p[0] + dd[0], y = p[1] + dd[1], z = p[2] + dd[2];
                if (y < 0 || y >= h || !inXZ(x, z)) continue;
                push.accept(new int[]{x, y, z});
            }
        }
        return seen;
    }
    boolean open(boolean[] sky, int x, int y, int z) { return sky[idx(x, y, z)]; }

    /** Point sampling of the block that holds the ray point (forbidden; kept to prove that it leaks). */
    boolean naiveRay(double sx, double sz, int y0, double angle, double from, int maxRise) {
        double tn = StrictMath.tan(StrictMath.toRadians(angle));
        double dx = StrictMath.sin(StrictMath.toRadians(from)) * tn, dz = -StrictMath.cos(StrictMath.toRadians(from)) * tn;
        for (double s = 0.02; s < maxRise; s += 0.05) {
            int x = (int)Math.floor(sx + dx * s), z = (int)Math.floor(sz + dz * s), y = (int)Math.floor(y0 + s);
            if (stops(get(x, y, z))) return false;
            if (height(x, z) < y) return true;
        }
        return false;
    }

    // ---- scenes of the study (scenes.js, proof.js) ----------------------------------------------------------------

    static SceneWorld roof(int clearance, int overhang) {
        SceneWorld s = new SceneWorld(30, clearance + 6, 21).ground();
        int cx = 15, cz = 10;
        for (int[] p : new int[][]{{cx - 2, cz - 2}, {cx + 2, cz - 2}, {cx - 2, cz + 2}, {cx + 2, cz + 2}}) s.fill(p[0], 1, p[1], p[0], clearance, p[1], LOG);
        s.fill(cx - 2 - overhang, clearance + 1, cz - 2 - overhang, cx + 2 + overhang, clearance + 1, cz + 2 + overhang, PLANKS);
        return s;
    }
    static int roofEdge(int overhang) { return 15 - 2 - overhang; }

    static void house(SceneWorld s, int x0, int z0, int x1, int z1, int wallH, int eave, int[][] openings) {
        for (int y = 1; y <= wallH; y++) for (int z = z0; z <= z1; z++) for (int x = x0; x <= x1; x++)
            if (x == x0 || x == x1 || z == z0 || z == z1) s.set(x, y, z, PLANKS);
        s.fill(x0 - eave, wallH + 1, z0 - eave, x1 + eave, wallH + 1, z1 + eave, PLANKS);
        for (int[] op : openings) s.fill(op[0], op[1], op[2], op[3], op[4], op[5], op.length > 6 ? op[6] : AIR);
    }
    static SceneWorld houses() {
        SceneWorld s = new SceneWorld(44, 10, 20).ground();
        house(s, 6, 5, 14, 13, 3, 1, new int[][]{{6, 1, 8, 6, 2, 8}, {6, 2, 11, 6, 2, 11}});
        house(s, 26, 5, 34, 13, 3, 1, new int[][]{{34, 1, 8, 34, 2, 8}, {34, 2, 11, 34, 2, 11, GLASS}});
        return s;
    }
    static SceneWorld porch() {
        SceneWorld s = new SceneWorld(36, 10, 22).ground();
        house(s, 16, 6, 24, 15, 4, 0, new int[][]{{16, 1, 10, 16, 2, 10, DOOR}});
        s.fill(12, 5, 6, 15, 5, 15, PLANKS);
        s.fill(12, 1, 6, 15, 4, 6, PLANKS);
        s.fill(12, 1, 15, 15, 4, 15, PLANKS);
        s.fill(12, 1, 7, 12, 4, 7, LOG);
        s.fill(12, 1, 14, 12, 4, 14, LOG);
        return s;
    }
    private static void blob(SceneWorld s, int cx, int y, int cz, int r, int t, boolean cut) {
        for (int dz = -r; dz <= r; dz++) for (int dx = -r; dx <= r; dx++) {
            if (cut && r > 0 && Math.abs(dx) == r && Math.abs(dz) == r) continue;
            if (s.get(cx + dx, y, cz + dz) == AIR) s.set(cx + dx, y, cz + dz, t);
        }
    }
    static SceneWorld trees() {
        SceneWorld s = new SceneWorld(50, 13, 18).ground();
        // oak
        s.fill(8, 1, 8, 8, 4, 8, LOG);
        blob(s, 8, 5, 8, 2, LEAVES, true); blob(s, 8, 6, 8, 2, LEAVES, true); blob(s, 8, 7, 8, 1, LEAVES, false); blob(s, 8, 8, 8, 1, LEAVES, true);
        // dark oak
        s.fill(22, 1, 8, 23, 6, 9, LOG);
        for (int y : new int[]{6, 7}) for (int dz = -3; dz <= 4; dz++) for (int dx = -3; dx <= 4; dx++) {
            if ((dx == -3 || dx == 4) && (dz == -3 || dz == 4)) continue;
            if (s.get(22 + dx, y, 8 + dz) == AIR) s.set(22 + dx, y, 8 + dz, LEAVES);
        }
        for (int dz = -2; dz <= 3; dz++) for (int dx = -2; dx <= 3; dx++) if (s.get(22 + dx, 8, 8 + dz) == AIR) s.set(22 + dx, 8, 8 + dz, LEAVES);
        // spruce
        s.fill(38, 1, 8, 38, 9, 8, LOG);
        int[][] layers = {{2, 2}, {3, 1}, {4, 2}, {5, 1}, {6, 1}, {7, 0}, {8, 1}, {9, 0}};
        for (int[] l : layers) blob(s, 38, l[0], 8, l[1], LEAVES, l[1] > 1);
        s.set(38, 10, 8, LEAVES);
        return s;
    }

    /** Walls only touch at block edges (diagonal), 4 high, roofed. */
    static SceneWorld diamondRoom() {
        SceneWorld s = new SceneWorld(21, 8, 21).ground();
        int c = 10, r = 6;
        for (int z = 0; z < 21; z++) for (int x = 0; x < 21; x++) {
            int dd = Math.abs(x - c) + Math.abs(z - c);
            if (dd == r) s.fill(x, 1, z, x, 4, z, SOLID);
            if (dd <= r) s.set(x, 5, z, PLANKS);
        }
        return s;
    }
    /**
     * The diamond room under a roof that covers everything but a strip on the west: the cells next to the room's corners
     * outside are sheltered and get snow (rays and creep), right next to room cells that touch them only at an edge.
     */
    static SceneWorld diamondRoomUnderRoof() {
        SceneWorld s = diamondRoom();
        for (int z = 0; z < s.d; z++) for (int x = 3; x < s.w; x++) s.set(x, 5, z, PLANKS);
        return s;
    }
    /** Column of the windward edge of {@link #creepGuard}. */
    static final int CREEP_EDGE = 8;
    /**
     * A roof of clearance 3 open to the west (edge at x = {@link #CREEP_EDGE}) and, {@code gap} open cells behind the
     * edge, a closed room (x .. 20, z 6..14) whose windward wall is of the type {@code wall}.
     */
    static SceneWorld creepGuard(int wall, int gap) {
        SceneWorld s = new SceneWorld(30, 9, 21).ground();
        s.fill(CREEP_EDGE, 4, 2, 24, 4, 18, PLANKS);
        int wx = CREEP_EDGE + gap + 1;
        s.fill(wx, 1, 6, 20, 3, 6, PLANKS).fill(wx, 1, 14, 20, 3, 14, PLANKS).fill(20, 1, 6, 20, 3, 14, PLANKS);
        s.fill(wx, 1, 7, wx, 3, 13, wall);
        return s;
    }
    /** An open area and a roofed room separated by one diagonal wall only. */
    static SceneWorld diagonalStrip() {
        SceneWorld s = new SceneWorld(20, 7, 20).ground();
        for (int i = 0; i < 20; i++) s.fill(i, 1, i, i, 4, i, SOLID);
        for (int z = 0; z < 20; z++) for (int x = 0; x < 20; x++) if (x < z) s.set(x, 5, z, PLANKS);
        s.fill(0, 1, 0, 0, 5, 19, SOLID);
        s.fill(0, 1, 19, 19, 5, 19, SOLID);
        return s;
    }
    /** Two checkerboard layers: no straight hole, only diagonal (edge-touching) gaps. */
    static SceneWorld lattice() {
        SceneWorld s = new SceneWorld(19, 8, 19).ground();
        int a = 4, b = 14;
        for (int y = 1; y <= 4; y++) for (int z = a; z <= b; z++) for (int x = a; x <= b; x++) {
            int ring = Math.min(Math.min(x - a, b - x), Math.min(z - a, b - z));
            if ((ring == 0 || ring == 1) && (x + y + z) % 2 == 0) s.set(x, y, z, SOLID);
        }
        s.fill(a, 5, a, b, 5, b, PLANKS);
        return s;
    }
    /** Random closed rooms with one-block walls, some stacked (proof.js boxes, same random numbers). */
    static SceneWorld boxes(int seed) {
        int[] r = {seed};
        SceneWorld s = new SceneWorld(30, 14, 30).ground();
        for (int n = 0; n < 4; n++) {
            int x0 = 1 + (int)Math.floor(WindRay.next(r) * 18), z0 = 1 + (int)Math.floor(WindRay.next(r) * 18);
            int sx = 3 + (int)Math.floor(WindRay.next(r) * 8), sz = 3 + (int)Math.floor(WindRay.next(r) * 8), hh = 2 + (int)Math.floor(WindRay.next(r) * 6);
            int y0 = WindRay.next(r) < 0.3 ? 1 + (int)Math.floor(WindRay.next(r) * 4) : 1;
            int x1 = Math.min(28, x0 + sx), z1 = Math.min(28, z0 + sz), y1 = Math.min(12, y0 + hh);
            for (int y = y0 - 1; y <= y1; y++) for (int z = z0; z <= z1; z++) for (int x = x0; x <= x1; x++) {
                boolean shell = x == x0 || x == x1 || z == z0 || z == z1 || y == y0 - 1 || y == y1;
                s.set(x, y, z, shell ? (WindRay.next(r) < 0.15 ? GLASS : PLANKS) : AIR);
            }
        }
        return s;
    }
}
