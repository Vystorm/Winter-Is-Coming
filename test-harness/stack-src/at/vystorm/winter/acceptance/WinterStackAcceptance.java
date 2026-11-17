package at.vystorm.winter.acceptance;

import Vystorm.vystorm_core.region.Region;
import Vystorm.vystorm_core.region.RegionService;
import Vystorm.vystorm_core.region.RegionShape;
import at.vystorm.winter.WinterPlugin;
import at.vystorm.winter.WinterSettings;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import org.bukkit.Bukkit;
import org.bukkit.GameRule;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.Biome;
import org.bukkit.block.data.type.Leaves;
import org.bukkit.block.data.type.Snow;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Item;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockFormEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.plugin.java.JavaPlugin;

/** Restart-based integration check for stacked, owned snow in an isolated server. */
public final class WinterStackAcceptance extends JavaPlugin implements Listener {
    private static final int MAX_TICKS = 24_000;
    private static final int GROUND_X = 8, GROUND_Z = 8;
    private static final int LEAF_X = 6, LEAF_Z = 6;
    private static final int REPLACE_X = 5, REPLACE_Z = 5;
    private static final int NORMAL_X = 9, NORMAL_Z = 9;

    private WinterPlugin winter;
    private World world;
    private Path dataDir;
    private Path phaseMarker;
    private int phase;
    private int phaseTwoStep;
    private int ticks;
    private boolean cycling;
    private boolean forcedChunkLoad;
    private int cancelledForms;
    private final List<String> checks = new ArrayList<>();

    @Override public void onEnable() {
        if (!Boolean.getBoolean("winter.stack.acceptance")) {
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        getServer().getScheduler().runTaskLater(this, () -> {
            try {
                start();
            } catch (Throwable failure) {
                fail(failure);
            }
        }, 40L);
    }

    private void start() throws Exception {
        winter = (WinterPlugin) Bukkit.getPluginManager().getPlugin("WinterIsComing");
        world = Bukkit.getWorld("world");
        if (winter == null || world == null) throw new IllegalStateException("Missing isolated world or WinterIsComing");
        dataDir = getDataFolder().toPath();
        Files.createDirectories(dataDir);
        phaseMarker = dataDir.resolve("phase1-complete");
        phase = Files.exists(phaseMarker) ? 2 : 1;

        String originalConfig = winter.getConfig().saveToString();
        validateSettingsCopies(originalConfig);
        check(originalConfig.equals(winter.getConfig().saveToString()), "config validation mutated WinterIsComing config");
        configureWinter();
        configureWorld();
        registerRegions();
        check(winter.settings().layers() == 12 && winter.settings().treeSnow(),
                "acceptance snow depth or leaf-cover config was not applied");
        check(winter.terrain().allowed(new Location(world, 8, 71, 8)), "fixture region did not allow winter");
        check(!winter.terrain().allowed(new Location(world, 3, 71, 3)), "protected point did not deny winter");
        check(!winter.terrain().allowed(new Location(world, 20, 71, 20)), "outside region did not deny winter");
        getServer().getPluginManager().registerEvents(this, this);
        world.getChunkAt(0, 0).addPluginChunkTicket(this);
        winter.terrain().load(world.getChunkAt(0, 0));

        if (phase == 1) prepareFixture();
        else beginRestartPhase();

        getLogger().info("WINTER_STACK_READY phase=" + phase + " seed=" + world.getSeed());
        getServer().getScheduler().runTaskTimer(this, this::tick, 1L, 1L);
    }

    private void configureWinter() {
        var config = winter.getConfig();
        config.set("worlds", List.of("world"));
        config.set("snow.max-layers", 12);
        config.set("snow.drift-strength", 0.0);
        config.set("snow.transition-blocks", 0);
        config.set("snow.cover-leaves", true);
        config.set("snow.cover-small-plants", true);
        config.set("performance.period-ticks", 1200);
        config.set("performance.columns-per-cycle", 256);
        config.set("performance.changes-per-cycle", 32);
        config.set("weather.enabled", false);
        config.set("spawns.enabled", false);
        config.set("holidays.fireworks", false);
        winter.saveConfig();
        command("reload");
    }

    private void applySnowSettings(int maxLayers, boolean coverLeaves) {
        var config = winter.getConfig();
        config.set("snow.max-layers", maxLayers);
        config.set("snow.cover-leaves", coverLeaves);
        config.set("snow.cover-small-plants", coverLeaves);
        winter.saveConfig();
        command("reload");
        check(winter.settings().layers() == maxLayers && winter.settings().treeSnow() == coverLeaves,
                "snow settings reload did not apply max-layers=" + maxLayers + ", cover-leaves=" + coverLeaves);
    }

    private void configureWorld() {
        world.setGameRule(GameRule.RANDOM_TICK_SPEED, 0);
        world.setGameRule(GameRule.DO_DAYLIGHT_CYCLE, false);
        world.setGameRule(GameRule.DO_WEATHER_CYCLE, false);
        world.setGameRule(GameRule.DO_MOB_SPAWNING, false);
        world.setTime(6000);
        world.setStorm(false);
        world.setThundering(false);
        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "tick rate 80");
    }

