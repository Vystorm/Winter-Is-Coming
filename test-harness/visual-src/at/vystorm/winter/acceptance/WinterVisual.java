package at.vystorm.winter.acceptance;

import Vystorm.vystorm_core.region.Region;
import Vystorm.vystorm_core.region.RegionService;
import Vystorm.vystorm_core.region.RegionShape;
import at.vystorm.winter.WinterPlugin;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import org.bukkit.Bukkit;
import org.bukkit.block.Biome;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Accelerated, controlled visual fixture for an isolated test server. Exported JSON records
 * actual block state read from that server; these are data snapshots, not generated screenshots.
 */
public final class WinterVisual extends JavaPlugin {
    private static final int MIN_STAGE_TICKS = 600;
    private static final int STABLE_TICKS = 100;
    private static final int MAX_STAGE_TICKS = 6000;
    private static final String[] NAMES = {"before", "early", "peak", "thaw", "after"};
    private static final LocalDate[] DATES = {
        LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 30),
        LocalDate.of(2026, 12, 24), LocalDate.of(2027, 2, 15),
        LocalDate.of(2027, 3, 2)
    };
    private WinterPlugin winter;
    private World world;
    private Path output;
    private int stage, ticks, stable;
    private String lastDigest;
    private String baselineMaterialDigest;
    private boolean failed;
    private final boolean captureMode = Boolean.getBoolean("winter.visual.capture");
    private int awaitingCapture = -1, captureWaitTicks;

    @Override public void onEnable() {
        if (!Boolean.getBoolean("winter.visual")) {
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        getServer().getScheduler().runTaskLater(this, () -> {
            try { startFixture(); }
            catch (Throwable error) { fail(error); }
        }, 40L);
    }

    private void startFixture() throws Exception {
        winter = (WinterPlugin) Bukkit.getPluginManager().getPlugin("WinterIsComing");
        world = Bukkit.getWorld("world");
        check(winter != null && world != null, "WinterIsComing and isolated world 'world' are required");
        output = getDataFolder().toPath().resolve("snapshots");
        Files.createDirectories(output);

        var config = winter.getConfig();
        config.set("performance.columns-per-cycle", 256);
        config.set("performance.changes-per-cycle", 32);
        config.set("performance.period-ticks", 1200);
        config.set("snow.max-layers", 3);
        config.set("frost.ocean-radius", 8);
        config.set("frost.inland-radius", 4);
        config.set("weather.enabled", false);
        config.set("spawns.enabled", false);
        config.set("holidays.fireworks", false);
        config.set("worlds", List.of("world"));
        winter.saveConfig();
        command("reload");
        world.setGameRule(org.bukkit.GameRule.RANDOM_TICK_SPEED, 0);
        world.setGameRule(org.bukkit.GameRule.DO_DAYLIGHT_CYCLE, false);
        world.setGameRule(org.bukkit.GameRule.DO_WEATHER_CYCLE, false);
        world.setTime(6000);
        world.setStorm(false);
        buildFixture();
        if (captureMode) getServer().getPluginManager().registerEvents(new org.bukkit.event.Listener() {
            @org.bukkit.event.EventHandler public void joined(org.bukkit.event.player.PlayerJoinEvent event) {
                getServer().getScheduler().runTaskLater(WinterVisual.this, () -> {
                    var player = event.getPlayer();
                    if (!player.isOnline()) return;
                    player.setGameMode(org.bukkit.GameMode.SPECTATOR);
                    player.teleport(new org.bukkit.Location(world, 42, 89, 46, 139, 27));
                }, 10L);
            }
        }, this);
        for (int cx = 0; cx <= 1; cx++) for (int cz = 0; cz <= 1; cz++) {
            var chunk = world.getChunkAt(cx, cz);
            chunk.addPluginChunkTicket(this);
            winter.terrain().load(chunk);
        }
        RegionService.get().register(this, Region.of("winter-visual", "world-default",
                new RegionShape.Box(world.getUID(), -30000000, -64, -30000000, 30000000, 320, 30000000))
                .withPriority(0).withFlags(Map.of("winter", false)));
        RegionService.get().register(this, Region.of("winter-visual", "fixture",
                new RegionShape.Box(world.getUID(), 0, 67, 0, 31, 82, 31))
                .withPriority(10).withFlags(Map.of("winter", true)));
        RegionService.get().register(this, Region.of("winter-visual", "protected-plot",
                new RegionShape.Box(world.getUID(), 25, 67, 3, 28, 82, 6))
                .withPriority(20).withFlags(Map.of("winter", false)));
        baselineMaterialDigest = materialDigest();
        command("setdate " + DATES[0]);
        exportStage(0);
        if (captureMode) awaitingCapture = 0;
        else beginNextStage();
        getServer().getScheduler().runTaskTimer(this, this::tick, 1L, 1L);
    }

    private void buildFixture() {
        for (int x = 0; x < 32; x++) for (int z = 0; z < 32; z++) {
            int coast = 11 + (int) Math.round(1.2 * Math.sin(x / 5.0));
            boolean sea = z >= coast;
            Material surface = sea ? Material.WATER : z >= coast - 2 ? Material.SAND : Material.GRASS_BLOCK;
            for (int y = 67; y <= 69; y++) world.getBlockAt(x, y, z).setType(sea ? Material.WATER : Material.DIRT, false);
            world.getBlockAt(x, 70, z).setType(surface, false);
            for (int y = 71; y <= 82; y++) world.getBlockAt(x, y, z).setType(Material.AIR, false);
            world.setBiome(x, 70, z, Biome.OCEAN);
        }
        // A visible path leading to a compact cabin at the inland edge.
        for (int z = 7; z <= 10; z++) world.getBlockAt(8, 70, z).setType(Material.DIRT_PATH, false);
        for (int x = 5; x <= 10; x++) for (int z = 2; z <= 6; z++) {
            if (x == 5 || x == 10 || z == 2 || z == 6) world.getBlockAt(x, 71, z).setType(Material.OAK_PLANKS, false);
            for (int y = 72; y <= 74; y++) if (x == 5 || x == 10 || z == 2 || z == 6)
                world.getBlockAt(x, y, z).setType(Material.OAK_PLANKS, false);
            world.getBlockAt(x, 75, z).setType(Material.OAK_PLANKS, false);
        }
        world.getBlockAt(8, 71, 6).setType(Material.AIR, false);
        world.getBlockAt(8, 72, 6).setType(Material.AIR, false);
        world.getBlockAt(7, 73, 6).setType(Material.GLASS, false);
        world.getBlockAt(9, 73, 6).setType(Material.GLASS, false);
        // Small, simple trees away from the house and shore.
        tree(2, 4); tree(17, 4);
    }

    private void tree(int x, int z) {
        world.getBlockAt(x, 71, z).setType(Material.OAK_LOG, false);
        world.getBlockAt(x, 72, z).setType(Material.OAK_LOG, false);
        for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++)
            world.getBlockAt(x + dx, 73, z + dz).setType(Material.OAK_LEAVES, false);
        world.getBlockAt(x, 74, z).setType(Material.OAK_LEAVES, false);
    }

    private void beginNextStage() {
        stage++;
        ticks = stable = 0;
        lastDigest = null;
        if (stage >= NAMES.length) return;
        command("setdate " + DATES[stage]);
    }

    private void tick() {
        if (failed) return;
        try {
            if (awaitingCapture >= 0) {
                Path marker = output.resolve(String.format("%02d-%s.json.captured", awaitingCapture + 1, NAMES[awaitingCapture]));
                if (Files.isRegularFile(marker)) {
                    awaitingCapture = -1;
                    captureWaitTicks = 0;
                    if (stage == 4) finish(); else beginNextStage();
                } else if (++captureWaitTicks > 24000) {
                    throw new IllegalStateException("Timed out waiting for real client capture of " + NAMES[stage]);
                }
                return;
            }
            for (int i = 0; i < (captureMode ? 64 : 8); i++) winter.terrain().cycle();
            ticks++;
            String digest = digest();
            stable = digest.equals(lastDigest) ? stable + 1 : 0;
            lastDigest = digest;
            if (ticks >= MIN_STAGE_TICKS && stable >= STABLE_TICKS) {
                exportStage(stage);
                check(lastProtectedPlotSnow == 0, "protected grass plot must remain snow-free");
                if (stage == 1 || stage == 2 || stage == 3) {
                    int guaranteedRadius = (int)Math.floor(8 * winter.season().intensity() * 0.85);
                    for (int x = 0; x < 32; x++) {
                        int coast = 11 + (int)Math.round(1.2 * Math.sin(x / 5.0));
                        for (int distance = 1; distance <= guaranteedRadius; distance++) {
                            check(world.getBlockAt(x, 70, coast + distance - 1).getType() == Material.ICE,
                                    "continuous near-shore ice at " + x + "," + (coast + distance - 1));
                        }
                    }
                }
                if (stage == 2) {
                    check(ownedCount(Material.SNOW) > 0, "peak must produce owned snow");
                    check(ownedCount(Material.ICE) > 0, "peak must produce owned ice");
                }
                if (stage == 4) {
                    check(ownedWeatherBlocks() == 0, "after season must restore all owned snow and ice");
                    check(materialDigest().equals(baselineMaterialDigest), "fixture materials must return to their baseline");
                    if (captureMode) awaitingCapture = stage; else finish();
                    return;
                }
                if (captureMode) awaitingCapture = stage; else beginNextStage();
            } else if (ticks >= MAX_STAGE_TICKS) {
                throw new IllegalStateException("Stage " + NAMES[stage] + " did not converge; stable=" + stable);
            }
        } catch (Throwable error) { fail(error); }
    }

    private void finish() throws IOException {
        writeResult(true, "passed");
        getServer().getScheduler().cancelTasks(this);
        world.save();
        Bukkit.shutdown();
    }

    private String digest() throws Exception {
        MessageDigest sha = MessageDigest.getInstance("SHA-256");
        for (int x = 0; x < 32; x++) for (int z = 0; z < 32; z++) for (int y = 67; y <= 82; y++) {
            Block b = world.getBlockAt(x, y, z);
            sha.update((x + "," + y + "," + z + ":" + b.getBlockData().getAsString() + ":" + winter.terrain().owned(b) + "\n").getBytes(StandardCharsets.UTF_8));
        }
        return HexFormat.of().formatHex(sha.digest());
    }

    private String materialDigest() throws Exception {
        MessageDigest sha = MessageDigest.getInstance("SHA-256");
        for (int x = 0; x < 32; x++) for (int z = 0; z < 32; z++) for (int y = 67; y <= 82; y++) {
            Block b = world.getBlockAt(x, y, z);
            sha.update((x + "," + y + "," + z + ":" + b.getBlockData().getAsString() + "\n").getBytes(StandardCharsets.UTF_8));
        }
        return HexFormat.of().formatHex(sha.digest());
    }

    private int lastProtectedPlotSnow;

    private void exportStage(int index) throws Exception {
        List<String> blocks = new ArrayList<>();
        int snow = 0, layers = 0, ice = 0, ownedSnow = 0, ownedIce = 0, owned = 0, protectedSnow = 0;
        for (int x = 0; x < 32; x++) for (int z = 0; z < 32; z++) for (int y = 67; y <= 82; y++) {
            Block b = world.getBlockAt(x, y, z);
            if (b.getType().isAir()) continue;
            boolean isOwned = winter.terrain().owned(b);
            String material = b.getType().name();
            if (b.getType() == Material.SNOW) { snow++; layers += ((org.bukkit.block.data.type.Snow)b.getBlockData()).getLayers(); if (isOwned) ownedSnow++; }
            if (b.getType() == Material.ICE) { ice++; if (isOwned) ownedIce++; }
            if (isOwned) owned++;
            if (b.getType() == Material.SNOW && x >= 25 && x <= 28 && z >= 3 && z <= 6) protectedSnow++;
            blocks.add("{\"x\":"+x+",\"y\":"+y+",\"z\":"+z+",\"material\":"+quote(material)+",\"data\":"+quote(b.getBlockData().getAsString())+",\"owned\":"+isOwned+"}");
        }
        check(protectedSnow == 0, "protected grass plot must remain snow-free at stage " + NAMES[index]);
        String json = "{\n  \"stage\":"+quote(NAMES[index])+",\n  \"date\":"+quote(winter.date().toString())+",\n  \"intensity\":"+winter.season().intensity()+",\n"
                + "  \"bounds\":{\"minX\":0,\"maxX\":31,\"minY\":67,\"maxY\":82,\"minZ\":0,\"maxZ\":31},\n"
                + "  \"settings\":{\"columnsPerCycle\":256,\"changesPerCycle\":32,\"periodTicks\":1200,\"maxSnowLayers\":3,\"oceanRadius\":8,\"inlandRadius\":4},\n"
                + "  \"summary\":{\"snowBlocks\":"+snow+",\"snowLayers\":"+layers+",\"iceBlocks\":"+ice+",\"ownedBlocks\":"+owned+",\"ownedSnowBlocks\":"+ownedSnow+",\"ownedIceBlocks\":"+ownedIce+",\"protectedPlotSnowBlocks\":"+protectedSnow+"},\n"
                + "  \"blocks\":[\n    "+String.join(",\n    ", blocks)+"\n  ]\n}\n";
        Files.writeString(output.resolve(String.format("%02d-%s.json", index + 1, NAMES[index])), json, StandardCharsets.UTF_8);
        lastProtectedPlotSnow = protectedSnow;
        getLogger().info("WINTER_VISUAL_STAGE stage="+NAMES[index]+" snow="+snow+" ice="+ice+" layers="+layers+" owned="+owned+" protectedSnow="+protectedSnow);
    }

    private int ownedWeatherBlocks() {
        int count = 0;
        for (int x = 0; x < 32; x++) for (int z = 0; z < 32; z++) for (int y = 67; y <= 82; y++) {
            Block b = world.getBlockAt(x, y, z);
            if ((b.getType() == Material.SNOW || b.getType() == Material.ICE) && winter.terrain().owned(b)) count++;
        }
        return count;
    }

    private int ownedCount(Material material) {
        int count = 0;
        for (int x = 0; x < 32; x++) for (int z = 0; z < 32; z++) for (int y = 67; y <= 82; y++) {
            Block b = world.getBlockAt(x, y, z);
            if (b.getType() == material && winter.terrain().owned(b)) count++;
        }
        return count;
    }

    private void writeResult(boolean pass, String message) throws IOException {
        Files.writeString(getDataFolder().toPath().resolve("result.json"),
                "{\"passed\":"+pass+",\"message\":"+quote(message)+",\"stagesCompleted\":"+(stage+1)+"}\n", StandardCharsets.UTF_8);
    }
    private void command(String text) { Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "winter " + text); }
    private static String quote(String s) { return "\""+s.replace("\\", "\\\\").replace("\"", "\\\"")+"\""; }
    private static void check(boolean ok, String message) { if (!ok) throw new IllegalStateException(message); }
    private void fail(Throwable error) {
        if (failed) return;
        failed = true;
        getLogger().log(Level.SEVERE, "WINTER_VISUAL_FAILED", error);
        try { Files.createDirectories(getDataFolder().toPath()); writeResult(false, error.toString()); }
        catch (IOException writeFailure) { error.addSuppressed(writeFailure); }
        getServer().getScheduler().cancelTasks(this);
        Bukkit.shutdown();
    }
}
