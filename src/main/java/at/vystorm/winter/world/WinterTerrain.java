package at.vystorm.winter.world;

import at.vystorm.winter.WinterPlugin;
import at.vystorm.winter.WinterSettings;
import at.vystorm.winter.storage.TrackedBlocks;
import at.vystorm.winter.storage.TrackedBlocks.Key;
import java.util.*;
import org.bukkit.*;
import org.bukkit.block.*;
import org.bukkit.block.data.Levelled;
import org.bukkit.block.data.type.Snow;
import org.bukkit.event.block.BlockFormEvent;

/**
 * All Bukkit access runs on the server thread. No forced chunk loads. The positions winter changed are kept per chunk
 * inside the chunk (1.24.2, {@link TrackedBlocks}); a planned change is recorded at the end of one tick and applied to
 * the world at the start of the next, so a saved chunk always holds the record of every block in it.
 */
public final class WinterTerrain implements AutoCloseable {
    private record ChunkKey(UUID world, int x, int z) { }
    private final WinterPlugin plugin;
    private final TrackedBlocks journal;
    private final ChunkRecordStorage chunkData;
    private final Vystorm.vystorm_core._advancedMethods.areaHandler.AreaInterface area;
    private final ArrayDeque<ChunkKey> chunks = new ArrayDeque<>();
    private final Set<ChunkKey> queued = new HashSet<>();
    private final Map<ChunkKey, Integer> offsets = new HashMap<>();
    /** The tracking limit that stopped new snow, 0 while below it (1.24.2). */
    private int limitHit;
    private boolean applying;
    /** Coastal frost walks the chunks with its own cursor and budget (1.24.2), so shore searches never stop the snow. */
    private final ArrayDeque<ChunkKey> frostChunks = new ArrayDeque<>();
    private final Set<ChunkKey> frostQueued = new HashSet<>();
    private final Map<ChunkKey, Integer> frostOffsets = new HashMap<>();
    private final PassBudget budget = new PassBudget();
    /** Set when a shore search did not fit into this tick's cell budget; the frost cursor then stays on that column. */
    private boolean searchDeferred;
    /** A change waiting for its record; applied to the world only afterwards. */
    private static final class Op {
        final Key key;
        final String before, after;
        final long generation;
        final boolean form, release;
        boolean cancelled;
        Op(Key key, String before, String after, long generation, boolean form, boolean release) {
            this.key = key;
            this.before = before;
            this.after = after;
            this.generation = generation;
            this.form = form;
            this.release = release;
        }
    }
    /** Changes collected this tick; written together at the end of the tick. */
    private final List<Op> batch = new ArrayList<>();
    private List<Op> inFlight = List.of();
    /** Records of the ops in {@link #inFlight}, written at the end of the last tick; null when nothing waits. */
    private List<TrackedBlocks.Entry> recorded;
    private boolean busy(Key key) {
        for (Op op : batch) if (op.key.equals(key)) return true;
        for (Op op : inFlight) if (op.key.equals(key)) return true;
        return false;
    }
    private void cancel(java.util.function.Predicate<Key> which) {
        for (Op op : batch) if (which.test(op.key)) op.cancelled = true;
        for (Op op : inFlight) if (which.test(op.key)) op.cancelled = true;
    }
    private void flush() {
        if (batch.isEmpty() || recorded != null) return;
        List<Op> ops = List.copyOf(batch);
        batch.clear();
        inFlight = ops;
        List<TrackedBlocks.Request> requests = new ArrayList<>(ops.size());
        for (Op op : ops) requests.add(new TrackedBlocks.Request(op.key, op.before, op.after, op.release));
        recorded = journal.apply(requests);
    }
    /**
     * @param journal the chunk records; their storage must be {@code chunkData}
     * @param area Core's area manager, {@code api.area()}; the region service is looked up per call as before
     */
    public WinterTerrain(WinterPlugin plugin, TrackedBlocks journal, ChunkRecordStorage chunkData, Vystorm.vystorm_core._advancedMethods.areaHandler.AreaInterface area) {
        this.plugin = plugin;
        this.area = area;
        this.journal = journal;
        this.chunkData = chunkData;
    }
    /** The storage that keeps the records in the chunks' persistent data, for {@link TrackedBlocks}. */
    public static ChunkRecordStorage chunkStorage(org.bukkit.plugin.Plugin plugin) {
        return new ChunkRecordStorage(new NamespacedKey(plugin, "tracked"));
    }
    private static TrackedBlocks.ChunkId id(ChunkKey key) {
        return new TrackedBlocks.ChunkId(key.world(), key.x(), key.z());
    }
    private static TrackedBlocks.ChunkId id(Chunk c) {
        return new TrackedBlocks.ChunkId(c.getWorld().getUID(), c.getX(), c.getZ());
    }
    public void load(Chunk c) {
        ChunkKey key = new ChunkKey(c.getWorld().getUID(), c.getX(), c.getZ());
        World world = c.getWorld();
        chunkData.current(c);
        try {
            journal.load(id(c), (x, y, z) -> world.getBlockAt(x, y, z).getBlockData().getAsString());
        } finally {
            chunkData.current(null);
        }
        if (queued.add(key)) chunks.add(key);
        if (frostQueued.add(key)) frostChunks.add(key);
    }
    /** The chunk's record is already stored in it; the chunk leaves the memory. */
    public void unload(Chunk c) {
        chunkData.current(c);
        try {
            journal.unload(id(c));
        } finally {
            chunkData.current(null);
        }
    }
    public void unloadWorld(World world) {
        journal.unloadWorld(world.getUID());
    }
    /** Positions held in memory may not exceed the setting, or 256 per layer block and two more per loaded chunk. */
    public long limit() {
        var s = plugin.settings();
        long perChunk = 256L * ((s.layers() + 7) / 8 + 2);
        return Math.max(s.maxTracked(), perChunk * journal.loadedChunks());
    }
    /** The limit that stopped new snow and ice, 0 while winter is below it. */
    public int limitHit() {
        return limitHit;
    }
    public TrackedBlocks journal() {
        return journal;
    }
    public boolean applying() {
        return applying;
    }
    public static Key key(Block b) {
        return new Key(b.getWorld().getUID(), b.getX(), b.getY(), b.getZ());
    }
    public boolean owned(Block b) {
        var e = journal.get(key(b));
        return e != null && e.winterBlock(b.getBlockData().getAsString());
    }
    public boolean allowed(Location loc) {
        if (plugin.zones() != null && !plugin.zones().allows(loc)) return false;   // spots and build mode: cheap, checked first
        WinterSettings s = plugin.settings();
        var regions = area.regions();
        boolean fallback = !s.protectRegions() || regions.at(loc).isEmpty();
        return regions.flag(loc, s.regionFlag(), fallback);
    }
    public void release(Block b) {
        Key released = key(b);
        cancel(released::equals);
        try {
            journal.release(key(b));
        } catch (RuntimeException failure) {
            plugin.storageFailure(failure);
            throw failure;
        }
    }
    public void clear(Block b) {
        // Cancel an in-flight new upper layer too: it may still be AIR and not owned yet.
        cancel(k -> k.world().equals(b.getWorld().getUID()) && k.x() == b.getX() && k.z() == b.getZ()
                && k.y() >= b.getY() && k.y() < b.getY() + SnowDepth.MAX_BLOCKS);
        List<Block> column = new ArrayList<>();
        if (!owned(b)) return;
        column.add(b);
        if (b.getType() == Material.SNOW) {
            Block cursor = b;
            for (int i = 1; i < SnowDepth.MAX_BLOCKS && cursor.getY() + 1 < cursor.getWorld().getMaxHeight(); i++) {
                cursor = cursor.getRelative(BlockFace.UP);
                if (cursor.getType() != Material.SNOW || !owned(cursor)) break;
                column.add(cursor);
            }
        }
        // Remove the top first, durably, so clearing a stack never leaves unsupported owned snow.
        for (int i = column.size() - 1; i >= 0; i--) if (!clearOne(column.get(i))) break;
    }
    private boolean clearOne(Block b) {
        var e = journal.get(key(b));
        if (e == null || !owned(b)) return true;
        try {
            Key cleared = key(b);
            cancel(cleared::equals);
            if (journal.intent(key(b), b.getBlockData().getAsString(), e.original()) != null) {
                b.setBlockData(Bukkit.createBlockData(e.original()), false);
                release(b);
            }
            return true;
        } catch (RuntimeException failure) {
            plugin.storageFailure(failure);
            return false;
        }
    }
    /** Runs every tick; all changes of a tick share one write of the changed chunk records. */
    public void cycle() {
        if (limitHit > 0 && journal.held() < limit()) limitHit = 0;
        try {
            if (!drain()) return;
            try {
                cycleBody();
            } finally {
                try {
                    flush();
                } catch (RuntimeException failure) {
                    plugin.storageFailure(failure);
                }
            }
        } finally {
            // Removals of restored positions from drain(): stored in the same tick as the restored blocks.
            try {
                journal.commit();
            } catch (RuntimeException failure) {
                plugin.storageFailure(failure);
            }
        }
    }
    /** Season of the current terrain pass, computed once per pass instead of once per block (1.19.1). */
    private at.vystorm.winter.season.SeasonClock.Now cycleNow;
    /** Chunks whose plugin snow should be checked first (build mode, spot changes), with a cursor into their records. */
    private final ArrayDeque<ChunkKey> melt = new ArrayDeque<>();
    private final Map<ChunkKey, Integer> meltCursor = new HashMap<>();
    /** Queues the tracked chunks within {@code radius} blocks of x/z (radius < 0 = the whole world) to melt first. */
    public void prioritize(UUID world, double x, double z, int radius) {
        int cx = (int)Math.floor(x)>>4, cz = (int)Math.floor(z)>>4, r = radius < 0 ? Integer.MAX_VALUE : (radius>>4) + 1;
        for (TrackedBlocks.ChunkId id : journal.chunksWithRecords()) {
            ChunkKey key = new ChunkKey(id.world(), id.x(), id.z());
            if (!key.world().equals(world) || Math.abs(key.x() - cx) > r || Math.abs(key.z() - cz) > r || meltCursor.containsKey(key)) continue;
            melt.addLast(key);
            meltCursor.put(key, 0);
        }
    }
    /** Works through the melt queue within the change allowance; returns false when the allowance ran out. */
    private boolean drainMelt(WinterSettings s) {
        int checks = 0;
        while (!melt.isEmpty() && budget.mayChange() && checks < 256) {
            ChunkKey ref = melt.peekFirst();
            World world = Bukkit.getWorld(ref.world());
            int records = journal.count(id(ref));
            int at = meltCursor.getOrDefault(ref, 0);
            if (world == null || !world.isChunkLoaded(ref.x(), ref.z()) || at >= records) {
                melt.removeFirst();
                meltCursor.remove(ref);
                continue;
            }
            meltCursor.put(ref, at + 1);
            checks++;
            Key k = journal.keyAt(id(ref), at);
            Block block = world.getBlockAt(k.x(), k.y(), k.z());
            if (!allowed(block.getLocation()) && reconcile(block, s, false)) budget.changed();
        }
        return budget.mayChange();
    }
    private void cycleBody() {
        var s = plugin.settings();
        var clock = plugin.clock();
        cycleNow = null;
        int boost = !clock.enabled() ? 1 : clock.mode() == at.vystorm.winter.season.SeasonClock.Mode.WEEKLY ? 8 : clock.mode() == at.vystorm.winter.season.SeasonClock.Mode.MONTHLY ? 4 : 1;
        int maxColumns = Math.min(8192, s.columns() * boost), maxChanges = Math.min(512, s.changes() * boost);
        budget.tick(maxChanges, s.period(), Math.max(1, maxColumns / s.period()), s.searchBudget());
        // snow.enabled (1.24.0): off = nothing new forms and the plugin's snow and ice stay as they are; a forced thaw
        // still clears them. The year-round plants (seasons.enabled) are a feature of their own.
        boolean thaw = plugin.state().data().thaw;
        boolean lays = plugin.switches().laysSnow(), touches = plugin.switches().touchesSnow(thaw);
        if (touches && !drainMelt(s)) return;
        // Frost first within its reserved share, then the snow, then the frost again with what the snow left (1.24.2).
        boolean frost = s.frost() && touches;
        if (frost) frostPass(s, true, lays, thaw);
        snowPass(s, lays, touches, thaw, clock);
        if (frost) frostPass(s, false, lays, thaw);
    }
    /**
     * Coastal frost: forms ice on still water near a shore and melts ice whose shore is now too far. Its own chunk
     * cursor, column limit and search cell budget; a search that does not fit this tick waits on the same column, so
     * no water column is skipped without being evaluated (a skipped column could phase-lock the round-robin cursor).
     */
    private void frostPass(WinterSettings s, boolean reservedOnly, boolean lays, boolean thaw) {
        while (!frostChunks.isEmpty() && budget.frostMayChange(reservedOnly) && budget.frostColumn()) {
            ChunkKey ref = frostChunks.removeFirst();
            World world = Bukkit.getWorld(ref.world());
            if (world == null || !world.isChunkLoaded(ref.x(), ref.z())) {
                frostQueued.remove(ref);
                frostOffsets.remove(ref);
                continue;
            }
            frostChunks.addLast(ref);
            int offset = frostOffsets.merge(ref, 1, Integer::sum) - 1;
            int records = journal.count(id(ref));
            searchDeferred = false;
            boolean changed = false;
            if (offset % 2 == 0 && records > 0) {
                Key k = journal.keyAt(id(ref), (offset / 2) % records);
                Block block = world.getBlockAt(k.x(), k.y(), k.z());
                if (block.getType() == Material.ICE) changed = reconcile(block, s, true);
            } else if (lays && s.includes(world) && plugin.season().intensity() > 0 && !thaw) {
                int column = SnowDepth.column(ref.x(), ref.z(), offset / 2);
                int x = (ref.x()<<4) + (column & 15), z = (ref.z()<<4) + (column>>4);
                changed = freeze(world.getHighestBlockAt(x, z, HeightMap.MOTION_BLOCKING), s);
            }
            if (searchDeferred) {
                // Back to this very column next tick.
                frostOffsets.merge(ref, -1, Integer::sum);
                frostChunks.removeLast();
                frostChunks.addFirst(ref);
                budget.frostColumnBack();
                return;
            }
            if (changed) budget.frostChanged();
        }
    }
    private void snowPass(WinterSettings s, boolean lays, boolean touches, boolean thaw, at.vystorm.winter.season.SeasonClock clock) {
        while (!chunks.isEmpty() && budget.mayChange() && budget.snowColumn()) {
            ChunkKey ref = chunks.removeFirst();
            World world = Bukkit.getWorld(ref.world());
            if (world == null || !world.isChunkLoaded(ref.x(), ref.z())) {
                queued.remove(ref);
                offsets.remove(ref);
                continue;
            }
            chunks.addLast(ref);
            int offset = offsets.merge(ref, 1, Integer::sum) - 1;
            // Alternate journal reconciliation with landscape sampling, including covered old blocks.
            int records = journal.count(id(ref));
            if (offset % 2 == 0 && records > 0) {
                if (!touches) continue;
                Key k = journal.keyAt(id(ref), (offset / 2) % records);
                if (reconcile(world.getBlockAt(k.x(), k.y(), k.z()), s, false)) budget.changed();
            } else if (lays && s.includes(world) && plugin.season().intensity() > 0 && !thaw) {
                int column = SnowDepth.column(ref.x(), ref.z(), offset / 2);
                int x = (ref.x()<<4) + (column & 15), z = (ref.z()<<4) + (column>>4);
                Block top = world.getHighestBlockAt(x, z, HeightMap.MOTION_BLOCKING);
                // Still water is the frost pass's work: a coast does not use up the snow's columns (1.24.2).
                if (s.frost() && top.getType() == Material.WATER && ((Levelled)top.getBlockData()).getLevel() == 0) {
                    budget.waterSkip();
                    continue;
                }
                if (sample(top, s)) budget.changed();
            } else if (s.includes(world) && clock.enabled() && plugin.season().intensity() <= 0 && !plugin.state().data().thaw) {
                int column = SnowDepth.column(ref.x(), ref.z(), offset / 2);
                int x = (ref.x()<<4) + (column & 15), z = (ref.z()<<4) + (column>>4);
                if (grow(world.getHighestBlockAt(x, z, HeightMap.MOTION_BLOCKING), s)) budget.changed();
            }
        }
    }
    private boolean holdsSeasonMelt() {
        return plugin.switches().holdsSeasonMelt(plugin.state().data().thaw, plugin.season().phase());
    }
    /** @param searchIce only the frost pass may search the shore of plugin ice; the snow pass leaves that ice to it */
    private boolean reconcile(Block block, WinterSettings s, boolean searchIce) {
        var entry = journal.get(key(block));
        if (entry == null || entry.released()) return false;
        String current = block.getBlockData().getAsString();
        if (!entry.accepts(current)) {
            queueRelease(key(block));
            return false;
        }
        if (current.equals(entry.original())) {
            // Nothing left to restore here (1.24.2): the position leaves the record and the count.
            if (!busy(entry.key())) journal.forget(entry.key());
            return false;
        }
        if (current.equals("minecraft:air") && SeasonPlants.tallPlantData(entry.original())) {
            boolean winter = s.includes(block.getWorld()) && !plugin.state().data().thaw && plugin.season().intensity() > 0 && s.terrain().removeTallPlants();
            if (winter || holdsSeasonMelt()) return false;
            if (entry.original().contains("half=upper") && !SeasonPlants.tallPlant(block.getRelative(BlockFace.DOWN).getType())) return false;
            return edit(block, entry.original(), false);
        }
        double intensity = s.includes(block.getWorld()) && !plugin.state().data().thaw ? plugin.season().intensity() : 0;
        // Restore our own temporary material even if an area became protected after placement.
        boolean restricted = !allowed(block.getLocation()) || s.excludedBiomes().contains(BiomeKeys.key(block.getBiome()))
                || block.getType() == Material.ICE && !s.frost();
        if (restricted) intensity = 0;
        // snow.spring-thaw.enabled false (1.24.0): after the peak the season itself melts nothing; restrictions still do.
        boolean hold = !restricted && holdsSeasonMelt();
        if (block.getType() == Material.SNOW) {
            // Keep the lower full block until its owned upper block has melted away.
            if (block.getY() + 1 < block.getWorld().getMaxHeight()) {
                Block above = block.getRelative(BlockFace.UP);
                if (above.getType() == Material.SNOW && owned(above)) return false;
            }
            // Outside season spots or near a builder: restore in one step instead of one layer per visit (1.20.1).
            if (plugin.zones() != null && !plugin.zones().allows(block.getLocation())) return edit(block, entry.original(), false);
            if (hold) return false;
            if (!snowSupportedAt(block, s)) intensity = 0;
            Block root = snowRoot(block);
            int offset = root == null ? SnowDepth.MAX_BLOCKS : block.getY() - root.getY();
            Snow snow = (Snow)block.getBlockData();
            if (root != null && !s.vegetationSnow() && coveredPlant(root)) intensity = 0;
            int target = SnowDepth.atHeight(layers(root == null ? block : root, s, intensity), offset);
            if (snow.getLayers() > target) {
                if (snow.getLayers() == 1) return edit(block, entry.original(), false);
                snow.setLayers(snow.getLayers() - 1);
                return edit(block, snow.getAsString(), false);
            }
        } else if (block.getType() == Material.ICE) {
            if (hold) return false;
            if (intensity == 0) return edit(block, entry.original(), false);
            if (!searchIce) return false;
            int radius = radius(block, s, intensity), max = maxRadius(block, s);
            // Ice inside its radius (the usual case) is settled by the short search; only ice outside it pays the full one.
            if (!budget.maySearch(FrostBudget.bound(radius, s.searchBudget()))) {
                searchDeferred = true;
                return false;
            }
            var found = FrostBudget.meltDistance(block.getX(), block.getZ(), radius, max, s.searchBudget(), shore(block, s));
            budget.searched(found.visited());
            // Missing/temporarily unloaded shore is not evidence to melt existing ice.
            if (found.distance() > radius) return edit(block, entry.original(), false);
        }
        return false;
    }
    /** Distance to the nearest ground exactly one block higher than the ground under this snow, 0 if none is close. */
    private int stepDistance(Block root) {
        World world = root.getWorld();
        int ground = root.getY() - 1;
        for (int distance = 1; distance <= SnowDepth.RAMP_DISTANCE; distance++) for (int[] d : new int[][]{{1, 0},{-1, 0},{0, 1},{0, -1}}) {
            int x = root.getX() + d[0] * distance, z = root.getZ() + d[1] * distance;
            if (world.isChunkLoaded(x>>4, z>>4) && groundHeight(world, x, z) == ground + 1) return distance;
        }
        return 0;
    }
    /** Highest ground of a column, ignoring winter snow lying on it. */
    private int groundHeight(World world, int x, int z) {
        Block top = world.getHighestBlockAt(x, z, HeightMap.MOTION_BLOCKING);
        for (int i = 0; i < SnowDepth.MAX_BLOCKS && top.getType() == Material.SNOW && top.getY() > world.getMinHeight(); i++) top = top.getRelative(BlockFace.DOWN);
        return top.getY();
    }
    private int layers(Block b, WinterSettings s, double intensity) {
        if (intensity <= 0) return 0;
        int base = SnowDepth.target(s.layers(), intensity, b.getX(), b.getZ(), s.driftStrength(), edgeFactor(b, s));
        return s.terrain().ramps() ? SnowDepth.withRamp(base, stepDistance(b), intensity) : base;
    }
    private double edgeFactor(Block root, WinterSettings s) {
        int width = s.transitionBlocks();
        if (width == 0) return 1;
        World world = root.getWorld();
        for (int distance = 1; distance <= width; distance++) for (int[] d : new int[][]{{1, 0},{-1, 0},{0, 1},{0, -1},{1, 1},{1, -1},{-1, 1},{-1, -1}}) {
            int x = root.getX() + d[0] * distance, z = root.getZ() + d[1] * distance;
            if (!world.isChunkLoaded(x>>4, z>>4)) continue;
            Block neighbor = world.getBlockAt(x, root.getY(), z);
            if (!allowed(neighbor.getLocation()) || s.excludedBiomes().contains(BiomeKeys.key(neighbor.getBiome())))
                return distance / (double)(width + 1);
        }
        return 1;
    }
    /** Single-block plants snow may cover; the original comes back on thaw. By name, so newer plants also work on older servers. */
    private static final Set<String> SMALL_PLANTS = Set.of("SHORT_GRASS", "FERN", "DANDELION", "POPPY", "BLUE_ORCHID", "ALLIUM", "AZURE_BLUET", "RED_TULIP", "ORANGE_TULIP", "WHITE_TULIP", "PINK_TULIP", "OXEYE_DAISY", "CORNFLOWER", "LILY_OF_THE_VALLEY", "TORCHFLOWER", "DEAD_BUSH", "PINK_PETALS", "WILDFLOWERS", "LEAF_LITTER", "SHORT_DRY_GRASS", "BUSH");
    public static boolean smallPlant(Material material) {
        return SMALL_PLANTS.contains(material.name());
    }
    private boolean coveredPlant(Block root) {
        var entry = journal.get(key(root));
        return entry != null && smallPlant(Bukkit.createBlockData(entry.original()).getMaterial());
    }
    /** Lowest contiguous owned snow position, bounded even after a configuration reduction. */
    private Block snowRoot(Block block) {
        Block root = block;
        for (int i = 0; i < SnowDepth.MAX_BLOCKS; i++) {
            if (root.getY() <= root.getWorld().getMinHeight()) return root;
            Block below = root.getRelative(BlockFace.DOWN);
            if (below.getType() != Material.SNOW || !owned(below)) return root;
            root = below;
        }
        return null;
    }
    private boolean groundSupportsSnow(Block base, WinterSettings s) {
        if (base.getY() + 1 >= base.getWorld().getMaxHeight() || !allowed(base.getLocation())
                || base.getType() == Material.SNOW || s.clearMaterials().contains(base.getType())
                || base.getState() instanceof org.bukkit.block.TileState) return false;
        if (base.getBlockData() instanceof org.bukkit.block.data.type.Leaves && !s.treeSnow()) return false;
        // Stairs and bottom slabs stay untouched; the listener keeps this snow from being removed by block updates.
        if (SeasonPlants.overlay(base.getBlockData()) != SeasonPlants.Overlay.NONE) return s.terrain().coverStairsAndSlabs();
        // Vanilla's actual snow-support rule includes leaves and solid top faces, not just occluding cubes.
        return Material.SNOW.createBlockData().isSupported(base.getRelative(BlockFace.UP));
    }
    private boolean snowSupportedAt(Block block, WinterSettings s) {
        if (block.getY() <= block.getWorld().getMinHeight()) return false;
        Block below = block.getRelative(BlockFace.DOWN);
        int depth = 0;
        while (below.getType() == Material.SNOW) {
            if (++depth >= SnowDepth.MAX_BLOCKS || !owned(below)
                    || ((Snow)below.getBlockData()).getLayers() != 8
                    || below.getY() <= below.getWorld().getMinHeight()) return false;
            below = below.getRelative(BlockFace.DOWN);
        }
        return groundSupportsSnow(below, s);
    }
    private boolean sample(Block top, WinterSettings s) {
        if (!allowed(top.getLocation()) || s.excludedBiomes().contains(BiomeKeys.key(top.getBiome()))) return false;
        double intensity = plugin.season().intensity();
        // Still water belongs to the frost pass (1.24.2).
        if (top.getType() == Material.WATER && s.frost() && ((Levelled)top.getBlockData()).getLevel() == 0) return false;
        if (top.getType() == Material.ICE || top.getY() + 1 >= top.getWorld().getMaxHeight()) return false;
        Block first = top.getType() == Material.SNOW || smallPlant(top.getType()) ? top : top.getRelative(BlockFace.UP);
        if (first.getType() == Material.SNOW && !owned(first)) return false;
        Block root = snowRoot(first);
        if (root == null || !snowSupportedAt(root, s) || !s.vegetationSnow() && coveredPlant(root)) return false;
        if (s.terrain().removeTallPlants() && SeasonPlants.tallPlant(root.getType())) {
            // Upper half first; both come back from the journal on thaw, lower half first.
            Block upper = root.getRelative(BlockFace.UP);
            if (SeasonPlants.tallPlant(upper.getType())) return edit(upper, "minecraft:air", true);
            return edit(root, "minecraft:air", true);
        }
        int total = layers(root, s, intensity);
        for (int offset = 0; offset < SnowDepth.MAX_BLOCKS && root.getY() + offset < root.getWorld().getMaxHeight(); offset++) {
            int target = SnowDepth.atHeight(total, offset);
            if (target == 0) return false;
            Block snow = root.getRelative(0, offset, 0);
            if (!allowed(snow.getLocation()) || s.excludedBiomes().contains(BiomeKeys.key(snow.getBiome()))) return false;
            int current = 0;
            if (snow.getType() == Material.SNOW) {
                if (!owned(snow)) return false;
                current = ((Snow)snow.getBlockData()).getLayers();
            } else if (smallPlant(snow.getType())) {
                if (!s.vegetationSnow() || offset != 0 || !SnowDepth.coversPlant(total)) return false;
            } else if (!snow.getType().isAir()) return false;
            if (current < target) {
                if (!snowSupportedAt(snow, s)) return false;
                Snow data = (Snow)Material.SNOW.createBlockData();
                data.setLayers(smallPlant(snow.getType()) ? SnowDepth.FIRST_LAYERS : current + 1);
                return edit(snow, data.getAsString(), true);
            }
            if (current < 8) return false;
        }
        return false;
    }
    /** Spring, summer and autumn on the highest ground only, so nothing under a roof or on farmland changes. Not journaled: the next season decides again. */
    private boolean grow(Block ground, WinterSettings s) {
        if (ground.getY() + 2 >= ground.getWorld().getMaxHeight() || ground.getType() != Material.GRASS_BLOCK) return false;
        if (!allowed(ground.getLocation()) || s.excludedBiomes().contains(BiomeKeys.key(ground.getBiome()))) return false;
        if (cycleNow == null) cycleNow = plugin.seasonNow();
        var now = cycleNow;
        Block first = ground.getRelative(BlockFace.UP), upper = first.getRelative(BlockFace.UP);
        long seed = ground.getWorld().getSeed();
        var season = now.season();
        if (season == at.vystorm.winter.season.SeasonClock.Season.AUTUMN && SeasonPlants.tallPlant(first.getType())) {
            upper.setType(Material.AIR, false);
            first.setType(Material.AIR, false);
            return true;
        }
        if (season != at.vystorm.winter.season.SeasonClock.Season.AUTUMN && first.getType() == Material.SWEET_BERRY_BUSH && SeasonPlants.oursBerry(seed, ground.getX(), ground.getZ())) {
            first.setType(Material.AIR, false);
            return true;
        }
        if (!first.getType().isAir()) return false;
        switch (SeasonPlants.plan(season, now.progress(), seed, ground.getX(), ground.getZ())) {
            case SHORT -> first.setType(Material.SHORT_GRASS, false);
            case FLOWER -> first.setType(ground.getX() % 2 == 0 ? Material.POPPY : Material.DANDELION, false);
            case BERRY -> first.setBlockData(Bukkit.createBlockData("minecraft:sweet_berry_bush[age=3]"), false);
            case TALL -> {
                if (!upper.getType().isAir()) return false;
                first.setBlockData(Bukkit.createBlockData("minecraft:tall_grass[half=lower]"), false);
                upper.setBlockData(Bukkit.createBlockData("minecraft:tall_grass[half=upper]"), false);
            }
            case NONE -> {
                return false;
            }
        }
        return true;
    }
    private int radius(Block b, WinterSettings s, double intensity) {
        int max = BiomeKeys.key(b.getBiome()).contains("ocean") ? s.oceanRadius() : s.inlandRadius();
        double uneven = 0.85 + Math.floorMod(Objects.hash(b.getX() / 3, b.getZ() / 3), 100) / 100.0 * 0.15;
        return (int)Math.floor(max * intensity * uneven);
    }
    private static int maxRadius(Block b, WinterSettings s) {
        return BiomeKeys.key(b.getBiome()).contains("ocean") ? s.oceanRadius() : s.inlandRadius();
    }
    /** Ice on still water whose shore lies within the current radius; the search stops at that radius (1.24.2). */
    private boolean freeze(Block top, WinterSettings s) {
        if (top.getType() != Material.WATER || ((Levelled)top.getBlockData()).getLevel() != 0) return false;
        if (!allowed(top.getLocation()) || s.excludedBiomes().contains(BiomeKeys.key(top.getBiome()))) return false;
        int radius = radius(top, s, plugin.season().intensity());
        if (radius < 1) return false;
        if (!budget.maySearch(FrostBudget.bound(radius, s.searchBudget()))) {
            searchDeferred = true;
            return false;
        }
        var found = FrostBudget.freezes(top.getX(), top.getZ(), radius, s.searchBudget(), shore(top, s));
        budget.searched(found.visited());
        if (found.distance() > 0 && found.distance() <= radius) return edit(top, Material.ICE.createBlockData().getAsString(), true);
        return false;
    }
    private FrostBudget.Surface shore(Block start, WinterSettings s) {
        World world = start.getWorld();
        int y = start.getY();
        return (x, z) -> {
            if (!world.isChunkLoaded(x>>4, z>>4)) return FrostBudget.Cell.UNKNOWN;
            Block b = world.getBlockAt(x, y, z);
            if (b.getType() == Material.WATER && ((Levelled)b.getBlockData()).getLevel() == 0 || b.getType() == Material.ICE && owned(b)) return FrostBudget.Cell.WATER;
            if (s.shores().contains(b.getType()) && y - 2 >= world.getMinHeight()
                    && s.shores().contains(world.getBlockAt(x, y - 1, z).getType()) && s.shores().contains(world.getBlockAt(x, y - 2, z).getType())) return FrostBudget.Cell.SHORE;
            return FrostBudget.Cell.BLOCKED;
        };
    }
    private boolean edit(Block b, String next, boolean formEvent) {
        var key = key(b);
        var previous = journal.get(key);
        if (previous == null && journal.held() >= limit()) {
            // Never silent (1.24.2): the admin hears it once in the console and sees it in /winter status.
            if (limitHit == 0) plugin.trackingLimitReached(limit());
            limitHit = (int)Math.min(Integer.MAX_VALUE, limit());
            return false;
        }
        String current = b.getBlockData().getAsString();
        if (current.equals(next)) return false;
        if (previous != null && !previous.accepts(current)) return false;
        if (formEvent) {
            BlockState proposed = b.getState();
            proposed.setBlockData(Bukkit.createBlockData(next));
            BlockFormEvent event = new BlockFormEvent(b, proposed);
            applying = true;
            try {
                Bukkit.getPluginManager().callEvent(event);
            } finally {
                applying = false;
            }
            if (event.isCancelled() || !b.getBlockData().getAsString().equals(current) || !proposed.getBlockData().getAsString().equals(next)) return false;
        }
        if (busy(key)) return false;
        batch.add(new Op(key, current, next, plugin.generation(), formEvent, false));
        return true;
    }
    private void queueRelease(Key key) {
        if (busy(key)) return;
        batch.add(new Op(key, null, null, plugin.generation(), false, true));
    }
    /** Apply only recorded intents (recorded last tick); recheck chunks, epoch, protection and block data for each. */
    private boolean drain() {
        if (recorded == null) return true;
        List<Op> ops = inFlight;
        List<TrackedBlocks.Entry> entries = recorded;
        recorded = null;
        inFlight = List.of();
        try {
            for (int i = 0; i < ops.size(); i++) apply(ops.get(i), entries.get(i));
        } catch (Exception failure) {
            plugin.storageFailure(failure);
            return false;
        }
        return true;
    }
    private void apply(Op op, TrackedBlocks.Entry entry) {
        if (entry == null || op.release) return;
        Key key = op.key;
        String before = op.before, after = op.after;
        World world = Bukkit.getWorld(key.world());
        if (op.cancelled) {
            queueRelease(key);
            return;
        }
        if (world == null || !world.isChunkLoaded(key.x()>>4, key.z()>>4) || op.generation != plugin.generation()) return;
        Block block = world.getBlockAt(key.x(), key.y(), key.z());
        if (!block.getBlockData().getAsString().equals(before)) {
            queueRelease(key);
            return;
        }
        if (op.form && (!plugin.controls(world) || !plugin.switches().laysSnow() || !allowed(block.getLocation()))) return;
        boolean snowForm = op.form && after.startsWith("minecraft:snow[");
        if (snowForm && (!snowSupportedAt(block, plugin.settings()) || smallPlant(block.getType()) && !plugin.settings().vegetationSnow())) return;
        // Protection can change in between. Re-fire immediately before applying.
        if (op.form) {
            BlockState state = block.getState();
            state.setBlockData(Bukkit.createBlockData(after));
            BlockFormEvent event = new BlockFormEvent(block, state);
            applying = true;
            try {
                Bukkit.getPluginManager().callEvent(event);
            } finally {
                applying = false;
            }
            if (event.isCancelled() || !state.getBlockData().getAsString().equals(after)
                    || !block.getBlockData().getAsString().equals(before)) return;
        }
        if (snowForm && (!snowSupportedAt(block, plugin.settings()) || smallPlant(block.getType()) && !plugin.settings().vegetationSnow())) return;
        block.setBlockData(Bukkit.createBlockData(after), false);
        // The original block is back: nothing is left to restore at this position (1.24.2).
        if (after.equals(entry.original())) journal.forget(key);
    }
    @Override public void close() {
        // An un-applied intent remains in its chunk's record for reconciliation on restart.
        journal.commit();
    }
}