    private void validateSettingsCopies(String source) throws InvalidConfigurationException {
        for (int layers : new int[] {1, 8, 12, 64}) {
            YamlConfiguration copy = configCopy(source);
            copy.set("snow.max-layers", layers);
            check(WinterSettings.read(copy).layers() == layers, "settings rejected valid snow depth " + layers);
        }
        for (int layers : new int[] {0, 65}) {
            YamlConfiguration copy = configCopy(source);
            copy.set("snow.max-layers", layers);
            boolean rejected = false;
            try {
                WinterSettings.read(copy);
            } catch (IllegalArgumentException expected) {
                rejected = true;
            }
            check(rejected, "settings accepted invalid snow depth " + layers);
        }
        YamlConfiguration oldConfig = configCopy(source);
        oldConfig.set("snow.cover-leaves", null);
        check(WinterSettings.read(oldConfig).treeSnow(), "missing leaf-cover setting did not default true");
        YamlConfiguration noLeafSnow = configCopy(source);
        noLeafSnow.set("snow.cover-leaves", false);
        check(!WinterSettings.read(noLeafSnow).treeSnow(), "explicit false leaf-cover setting was ignored");
        checks.add("settings-depth-range-and-backward-compatible-leaf-cover");
    }

    private static YamlConfiguration configCopy(String source) throws InvalidConfigurationException {
        YamlConfiguration copy = new YamlConfiguration();
        copy.loadFromString(source);
        return copy;
    }

    private void registerRegions() {
        var service = RegionService.get();
        service.register(this, Region.of("winter-stack", "outside",
                new RegionShape.Box(world.getUID(), -30_000_000, -64, -30_000_000, 30_000_000, 320, 30_000_000))
                .withPriority(0).withFlags(Map.of("winter", false)));
        service.register(this, Region.of("winter-stack", "fixture",
                new RegionShape.Box(world.getUID(), 0, 64, 0, 15, 80, 15))
                .withPriority(10).withFlags(Map.of("winter", true)));
        service.register(this, Region.of("winter-stack", "protected-point",
                new RegionShape.Box(world.getUID(), 3, 64, 3, 3, 80, 3))
                .withPriority(20).withFlags(Map.of("winter", false)));
    }

    private void prepareFixture() throws Exception {
        for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++) {
            world.getBlockAt(x, 69, z).setType(Material.STONE, false);
            world.getBlockAt(x, 70, z).setType(Material.DIRT_PATH, false);
            for (int y = 71; y <= 80; y++) world.getBlockAt(x, y, z).setType(Material.AIR, false);
            world.setBiome(x, 70, z, Biome.PLAINS);
        }
        for (int[] point : new int[][] {{1, 1}, {2, 2}, {3, 3}, {5, 5}, {8, 8}, {9, 9}})
            world.getBlockAt(point[0], 70, point[1]).setType(Material.STONE, false);
        Leaves canopy = (Leaves) Material.OAK_LEAVES.createBlockData();
        canopy.setPersistent(true);
        world.getBlockAt(LEAF_X, 70, LEAF_Z).setBlockData(canopy, false);
        world.getBlockAt(3, 71, 3).setBlockData(canopy, false);

