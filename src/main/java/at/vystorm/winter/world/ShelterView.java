package at.vystorm.winter.world;

import java.util.HashMap;
import java.util.Map;

/**
 * The world as wind-blown snow sees it (1.25.0), over a small {@link Source} so it can be tested without a server.
 * Reads are cached for one evaluation ({@link #reset} between columns): the eight rays of a cone cross the same blocks.
 * Never loads a chunk: a column in an unloaded chunk is "not loaded" and stops the ray.
 */
final class ShelterView implements Shelter.World {
    /** Raw reads; on a server every call goes to the world. */
    interface Source {
        boolean chunkLoaded(int chunkX, int chunkZ);
        /** Motion-blocking heightmap: y of the column's highest such block. */
        int heightmap(int x, int z);
        /** Material name of the block ({@code Material.name()}). */
        String material(int x, int y, int z);
        /** The block is snow the plugin laid (tracked and still the plugin's). */
        boolean own(int x, int y, int z);
        /** The block takes snow on top by the plugin's usual rules (support, protection, clear materials, leaves switch). */
        boolean takesSnow(int x, int y, int z);
        int minY();
        /** Layers (1..8) of the snow block at the position; only asked for snow blocks. */
        int layers(int x, int y, int z);
    }

    private final Source source;
    private final boolean leavesBlock, plants;
    private final Map<Long, Integer> heights = new HashMap<>();
    private final Map<Long, Boolean> stops = new HashMap<>();
    private long reads;

    /** @param plants small plants may be covered ({@code snow.cover-small-plants}) */
    ShelterView(Source source, boolean leavesBlock, boolean plants) {
        this.source = source;
        this.leavesBlock = leavesBlock;
        this.plants = plants;
    }
    /** Forgets the cached reads (the world may have changed). */
    void reset() {
        heights.clear();
        stops.clear();
    }
    /** Block and heightmap reads so far. */
    long reads() {
        return reads;
    }
    private static long column(int x, int z) {
        return (long)x << 32 ^ (z & 0xFFFFFFFFL);
    }
    private static long cell(int x, int y, int z) {
        return ((long)x & 0x3FFFFFF) << 38 | ((long)z & 0x3FFFFFF) << 12 | (y & 0xFFF);
    }
    @Override public boolean loaded(int x, int z) {
        return source.chunkLoaded(x >> 4, z >> 4);
    }
    @Override public boolean blocks(int x, int y, int z) {
        return stops.computeIfAbsent(cell(x, y, z), k -> {
            reads++;
            String m = source.material(x, y, z);
            return Shelter.blocksRay(m, m.equals("SNOW") && source.own(x, y, z), leavesBlock);
        });
    }
    /** The heightmap below the plugin's own snow: open ground keeps its sky however deep the snow on it lies. */
    @Override public int height(int x, int z) {
        return heights.computeIfAbsent(column(x, z), k -> {
            reads++;
            int y = source.heightmap(x, z), min = source.minY();
            for (int i = 0; i < SnowDepth.MAX_BLOCKS && y > min; i++) {
                reads++;
                if (!source.material(x, y, z).equals("SNOW") || !source.own(x, y, z)) break;
                y--;
            }
            return y;
        });
    }
    @Override public int rawHeight(int x, int z) {
        reads++;
        return source.heightmap(x, z);
    }
    @Override public boolean surface(int x, int y, int z) {
        reads++;
        String m = source.material(x, y, z);
        boolean free = Shelter.blocksRay(m, false, true) ? m.equals("SNOW") && source.own(x, y, z)
                : !Shelter.SMALL_PLANTS.contains(m) || plants;
        if (!free) return false;
        reads++;
        return !source.material(x, y - 1, z).equals("SNOW") && source.takesSnow(x, y - 1, z);
    }
    @Override public boolean room(int x, int y, int z) {
        reads++;
        return !Shelter.blocksRay(source.material(x, y, z), false, true);
    }
    @Override public int minY() {
        return source.minY();
    }
    @Override public int snowLayers(int x, int y, int z) {
        reads++;
        if (!source.material(x, y, z).equals("SNOW") || !source.own(x, y, z)) return 0;
        return source.layers(x, y, z);
    }
}
