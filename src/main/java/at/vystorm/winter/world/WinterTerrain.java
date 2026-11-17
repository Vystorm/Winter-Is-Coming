package at.vystorm.winter.world;

import at.vystorm.winter.WinterPlugin;
import at.vystorm.winter.WinterSettings;
import at.vystorm.winter.storage.BlockJournal;
import at.vystorm.winter.storage.BlockJournal.Key;
import java.io.IOException;
import java.util.*;
import org.bukkit.*;
import org.bukkit.block.*;
import org.bukkit.block.data.Levelled;
import org.bukkit.block.data.type.Snow;
import org.bukkit.event.block.BlockFormEvent;

/** All Bukkit access runs on the server thread; periodic durable intents use one worker. No forced chunk loads. */
public final class WinterTerrain implements AutoCloseable {
    private record ChunkKey(UUID world, int x, int z) { }
    private final WinterPlugin plugin;
    private final BlockJournal journal;
    private final Vystorm.vystorm_core._advancedMethods.areaHandler.AreaInterface area;
    private final ArrayDeque<ChunkKey> chunks = new ArrayDeque<>();
    private final Set<ChunkKey> queued = new HashSet<>();
    private final Map<ChunkKey, List<Key>> tracked = new HashMap<>();
    private final Map<ChunkKey, Integer> offsets = new HashMap<>();
    private boolean applying;
    private boolean frostSearched;
    private final java.util.concurrent.ExecutorService writer = java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "Winter-journal");
        thread.setDaemon(true);
        return thread;
    });
    /** A change waiting for its durable journal line; applied to the world only afterwards. */
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
    private java.util.concurrent.CompletableFuture<List<BlockJournal.Entry>> flight;
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
        if (batch.isEmpty() || flight != null) return;
        List<Op> ops = List.copyOf(batch);
        batch.clear();
        inFlight = ops;
        List<BlockJournal.Request> requests = new ArrayList<>(ops.size());
        for (Op op : ops) requests.add(new BlockJournal.Request(op.key, op.before, op.after, op.release));
        flight = java.util.concurrent.CompletableFuture.supplyAsync(() -> {
            try {
                return journal.apply(requests);
            } catch (IOException failure) {
                throw new java.util.concurrent.CompletionException(failure);
            }
        }, writer);
    }
    /** @param area Core's area manager, {@code api.area()}; the region service is looked up per call as before */
    public WinterTerrain(WinterPlugin plugin, BlockJournal journal, Vystorm.vystorm_core._advancedMethods.areaHandler.AreaInterface area) {
        this.plugin = plugin;
        this.area = area;
        this.journal = journal;
        for (var entry : journal.entries()) track(entry.key());
    }
    private static ChunkKey chunk(Key key) {
        return new ChunkKey(key.world(), key.x()>>4, key.z()>>4);
    }
    private void track(Key key) {
        var list = tracked.computeIfAbsent(chunk(key), unused -> new ArrayList<>());
        if (!list.contains(key)) list.add(key);
    }
    public void load(Chunk c) {
        ChunkKey key = new ChunkKey(c.getWorld().getUID(), c.getX(), c.getZ());
        if (queued.add(key)) chunks.add(key);
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
        } catch (IOException failure) {
            plugin.storageFailure(failure);
            throw new IllegalStateException(failure);
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
        } catch (IOException failure) {
            plugin.storageFailure(failure);
            return false;
        }
    }
    /** Changes earned so far. Runs every tick; all changes of a tick share one journal write. */
    private double allowance;
    public void cycle() {
        if (!drain()) return;
        try {
            cycleBody();
        } finally {
            flush();
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
        for (ChunkKey key : tracked.keySet()) {
            if (!key.world().equals(world) || Math.abs(key.x() - cx) > r || Math.abs(key.z() - cz) > r || meltCursor.containsKey(key)) continue;
            melt.addLast(key);
            meltCursor.put(key, 0);
        }
    }
    /** Works through the melt queue within the change allowance; returns false when the allowance ran out. */
    private boolean drainMelt(WinterSettings s) {
        int checks = 0;
        while (!melt.isEmpty() && allowance >= 1 && checks < 256) {
            ChunkKey ref = melt.peekFirst();
            World world = Bukkit.getWorld(ref.world());
            List<Key> records = tracked.getOrDefault(ref, List.of());
            int at = meltCursor.getOrDefault(ref, 0);
            if (world == null || !world.isChunkLoaded(ref.x(), ref.z()) || at >= records.size()) {
                melt.removeFirst();
                meltCursor.remove(ref);
                continue;
            }
            meltCursor.put(ref, at + 1);
            checks++;
            Key k = records.get(at);
            Block block = world.getBlockAt(k.x(), k.y(), k.z());
            if (!allowed(block.getLocation()) && reconcile(block, s)) allowance--;
        }
        return allowance >= 1;
    }
    private void cycleBody() {
        var s = plugin.settings();
        frostSearched = false;
        // Stop after spending the shoreline-search budget. Advancing other water columns
        // without evaluating them can phase-lock the round-robin cursor across chunks,
        // permanently skipping near-shore cells while repeatedly searching farther water.
        var clock = plugin.clock();
        cycleNow = null;
        int boost = !clock.enabled() ? 1 : clock.mode() == at.vystorm.winter.season.SeasonClock.Mode.WEEKLY ? 8 : clock.mode() == at.vystorm.winter.season.SeasonClock.Mode.MONTHLY ? 4 : 1;
        int maxColumns = Math.min(8192, s.columns() * boost), maxChanges = Math.min(512, s.changes() * boost);
        allowance = Math.min(allowance + (double)maxChanges / s.period(), Math.max(1, maxChanges));
        int columnsThisTick = Math.max(1, maxColumns / s.period());
        if (!drainMelt(s)) return;
        for (int attempt = 0; attempt < columnsThisTick && !chunks.isEmpty() && allowance >= 1 && !frostSearched; attempt++) {
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
            List<Key> records = tracked.getOrDefault(ref, List.of());
            if (offset % 2 == 0 && !records.isEmpty()) {
                Key k = records.get((offset / 2) % records.size());
                if (reconcile(world.getBlockAt(k.x(), k.y(), k.z()), s)) allowance--;
            } else if (s.includes(world) && plugin.season().intensity() > 0 && !plugin.state().data().thaw) {
                int column = (offset / 2) % 256;
                int x = (ref.x()<<4) + (column & 15), z = (ref.z()<<4) + (column>>4);
                Block top = world.getHighestBlockAt(x, z, HeightMap.MOTION_BLOCKING);
                if (sample(top, s)) allowance--;
            } else if (s.includes(world) && clock.enabled() && plugin.season().intensity() <= 0 && !plugin.state().data().thaw) {
                int column = (offset / 2) % 256;
                int x = (ref.x()<<4) + (column & 15), z = (ref.z()<<4) + (column>>4);
                if (grow(world.getHighestBlockAt(x, z, HeightMap.MOTION_BLOCKING), s)) allowance--;
            }
        }
    }
    private boolean reconcile(Block block, WinterSettings s) {
        var entry = journal.get(key(block));
        if (entry == null || entry.released()) return false;
        String current = block.getBlockData().getAsString();
        if (!entry.accepts(current)) {
            queueRelease(key(block));
            return false;
        }
        if (current.equals(entry.original())) return false;
        if (current.equals("minecraft:air") && SeasonPlants.tallPlantData(entry.original())) {
            boolean winter = s.includes(block.getWorld()) && !plugin.state().data().thaw && plugin.season().intensity() > 0 && s.terrain().removeTallPlants();
            if (winter) return false;
            if (entry.original().contains("half=upper") && !SeasonPlants.tallPlant(block.getRelative(BlockFace.DOWN).getType())) return false;
            return edit(block, entry.original(), false);
        }
        double intensity = s.includes(block.getWorld()) && !plugin.state().data().thaw ? plugin.season().intensity() : 0;
        // Restore our own temporary material even if an area became protected after placement.
        if (!allowed(block.getLocation()) || s.excludedBiomes().contains(BiomeKeys.key(block.getBiome()))
                || block.getType() == Material.ICE && !s.frost()) intensity = 0;
        if (block.getType() == Material.SNOW) {
            // Keep the lower full block until its owned upper block has melted away.
            if (block.getY() + 1 < block.getWorld().getMaxHeight()) {
                Block above = block.getRelative(BlockFace.UP);
                if (above.getType() == Material.SNOW && owned(above)) return false;
            }
            // Outside season spots or near a builder: restore in one step instead of one layer per visit (1.20.1).
            if (plugin.zones() != null && !plugin.zones().allows(block.getLocation())) return edit(block, entry.original(), false);
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
            if (intensity == 0) return edit(block, entry.original(), false);
            if (frostSearched) return false;
            int distance = distance(block, s);
            // Missing/temporarily unloaded shore is not evidence to melt existing ice.
            if (distance > radius(block, s, intensity)) return edit(block, entry.original(), false);
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
        if (top.getType() == Material.WATER && s.frost() && ((Levelled)top.getBlockData()).getLevel() == 0) {
            int radius = radius(top, s, intensity);
            if (radius < 1 || frostSearched) return false;
            int distance = distance(top, s);
            if (distance > 0 && distance <= radius) return edit(top, Material.ICE.createBlockData().getAsString(), true);
            return false;
        }
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
                if (!s.vegetationSnow() || offset != 0 || total < 4) return false;
            } else if (!snow.getType().isAir()) return false;
            if (current < target) {
                if (!snowSupportedAt(snow, s)) return false;
                Snow data = (Snow)Material.SNOW.createBlockData();
                data.setLayers(smallPlant(snow.getType()) ? 4 : current + 1);
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
    private int distance(Block start, WinterSettings s) {
        frostSearched = true;
        int max = BiomeKeys.key(start.getBiome()).contains("ocean") ? s.oceanRadius() : s.inlandRadius();
        World world = start.getWorld();
        int y = start.getY();
        return FrostBudget.distance(start.getX(), start.getZ(), max, s.searchBudget(), (x, z) -> {
            if (!world.isChunkLoaded(x>>4, z>>4)) return FrostBudget.Cell.UNKNOWN;
            Block b = world.getBlockAt(x, y, z);
            if (b.getType() == Material.WATER && ((Levelled)b.getBlockData()).getLevel() == 0 || b.getType() == Material.ICE && owned(b)) return FrostBudget.Cell.WATER;
            if (s.shores().contains(b.getType()) && y - 2 >= world.getMinHeight()
                    && s.shores().contains(world.getBlockAt(x, y - 1, z).getType()) && s.shores().contains(world.getBlockAt(x, y - 2, z).getType())) return FrostBudget.Cell.SHORE;
            return FrostBudget.Cell.BLOCKED;
        });
    }
    private boolean edit(Block b, String next, boolean formEvent) {
        var key = key(b);
        var previous = journal.get(key);
        if (previous == null && journal.size() >= plugin.settings().maxTracked()) return false;
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
    /** Apply only completed, durable intents; recheck chunks, epoch, protection and block data for each. */
    private boolean drain() {
        if (flight == null) return true;
        if (!flight.isDone()) return false;
        var completed = flight;
        List<Op> ops = inFlight;
        flight = null;
        inFlight = List.of();
        List<BlockJournal.Entry> entries;
        try {
            entries = completed.join();
        } catch (Exception failure) {
            plugin.storageFailure(failure);
            return false;
        }
        try {
            for (int i = 0; i < ops.size(); i++) apply(ops.get(i), entries.get(i));
        } catch (Exception failure) {
            plugin.storageFailure(failure);
            return false;
        }
        return true;
    }
    private void apply(Op op, BlockJournal.Entry entry) {
        if (entry == null || op.release) return;
        Key key = op.key;
        String before = op.before, after = op.after;
        track(key);
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
        if (op.form && (!plugin.controls(world) || !allowed(block.getLocation()))) return;
        boolean snowForm = op.form && after.startsWith("minecraft:snow[");
        if (snowForm && (!snowSupportedAt(block, plugin.settings()) || smallPlant(block.getType()) && !plugin.settings().vegetationSnow())) return;
        // Protection can change while disk IO runs. Re-fire immediately before applying.
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
    }
    @Override public void close() {
        writer.shutdown();
        try {
            if (!writer.awaitTermination(10, java.util.concurrent.TimeUnit.SECONDS)) writer.shutdownNow();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            writer.shutdownNow();
        }
        // An un-applied durable intent remains in the journal for reconciliation on restart.
    }
}