        for(int z=8;z<=10;z++) {
            world.getBlockAt(11,70,z).setType(Material.GRASS_BLOCK,false);
            world.getBlockAt(11,71,z).setType(z==9?Material.FERN:Material.SHORT_GRASS,false);
        }
        // Cancelled formation over vegetation must leave the original plant intact.
        world.getBlockAt(2,70,2).setType(Material.GRASS_BLOCK,false);
        world.getBlockAt(2,71,2).setType(Material.FERN,false);
        Snow naturalSnow = (Snow) Material.SNOW.createBlockData();
        naturalSnow.setLayers(5);
        world.getBlockAt(1, 71, 1).setBlockData(naturalSnow, false);
        check(world.getBlockAt(1, 70, 1).getType() == Material.STONE, "natural snow lacks its stone support");
        checks.add("natural-five-layer-snow-seeded");
        command("setdate 2026-12-24");
        check(winter.date().toString().equals("2026-12-24"), "peak date was not set");
        checks.add("peak-date-set");
    }

    private void beginRestartPhase() throws Exception {
        check(Files.exists(phaseMarker), "phase-one marker missing");
        check(winter.date().toString().equals("2026-12-24"), "manual peak date did not persist");
        check(totalLayers(GROUND_X, GROUND_Z) == 12, "ground snow stack did not persist at 12 layers");
        check(totalLayers(LEAF_X, LEAF_Z) == 12, "leaf snow stack did not persist at 12 layers");
        check(totalLayers(REPLACE_X, REPLACE_Z) == 12, "replacement snow stack did not persist at 12 layers");
        check(totalLayers(NORMAL_X, NORMAL_Z) == 12, "normal thaw snow stack did not persist at 12 layers");
        check(fullStack(11,8) && fullStack(11,9) && fullStack(11,10),"covered plants did not persist across restart");
        checks.add("covered-grass-and-fern-persisted-after-restart");
        check(ownedStack(GROUND_X, GROUND_Z), "ground stack ownership did not persist");
        check(ownedStack(LEAF_X, LEAF_Z), "leaf stack ownership did not persist");
        checks.add("ground-and-leaf-stacks-and-ownership-restored-after-restart");
        applySnowSettings(4, false);
        phaseTwoStep = 1;
        ticks = 0;
        checks.add("reduced-max-depth-and-disabled-leaf-cover");
    }

    private void tick() {
        try {
            if (phase == 1) tickPeak();
            else tickThaw();
        } catch (Throwable failure) {
            fail(failure);
        }
    }

    private void cycle() {
        cycling = true;
        try {
            for (int i = 0; i < 8; i++) winter.terrain().cycle();
        } finally {
            cycling = false;
        }
    }

    private void tickPeak() throws Exception {
        cycle();
        ticks++;
        boolean groundReady = fullStack(GROUND_X, GROUND_Z);
        boolean leafReady = fullStack(LEAF_X, LEAF_Z);
        boolean replacementReady = fullStack(REPLACE_X, REPLACE_Z);
        boolean normalReady = fullStack(NORMAL_X, NORMAL_Z);
        if (ticks >= MAX_TICKS) throw new IllegalStateException("Timed out waiting for all four 12-layer stacks");
        if (!groundReady || !leafReady || !replacementReady || !normalReady || !fullStack(11,8) || !fullStack(11,9) || !fullStack(11,10)) return;

        check(!forcedChunkLoad, "terrain cycle force-loaded a chunk");
        check(winter.terrain().owned(world.getBlockAt(1, 71, 1)) == false, "natural snow became journal-owned");
        check(snowLayers(1, 71, 1) == 5, "natural snow changed");
        check(world.getBlockAt(2, 71, 2).getType() == Material.FERN, "cancelled block form was applied");
        check(cancelledForms > 0, "test block form event was not observed");
        check(world.getBlockAt(3, 72, 3).getType() == Material.AIR, "protected point received snow");
        check(world.getBlockAt(3, 71, 3).getType() == Material.OAK_LEAVES
                        && ((Leaves) world.getBlockAt(3, 71, 3).getBlockData()).isPersistent(),
                "protected canopy base changed");
        checks.addAll(List.of("short-grass-and-fern-covered-by-owned-stacks", "cancelled-plant-coverage-preserves-fern", "ground-stack-12-owned", "persistent-leaf-stack-12-owned", "normal-thaw-stack-12-owned", "natural-snow-untouched",
                "form-event-cancellation-respected", "protected-region-respected", "no-forced-chunk-load"));
        writeReport("result-stage1", true, null);
        Files.writeString(phaseMarker, "peak stacks captured\n");
        world.save();
        getLogger().info("WINTER_STACK_PHASE1_PASSED " + countsJson());
        getServer().getScheduler().cancelTasks(this);
        Bukkit.shutdown();
    }

    private void tickThaw() throws Exception {
        cycle();
        ticks++;
        if (phaseTwoStep == 1) {
            if (ticks >= MAX_TICKS) throw new IllegalStateException("Timed out waiting for reduced ground stacks and leaf removal");
            if (!reducedStack(GROUND_X, GROUND_Z) || !reducedStack(REPLACE_X, REPLACE_Z)
                    || !reducedStack(NORMAL_X, NORMAL_Z) || hasOwnedLeafSnow()
                    || world.getBlockAt(11,71,8).getType()!=Material.SHORT_GRASS
                    || world.getBlockAt(11,71,9).getType()!=Material.FERN
                    || world.getBlockAt(11,71,10).getType()!=Material.SHORT_GRASS) return;
            checks.add("disabling-plant-cover-restores-original-plants-at-peak");
            check(winter.date().toString().equals("2026-12-24") && !winter.state().data().thaw,
                    "reduced-stack check did not remain at peak");
            check(snowLayers(1, 71, 1) == 5 && !winter.terrain().owned(world.getBlockAt(1, 71, 1)),
                    "native snow changed during depth reduction");
            check(world.getBlockAt(LEAF_X, 70, LEAF_Z).getType() == Material.OAK_LEAVES,
                    "leaf support changed when leaf cover was disabled");
            checks.add("depth-reduction-and-leaf-disable-applied-while-at-peak");
            applySnowSettings(12, true);
            phaseTwoStep = 2;
            ticks = 0;
            checks.add("restored-max-depth-and-enabled-leaf-cover");
            return;
        }
        if (phaseTwoStep == 2) {
            if (ticks >= MAX_TICKS) throw new IllegalStateException("Timed out waiting for all four stacks to regrow");
            if (!fullStack(GROUND_X, GROUND_Z) || !fullStack(LEAF_X, LEAF_Z)
                    || !fullStack(REPLACE_X, REPLACE_Z) || !fullStack(NORMAL_X, NORMAL_Z) || !fullStack(11,8) || !fullStack(11,9) || !fullStack(11,10)) return;
            check(snowLayers(1, 71, 1) == 5 && !winter.terrain().owned(world.getBlockAt(1, 71, 1)),
                    "native snow changed during stack regrowth");
            check(world.getBlockAt(LEAF_X, 70, LEAF_Z).getType() == Material.OAK_LEAVES,
                    "leaf support changed during stack regrowth");
            checks.add("ground-leaf-replacement-and-normal-stacks-regrew-to-twelve-layers");
            clearStack(GROUND_X, GROUND_Z, "ground");
            clearStack(LEAF_X, LEAF_Z, "leaf");
            checks.add("clear-removes-entire-ground-and-leaf-stack-immediately");
            check(snowLayers(REPLACE_X, 71, REPLACE_Z) > 0, "replacement target was not snow before player replacement");
            world.getBlockAt(REPLACE_X, 71, REPLACE_Z).setType(Material.DIAMOND_BLOCK, false);
            world.getBlockAt(11,71,10).setType(Material.DIAMOND_BLOCK,false);
            check(ownedSnowCount() > 0, "no owned snow remains to exercise thaw reconciliation");
            command("thaw");
            phaseTwoStep = 3;
            ticks = 0;
            checks.add("foreign-replacement-and-thaw-started");
            return;
        }
        if (ticks >= MAX_TICKS) throw new IllegalStateException("Timed out waiting for owned snow to thaw");
        if (ownedSnowCount() != 0) return;

        check(!forcedChunkLoad, "terrain cycle force-loaded a chunk during thaw");
        check(world.getBlockAt(REPLACE_X, 71, REPLACE_Z).getType() == Material.DIAMOND_BLOCK,
                "foreign replacement block was not preserved");
        check(world.getBlockAt(REPLACE_X, 72, REPLACE_Z).getType() == Material.AIR,
                "snow above the foreign replacement was not cleared");
        check(world.getBlockAt(1, 71, 1).getType() == Material.SNOW && snowLayers(1, 71, 1) == 5,
                "natural snow changed during thaw");
        check(world.getBlockAt(LEAF_X, 70, LEAF_Z).getType() == Material.OAK_LEAVES,
                "canopy base block changed during clear/thaw");
        check(world.getBlockAt(NORMAL_X, 71, NORMAL_Z).getType() == Material.AIR
                        && world.getBlockAt(NORMAL_X, 72, NORMAL_Z).getType() == Material.AIR,
                "normal owned stack did not thaw to air");
        check(world.getBlockAt(3, 71, 3).getType() == Material.OAK_LEAVES
                        && ((Leaves) world.getBlockAt(3, 71, 3).getBlockData()).isPersistent(),
                "protected canopy base changed");
        check(world.getBlockAt(GROUND_X, 71, GROUND_Z).getType() == Material.AIR
                        && world.getBlockAt(GROUND_X, 72, GROUND_Z).getType() == Material.AIR,
                "cleared ground stack returned during thaw");
        check(world.getBlockAt(LEAF_X, 71, LEAF_Z).getType() == Material.AIR
                        && world.getBlockAt(LEAF_X, 72, LEAF_Z).getType() == Material.AIR,
                "cleared leaf stack returned during thaw");
        check(world.getBlockAt(11,71,8).getType()==Material.SHORT_GRASS && world.getBlockAt(11,71,9).getType()==Material.FERN,"plants not restored after thaw");
        check(world.getBlockAt(11,71,10).getType()==Material.DIAMOND_BLOCK,"plant restoration overwrote foreign replacement");
        check(world.getBlockAt(11,72,10).getType()==Material.AIR,"snow above plant replacement not removed");
        check(world.getBlockAt(2,71,2).getType()==Material.FERN,"cancelled plant coverage changed fern");
        checks.add("plants-restored-on-thaw-and-foreign-replacement-preserved");
        checkNoSnowDrops();
        checks.addAll(List.of("foreign-replacement-preserved", "upper-snow-restored-to-air", "natural-snow-untouched",
                "canopy-base-preserved", "protected-canopy-base-preserved", "normal-stack-thawed", "cleared-stacks-stayed-cleared", "all-owned-snow-restored",
                "no-snow-item-drops", "no-forced-chunk-load"));
        writeReport("result-stage2", true, null);
        writeReport("result", true, null);
        world.save();
        getLogger().info("WINTER_STACK_PASSED " + countsJson());
        getServer().getScheduler().cancelTasks(this);
        Bukkit.shutdown();
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void cancelSelectedForm(BlockFormEvent event) {
        if (event.getBlock().getX() == 2 && event.getBlock().getY() == 71 && event.getBlock().getZ() == 2) {
            cancelledForms++;
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void trackChunkLoads(ChunkLoadEvent event) {
        if (cycling) forcedChunkLoad = true;
    }

    private boolean fullStack(int x, int z) {
        return snowLayers(x, 71, z) == 8 && snowLayers(x, 72, z) >= 1
                && totalLayers(x, z) == 12 && ownedStack(x, z);
    }

    private boolean ownedStack(int x, int z) {
        boolean any = false;
        for (int y = 71; y <= 80; y++) {
            Block block = world.getBlockAt(x, y, z);
            if (block.getType() == Material.SNOW) {
                any = true;
                if (!winter.terrain().owned(block)) return false;
            }
        }
        return any;
    }

    private int totalLayers(int x, int z) {
        int total = 0;
        for (int y = 71; y <= 80; y++) total += snowLayers(x, y, z);
        return total;
    }

    private boolean reducedStack(int x, int z) {
        int layers = totalLayers(x, z);
        return layers > 0 && layers <= 4;
    }

    private boolean hasOwnedLeafSnow() {
        for (int y = 71; y <= 80; y++) {
            Block block = world.getBlockAt(LEAF_X, y, LEAF_Z);
            if (block.getType() == Material.SNOW && winter.terrain().owned(block)) return true;
        }
        return false;
    }

    private void checkNoSnowDrops() {
        for (Item item : world.getEntitiesByClass(Item.class)) {
            Material type = item.getItemStack().getType();
            check(type != Material.SNOW && type != Material.SNOW_BLOCK && type != Material.SNOWBALL,
                    "snow item drop remains in fixture: " + type);
        }
    }

    private int snowLayers(int x, int y, int z) {
        Block block = world.getBlockAt(x, y, z);
        return block.getType() == Material.SNOW ? ((Snow) block.getBlockData()).getLayers() : 0;
    }

    private void clearStack(int x, int z, String name) {
        check(fullStack(x, z), name + " stack was not complete before clear");
        winter.terrain().clear(world.getBlockAt(x, 71, z));
        for (int y = 71; y <= 80; y++) {
            check(world.getBlockAt(x, y, z).getType() == Material.AIR, name + " upper snow was not cleared immediately");
            check(!winter.terrain().owned(world.getBlockAt(x, y, z)), name + " cleared snow remains owned");
        }
    }

    private int ownedSnowCount() {
        int count = 0;
        for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++) for (int y = 71; y <= 80; y++) {
            Block block = world.getBlockAt(x, y, z);
            if (block.getType() == Material.SNOW && winter.terrain().owned(block)) count++;
        }
        return count;
    }

    private String countsJson() {
        int snowBlocks = 0, layers = 0, owned = 0;
        for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++) for (int y = 71; y <= 80; y++) {
            Block block = world.getBlockAt(x, y, z);
            if (block.getType() == Material.SNOW) {
                snowBlocks++;
                layers += ((Snow) block.getBlockData()).getLayers();
                if (winter.terrain().owned(block)) owned++;
            }
        }
        return "{\"snowBlocks\":" + snowBlocks + ",\"snowLayers\":" + layers + ",\"ownedSnowBlocks\":" + owned + "}";
    }

    private void writeReport(String name, boolean passed, Throwable failure) throws Exception {
        StringBuilder checksJson = new StringBuilder("[");
        for (int i = 0; i < checks.size(); i++) {
            if (i > 0) checksJson.append(',');
            checksJson.append('"').append(escape(checks.get(i))).append('"');
        }
        checksJson.append(']');
        String failureJson = failure == null ? "" : ",\"failure\":\"" + escape(failure.toString()) + "\"";
        String json = "{\"passed\":" + passed + ",\"phase\":" + phase + ",\"checks\":" + checksJson
                + ",\"counts\":" + countsJson() + failureJson + "}\n";
        Files.writeString(dataDir.resolve(name + ".json"), json);
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\r", "\\r").replace("\n", "\\n");
    }

    private void command(String action) {
        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "winter " + action);
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private void fail(Throwable failure) {
        getLogger().log(Level.SEVERE, "WINTER_STACK_FAILED", failure);
        try {
            Files.createDirectories(dataDir == null ? getDataFolder().toPath() : dataDir);
            if (world != null && winter != null) writeReport("result", false, failure);
            else Files.writeString((dataDir == null ? getDataFolder().toPath() : dataDir).resolve("result.json"),
                    "{\"passed\":false,\"failure\":\"" + escape(failure.toString()) + "\"}\n");
        } catch (Exception reportFailure) {
            failure.addSuppressed(reportFailure);
        }
        getServer().getScheduler().cancelTasks(this);
        Bukkit.shutdown();
    }
}
