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
    /** The snow pass's round over the loaded chunks (1.27.0). */
    private final ChunkRounds<ChunkKey> rounds = new ChunkRounds<>();
    /** The tracking limit that stopped new snow, 0 while below it (1.24.2). */
    private int limitHit;
    private boolean applying;
    /**
     * Coastal frost walks the chunks with its own round and budget (1.24.2), so shore searches never stop the snow; the
     * same {@link ChunkRounds} walk as the snow since 1.27.0.
     */
    private final ChunkRounds<ChunkKey> frostRounds = new ChunkRounds<>();
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
        /** A bulk edit wrote this position after the change was planned: the change is dropped, the edit decided (1.27.0). */
        boolean dropped;
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
        rounds.add(key);
        frostRounds.add(key);
        // A chunk that loads inside a snow-free circle is cleared like the rest of the circle (1.28.0).
        BuildAreas areas = plugin.buildAreas();
        if (areas != null && !areas.empty() && journal.active(id(c)) > 0 && areas.touches(key.world(), key.x(), key.z()))
            sweep.add(new BuildAreas.Clear(key.world(), (key.x() << 4) + 8, (key.z() << 4) + 8, 12), null);
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
    /**
     * A bulk edit wrote cells of one chunk without block events (1.27.0, {@link EditAware}): the same as a player's place
     * or break for each cell ({@link TrackedBlocks#edited}), and a change the plugin planned for one of those cells is
     * dropped. Server thread; nothing is read from the world.
     */
    public void edited(TrackedBlocks.EditedCells cells) {
        dropPlanned(cells);
        try {
            journal.edited(cells);
        } catch (RuntimeException failure) {
            plugin.storageFailure(failure);
            throw failure;
        }
    }
    private void dropPlanned(TrackedBlocks.EditedCells cells) {
        if (batch.isEmpty() && inFlight.isEmpty()) return;
        Map<Integer, List<Op>> here = null;
        for (List<Op> ops : List.of(batch, inFlight)) for (Op op : ops) {
            Key k = op.key;
            if (!k.world().equals(cells.world()) || k.x() >> 4 != cells.chunkX() || k.z() >> 4 != cells.chunkZ()) continue;
            if (here == null) here = new HashMap<>();
            here.computeIfAbsent(k.y() << 8 | (k.z() & 15) << 4 | k.x() & 15, p -> new ArrayList<>(1)).add(op);
        }
        if (here == null) return;
        for (int i = 0, n = cells.size(); i < n; i++) {
            List<Op> ops = here.get(cells.y(i) << 8 | (cells.z(i) & 15) << 4 | cells.x(i) & 15);
            if (ops != null) for (Op op : ops) op.dropped = true;
        }
        batch.removeIf(op -> op.dropped);
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
    // ---- snow-free building (1.28.0) ---------------------------------------------------------------------------------
    private final ClearSweep sweep = new ClearSweep();
    /** Queues a snow-free circle: the plugin's snow and ice inside go back to the original, nearest first. */
    public void clearCircle(BuildAreas.Clear circle, java.util.function.Consumer<ClearSweep.Done> done) {
        sweep.add(circle, done);
    }
    public ClearSweep sweep() {
        return sweep;
    }
    /** True when a snow-free building circle covers this column now. */
    private boolean buildFree(Block b) {
        BuildAreas areas = plugin.buildAreas();
        return areas != null && areas.free(b.getWorld().getUID(), b.getX(), b.getZ());
    }
    /**
     * The plugin's snow carries a block someone put on it (a solid block right above that is not the plugin's): a
     * snow-free circle leaves it for the thaw, so a build made on snow before 1.28.0 does not drop by a block now.
     */
    private boolean carriesBlock(Block snow) {
        if (snow.getY() + 1 >= snow.getWorld().getMaxHeight()) return false;
        Block above = snow.getRelative(BlockFace.UP);
        Material m = above.getType();
        if (m.isAir() || smallPlant(m) || m == Material.SNOW && owned(above)) return false;
        return m.isSolid();
    }
    private ClearSweep.Cells sweepCells(WinterSettings s) {
        return new ClearSweep.Cells() {
            @Override public List<int[]> collect(BuildAreas.Clear circle) {
                List<int[]> out = new ArrayList<>();
                World world = Bukkit.getWorld(circle.world());
                if (world == null) return out;
                long r2 = (long)circle.radius() * circle.radius();
                for (int cx = (circle.x() - circle.radius()) >> 4; cx <= (circle.x() + circle.radius()) >> 4; cx++)
                    for (int cz = (circle.z() - circle.radius()) >> 4; cz <= (circle.z() + circle.radius()) >> 4; cz++) {
                        if (!world.isChunkLoaded(cx, cz)) continue;
                        TrackedBlocks.ChunkId chunk = new TrackedBlocks.ChunkId(circle.world(), cx, cz);
                        int n = journal.count(chunk);
                        // nextActive wraps round: the walk ends when it comes back to an earlier index
                        for (int i = journal.nextActive(chunk, 0), last = -1; i > last; last = i, i = i + 1 >= n ? -1 : journal.nextActive(chunk, i + 1)) {
                            Key k = journal.keyAt(chunk, i);
                            if (k == null) break;
                            long dx = k.x() - circle.x(), dz = k.z() - circle.z();
                            if (dx * dx + dz * dz <= r2) out.add(new int[]{k.x(), k.y(), k.z()});
                        }
                    }
                return out;
            }
            @Override public ClearSweep.Visit visit(UUID id, int x, int y, int z) {
                World world = Bukkit.getWorld(id);
                if (world == null || !world.isChunkLoaded(x >> 4, z >> 4)) return ClearSweep.Visit.DONE;
                Block block = world.getBlockAt(x, y, z);
                Key key = key(block);
                var entry = journal.get(key);
                if (entry == null || entry.released() || !buildFree(block)) return ClearSweep.Visit.DONE;
                String current = block.getBlockData().getAsString();
                if (current.equals(entry.original()) || !entry.accepts(current)) return reconcile(block, s, true) ? ClearSweep.Visit.CHANGED : ClearSweep.Visit.DONE;
                if (busy(key)) return ClearSweep.Visit.WAIT;
                if (block.getType() == Material.SNOW && carriesBlock(block)) return ClearSweep.Visit.DONE;
                return reconcile(block, s, true) ? ClearSweep.Visit.CHANGED : ClearSweep.Visit.WAIT;
            }
        };
    }
    /**
     * A player placed a block (1.28.0, {@code build-mode.under-blocks.enabled}): the plugin's snow (or one ice block)
     * right under it is given up, so it stays under the block through the thaw and the block never ends up floating one
     * block above the ground. Returns the positions given up.
     */
    public int keepUnder(Block placed) {
        int kept = 0;
        Block below = placed;
        for (int i = 0; i <= SnowDepth.MAX_BLOCKS && below.getY() > below.getWorld().getMinHeight(); i++) {
            below = below.getRelative(BlockFace.DOWN);
            Material m = below.getType();
            if (m != Material.SNOW && m != Material.ICE || !owned(below)) break;
            release(below);
            kept++;
            if (m == Material.ICE) break;
        }
        return kept;
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
        // Snow-free building (1.28.0): its own allowance on top of the passes, nothing at all while no circle waits.
        if (touches && !sweep.idle() && plugin.buildAreas() != null) {
            int perTick = plugin.buildAreas().settings().clearPerTick();
            sweep.run(perTick, perTick * 8, sweepCells(s));
        }
        if (touches && !drainMelt(s)) return;
        if (touches) drainRecheck(s);
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
        // The same walk as the snow (1.27.0): before, a record visit took (visits / 2) % records, so ice whose shore
        // went out of reach melted at about one cell per lap. Freezing keeps its columns: one new column per two visits.
        boolean freezing = lays && plugin.season().intensity() > 0 && !thaw;
        frostRounds.run(ChunkRounds.frost(budget, reservedOnly), true, new ChunkRounds.Chunks<>() {
            @Override public boolean loaded(ChunkKey ref) {
                World world = Bukkit.getWorld(ref.world());
                return world != null && world.isChunkLoaded(ref.x(), ref.z());
            }
            @Override public int nextActive(ChunkKey ref, int from) {
                return journal.nextActive(id(ref), from);
            }
            @Override public boolean samples(ChunkKey ref) {
                return freezing && s.includes(Bukkit.getWorld(ref.world()));
            }
        }, new ChunkRounds.Work<>() {
            @Override public boolean record(ChunkKey ref, int index) {
                searchDeferred = false;
                Key k = journal.keyAt(id(ref), index);
                if (k == null) return false;
                Block block = Bukkit.getWorld(ref.world()).getBlockAt(k.x(), k.y(), k.z());
                return block.getType() == Material.ICE && reconcile(block, s, true);
            }
            @Override public boolean landscape(ChunkKey ref, int turn) {
                searchDeferred = false;
                World world = Bukkit.getWorld(ref.world());
                int column = SnowDepth.column(ref.x(), ref.z(), turn / 2);
                int x = (ref.x()<<4) + (column & 15), z = (ref.z()<<4) + (column>>4);
                return freeze(world.getHighestBlockAt(x, z, HeightMap.MOTION_BLOCKING), s);
            }
            @Override public boolean deferred() {
                return searchDeferred;
            }
        });
    }
    /**
     * One column per chunk visit ({@link ChunkRounds}): a record visit reconciles one tracked position, a landscape
     * column lays snow (winter) or grows plants (the rest of the year). Chunks with nothing to restore and nothing to
     * sample cost no column, given-up markers cost no visit (1.27.0).
     */
    private void snowPass(WinterSettings s, boolean lays, boolean touches, boolean thaw, at.vystorm.winter.season.SeasonClock clock) {
        double intensity = plugin.season().intensity();
        boolean snowing = lays && intensity > 0 && !thaw, growing = clock.enabled() && intensity <= 0 && !thaw;
        rounds.run(budget, touches, new ChunkRounds.Chunks<>() {
            @Override public boolean loaded(ChunkKey ref) {
                World world = Bukkit.getWorld(ref.world());
                return world != null && world.isChunkLoaded(ref.x(), ref.z());
            }
            @Override public int nextActive(ChunkKey ref, int from) {
                return journal.nextActive(id(ref), from);
            }
            @Override public boolean samples(ChunkKey ref) {
                return (snowing || growing) && s.includes(Bukkit.getWorld(ref.world()));
            }
        }, new ChunkRounds.Work<>() {
            @Override public boolean record(ChunkKey ref, int index) {
                Key k = journal.keyAt(id(ref), index);
                return k != null && reconcile(Bukkit.getWorld(ref.world()).getBlockAt(k.x(), k.y(), k.z()), s, false);
            }
            @Override public boolean landscape(ChunkKey ref, int turn) {
                World world = Bukkit.getWorld(ref.world());
                int column = SnowDepth.column(ref.x(), ref.z(), turn / 2);
                int x = (ref.x()<<4) + (column & 15), z = (ref.z()<<4) + (column>>4);
                Block top = world.getHighestBlockAt(x, z, HeightMap.MOTION_BLOCKING);
                if (!snowing) return grow(top, s);
                // Still water is the frost pass's work: a coast does not use up the snow's columns (1.24.2).
                if (s.frost() && top.getType() == Material.WATER && ((Levelled)top.getBlockData()).getLevel() == 0) {
                    budget.waterSkip();
                    return false;
                }
                return sample(top, s) || shelterSample(world, x, z, s);
            }
        });
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
            // A snow-free building circle gets its tall plants back as a thaw would (1.28.0).
            if ((winter || holdsSeasonMelt()) && !buildFree(block)) return false;
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
            if (plugin.zones() != null && !plugin.zones().allows(block.getLocation())) {
                // Snow that carries a block stays for the thaw inside a snow-free building circle (1.28.0).
                if (buildFree(block) && carriesBlock(block)) return false;
                return edit(block, entry.original(), false);
            }
            if (hold) return false;
            if (!snowSupportedAt(block, s)) intensity = 0;
            Block root = snowRoot(block);
            int offset = root == null ? SnowDepth.MAX_BLOCKS : block.getY() - root.getY();
            Snow snow = (Snow)block.getBlockData();
            if (root != null && !s.vegetationSnow() && coveredPlant(root)) intensity = 0;
            int target;
            boolean shelteredSnow = windOn(s) && sheltered(block, s);
            if (shelteredSnow) {
                // Wind-blown snow (1.25.0): the rays decide again, so a porch that was closed loses its snow. Sheltered
                // snow is one block at most; anything stacked on it melts.
                if (intensity > 0 && !budget.mayTrace()) return false;
                target = root == block ? shelterTarget(shelterView(block.getWorld(), s), block, s, intensity) : 0;
            } else target = SnowDepth.atHeight(layers(root == null ? block : root, s, intensity), offset);
            if (snow.getLayers() > target) {
                // creep (1.25.0): the sheltered cell downwind takes half of this cell's layers, look at it again soon
                if (shelteredSnow) recheckDownwind(block, s);
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
        if (s.terrain().cover() == SnowCover.Mode.SMOOTH) return smooth(b, s, intensity, s.terrain().ramps()).layers();
        int base = SnowDepth.target(s.layers(), intensity, b.getX(), b.getZ(), s.driftStrength(), edgeFactor(b, s));
        return s.terrain().ramps() ? SnowDepth.withRamp(base, stepDistance(b), intensity) : base;
    }
    // ---- smooth cover (1.27.0) -----------------------------------------------------------------------------------------
    /** Biome shares of 4x4 biome cells, per world; cleared when the settings or the world change or it grows large. */
    private final Map<Long, Double> biomeShares = new HashMap<>();
    private UUID biomeWorld;
    private WinterSettings biomeSettings;
    /** The smooth cover of the column whose snow starts at {@code root} ({@link SnowCover#target}). */
    private SnowCover.Result smooth(Block root, WinterSettings s, double intensity, boolean ramps) {
        World world = root.getWorld();
        if (!world.getUID().equals(biomeWorld) || biomeSettings != s || biomeShares.size() > 65536) {
            biomeShares.clear();
            biomeWorld = world.getUID();
            biomeSettings = s;
        }
        SnowCover.Ground ground = new SnowCover.Ground() {
            @Override public int height(int x, int z) {
                return world.isChunkLoaded(x >> 4, z >> 4) ? groundHeight(world, x, z) : Integer.MIN_VALUE;
            }
            @Override public boolean room(int x, int y, int z) {
                Block cell = world.getBlockAt(x, y, z);
                Material m = cell.getType();
                return m.isAir() || smallPlant(m) || m == Material.SNOW && owned(cell);
            }
            @Override public double biomeShare(int cellX, int cellZ) {
                long key = (long)cellX << 32 ^ (cellZ & 0xFFFFFFFFL);
                Double v = biomeShares.get(key);
                if (v != null) return v;
                int x = cellX * 4 + 2, z = cellZ * 4 + 2;
                if (!world.isChunkLoaded(x >> 4, z >> 4)) return Double.NaN;
                Block at = world.getBlockAt(x, Math.min(world.getMaxHeight() - 1, groundHeight(world, x, z) + 1), z);
                String biome = BiomeKeys.key(at.getBiome());
                double share = s.excludedBiomes().contains(biome) ? 0 : s.terrain().biomes().share(biome, at.getTemperature(), at.getHumidity());
                biomeShares.put(key, share);
                return share;
            }
        };
        return SnowCover.target(ground, root.getX(), root.getZ(), root.getY(),
                new SnowCover.Input(s.layers(), intensity, s.driftStrength(), edgeFactor(root, s), ramps, SnowCover.salt(world.getSeed())));
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
    public static boolean smallPlant(Material material) {
        return Shelter.SMALL_PLANTS.contains(material.name());
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
    // ---- wind-blown snow (1.25.0) -------------------------------------------------------------------------------------
    private ShelterView shelterView;
    private UUID shelterWorld;
    private boolean shelterLeaves, shelterPlants;
    private long raysTraced;
    /** Rays of wind-blown snow traced since the start and the block reads they and the filter needed. */
    public long raysTraced() {
        return raysTraced;
    }
    public long shelterReads() {
        return shelterView == null ? 0 : shelterReadsBefore + shelterView.reads();
    }
    private long shelterReadsBefore;
    boolean windOn(WinterSettings s) {
        return plugin.switches().on(at.vystorm.winter.WinterSwitches.Feature.WIND_BLOWN);
    }
    /** The world as the wind sees it, caches cleared: one evaluation per call. */
    private ShelterView shelterView(World world, WinterSettings s) {
        boolean leaves = s.windBlown().leavesBlock(), plants = s.vegetationSnow();
        if (shelterView == null || !world.getUID().equals(shelterWorld) || leaves != shelterLeaves || plants != shelterPlants) {
            if (shelterView != null) shelterReadsBefore += shelterView.reads();
            shelterView = new ShelterView(new ShelterView.Source() {
                public boolean chunkLoaded(int chunkX, int chunkZ) { return world.isChunkLoaded(chunkX, chunkZ); }
                public int heightmap(int x, int z) { return world.getHighestBlockYAt(x, z, HeightMap.MOTION_BLOCKING); }
                public String material(int x, int y, int z) { return world.getBlockAt(x, y, z).getType().name(); }
                public boolean own(int x, int y, int z) { return owned(world.getBlockAt(x, y, z)); }
                public boolean takesSnow(int x, int y, int z) { return groundSupportsSnow(world.getBlockAt(x, y, z), plugin.settings()); }
                public int minY() { return world.getMinHeight(); }
                public int layers(int x, int y, int z) { return world.getBlockAt(x, y, z).getBlockData() instanceof Snow snow ? snow.getLayers() : 0; }
            }, leaves, plants);
            shelterWorld = world.getUID();
            shelterLeaves = leaves;
            shelterPlants = plants;
        }
        shelterView.reset();
        return shelterView;
    }
    /**
     * Wind-blown snow: one more layer on a sheltered block top of this column (under a roof edge or canopy) that the
     * wind reaches. Same edit, form event, records, budgets, spots, build mode, region flag and biomes as {@link #sample};
     * own ray budget per tick. False when nothing changed.
     */
    private boolean shelterSample(World world, int x, int z, WinterSettings s) {
        if (!windOn(s) || !budget.mayTrace()) return false;
        WinterSettings.WindBlown wb = s.windBlown();
        ShelterView view = shelterView(world, s);
        if (!Shelter.candidate(view, x, z, wb.wind())) return false;
        double intensity = plugin.season().intensity();
        for (int y : Shelter.surfaces(view, x, z, wb.maxHeight())) {
            if (!budget.mayTrace()) return false;
            Block cell = world.getBlockAt(x, y, z);
            if (!allowed(cell.getLocation()) || s.excludedBiomes().contains(BiomeKeys.key(cell.getBiome()))) continue;
            if (cell.getType() == Material.SNOW && (!owned(cell) || !s.vegetationSnow() && coveredPlant(cell))) continue;
            int current = cell.getType() == Material.SNOW ? ((Snow)cell.getBlockData()).getLayers() : 0;
            if (current >= Math.min(8, wb.maxLayers())) continue;
            int target = shelterTarget(view, cell, s, intensity);
            if (current >= target) continue;
            Snow data = (Snow)Material.SNOW.createBlockData();
            data.setLayers(smallPlant(cell.getType()) ? SnowDepth.FIRST_LAYERS : current + 1);
            return edit(cell, data.getAsString(), true);
        }
        return false;
    }
    /** The snow lies below the column's highest blocking block (a roof or canopy over it), not on the open top. */
    private boolean sheltered(Block snow, WinterSettings s) {
        World world = snow.getWorld();
        if (snow.getY() > world.getHighestBlockYAt(snow.getX(), snow.getZ(), HeightMap.MOTION_BLOCKING)) return false;
        return snow.getY() < shelterView(world, s).height(snow.getX(), snow.getZ());
    }
    // Early re-check after a block was placed near sheltered snow: the positions wait here for the next ticks.
    private final ArrayDeque<Key> recheck = new ArrayDeque<>();
    private final Set<Key> recheckQueued = new HashSet<>();
    /** At most this many positions wait for an early re-check; the normal reconcile still reaches the rest. */
    static final int RECHECK_LIMIT = 4096;
    /**
     * A player placed a block: tracked snow whose rays it may cross ({@link Shelter#mayShade}) is checked again within
     * the next ticks instead of at the next visit of the pass. Only record lookups here, no block reads.
     */
    public void placed(Block placed) {
        WinterSettings s = plugin.settings();
        if (s == null || !windOn(s)) return;
        World world = placed.getWorld();
        WindRay.Wind wind = s.windBlown().wind();
        Shelter.Shade shade = Shelter.Shade.of(wind);
        int reach = WindRay.horizontalReach(wind) + 2, px = placed.getX(), py = placed.getY(), pz = placed.getZ();
        Map<TrackedBlocks.ChunkId, Boolean> records = new HashMap<>();
        UUID id = world.getUID();
        for (int dz = -reach; dz <= reach; dz++) for (int dx = -reach; dx <= reach; dx++) {
            int x = px + dx, z = pz + dz;
            TrackedBlocks.ChunkId chunk = new TrackedBlocks.ChunkId(id, x >> 4, z >> 4);
            if (!records.computeIfAbsent(chunk, c -> journal.count(c) > 0)) continue;
            for (int y = py; y >= py - wind.maxHeight() && y > world.getMinHeight(); y--) {
                if (!shade.test(px, py, pz, x, y, z)) continue;
                Key key = new Key(id, x, y, z);
                var entry = journal.get(key);
                if (entry == null || entry.released() || recheckQueued.size() >= RECHECK_LIMIT) continue;
                if (recheckQueued.add(key)) recheck.addLast(key);
            }
        }
    }
    /**
     * Sheltered snow shrinks: the cell one step downwind may hold creep that depends on it ({@link Shelter#upwindLayers}),
     * so it waits for an early re-check too; a closed porch then loses its snow cell after cell without waiting for the
     * pass. Only a record lookup, no block read.
     */
    private void recheckDownwind(Block block, WinterSettings s) {
        int[] up = Shelter.upwindStep(s.windBlown().wind());
        Key key = new Key(block.getWorld().getUID(), block.getX() - up[0], block.getY(), block.getZ() - up[1]);
        var entry = journal.get(key);
        if (entry == null || entry.released() || recheckQueued.size() >= RECHECK_LIMIT) return;
        if (recheckQueued.add(key)) recheck.addLast(key);
    }
    /** Positions waiting for an early re-check (tests, status). */
    public int rechecksWaiting() {
        return recheck.size();
    }
    /** Works through the early re-checks within the change and ray budgets; a position that still changes stays queued. */
    private void drainRecheck(WinterSettings s) {
        int visits = 0;
        while (!recheck.isEmpty() && budget.mayChange() && budget.mayTrace() && visits++ < 256) {
            Key key = recheck.pollFirst();
            recheckQueued.remove(key);
            World world = Bukkit.getWorld(key.world());
            if (world == null || !world.isChunkLoaded(key.x() >> 4, key.z() >> 4)) continue;
            Block block = world.getBlockAt(key.x(), key.y(), key.z());
            if (block.getType() != Material.SNOW) continue;
            if (reconcile(block, s, false)) {
                budget.changed();
                // one layer per visit: look again once this change is in the world (next tick)
                if (recheckQueued.add(key)) recheck.addLast(key);
            }
        }
    }
    /**
     * Layers a sheltered block top should carry now ({@link Shelter#target}): the cone's free rays scale the normal depth
     * capped by max-layers and headroom, one layer less per cell under the roof, half of the sheltered neighbour's snow
     * upwind (no ramps under roofs).
     */
    private int shelterTarget(ShelterView view, Block cell, WinterSettings s, double intensity) {
        if (intensity <= 0) return 0;
        int depth = s.terrain().cover() == SnowCover.Mode.SMOOTH ? smooth(cell, s, intensity, false).base()
                : SnowDepth.target(s.layers(), intensity, cell.getX(), cell.getZ(), s.driftStrength(), edgeFactor(cell, s));
        if (depth <= 0) return 0;
        budget.traced(WindRay.CONE_RAYS);
        raysTraced += WindRay.CONE_RAYS;
        return Shelter.target(view, cell.getX(), cell.getY(), cell.getZ(), s.windBlown().wind(), depth, s.windBlown().maxLayers());
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
        if (entry == null || op.release || op.dropped) return;
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
