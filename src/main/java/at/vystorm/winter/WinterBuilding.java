package at.vystorm.winter;

import Vystorm.vystorm_core.API;
import Vystorm.vystorm_core.CoreApi;
import at.vystorm.winter.world.BuildAreas;
import at.vystorm.winter.world.ClearSweep;
import java.util.List;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.HeightMap;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;

/**
 * Snow-free building (1.28.0): the player's switch (Winter menu, {@code /winter build}), the halos around fresh blocks
 * and the snow kept under placed blocks. The bookkeeping is {@link BuildAreas}; the clearing runs in the terrain pass
 * ({@link at.vystorm.winter.world.WinterTerrain#clearCircle}). Server thread.
 */
final class WinterBuilding {
    static final String PERMISSION = "winter.build", UNLIMITED = "winter.build.unlimited";
    /** A block placed or broken this many blocks below the column's top or higher counts as near the surface. */
    static final int SURFACE_DEPTH = 3;

    /** The answer of a switch: a language key and its placeholders, and whether the switch changed. */
    record Answer(boolean changed, String key, Object... placeholders) {}

    private final WinterPlugin plugin;
    private final API core;
    private final BuildAreas areas;

    WinterBuilding(WinterPlugin plugin, API core, BuildAreas areas) {
        this.plugin = plugin;
        this.core = core;
        this.areas = areas;
    }

    BuildAreas areas() { return areas; }

    boolean enabled() { return plugin.getConfig().getBoolean("build-mode.enabled", true); }

    /** The menu shows the switch: build mode on and the player may use it. */
    boolean available(Player player) { return enabled() && player.hasPermission(PERMISSION); }

    /** {@code /winter build}: switches the player's area on, or off when it is on. */
    Answer toggle(Player player) {
        var area = areas.area(player.getUniqueId());
        return set(player, area.isEmpty() || !area.get().on());
    }

    /** Switches the player's area on (at his position, replacing an earlier one) or off. */
    Answer set(Player player, boolean on) {
        if (!enabled()) return new Answer(false, WinterSwitches.OFF_MESSAGE);
        UUID id = player.getUniqueId();
        if (!on) {
            var stopped = areas.stop(id);
            if (stopped.isEmpty()) return new Answer(false, "build-off", "minutes", 0);
            return new Answer(true, "build-off", "minutes", stopped.get().minutesLeft(areas.now()));
        }
        Location at = player.getLocation();
        World world = player.getWorld();
        int x = at.getBlockX(), y = at.getBlockY(), z = at.getBlockZ();
        boolean unlimited = player.hasPermission(UNLIMITED);
        int radius = areas.settings().radius();
        BuildAreas.Permit permit = permit(player, world, x, y, z, radius);
        if (!permit.may(x, z)) return new Answer(false, "build-protected");
        var started = areas.start(id, world.getUID(), x, z, radius, unlimited, permit);
        switch (started.outcome()) {
            case LIMIT -> {
                return new Answer(false, "build-limit", "max", areas.settings().maxAreas());
            }
            case COOLDOWN -> {
                return new Answer(false, "build-cooldown", "seconds", Math.max(1, (started.waitMillis() + 999) / 1000));
            }
            case STARTED -> { }
        }
        BuildAreas.Area area = started.area();
        plugin.lang().actionBar(player, "build-started", "radius", area.radius());
        long begun = System.nanoTime();
        if (plugin.terrain() != null) plugin.terrain().clearCircle(new BuildAreas.Clear(area.world(), area.x(), area.z(), area.radius()), done -> {
            Player online = Bukkit.getPlayer(id);
            if (online == null || areas.area(id).isEmpty()) return;
            double seconds = (System.nanoTime() - begun) / 1e9;
            plugin.lang().actionBar(online, "build-cleared", "blocks", done.changed(), "seconds", String.format(java.util.Locale.ROOT, "%.1f", seconds));
            plugin.getLogger().fine("Snow-free area of " + online.getName() + ": " + done.changed() + " positions in " + done.ticks() + " ticks");
        });
        return new Answer(true, "build-on", "radius", area.radius());
    }

    /** Once a second: areas whose time is over end, their players hear it. */
    void tick() {
        List<BuildAreas.Area> ended = areas.expire();
        for (BuildAreas.Area area : ended) {
            Player player = Bukkit.getPlayer(area.player());
            if (player != null) plugin.lang().actionBar(player, "build-ended");
        }
    }

    /** The player logged out: his area keeps the snow away for after-minutes, then ends. */
    void left(Player player) { areas.left(player.getUniqueId()); }

    // ---- without a click -----------------------------------------------------------------------------------------------

    /** A player placed a block (Core stage OBSERVE): the snow under it stays, the halo around it starts. */
    void placed(Player player, Block block) {
        if (plugin.terrain() == null) return;
        if (plugin.switches().on(WinterSwitches.Feature.UNDER_BLOCKS)) plugin.terrain().keepUnder(block);
        halo(player, block);
    }

    /** A player broke a block that was not the plugin's snow (Core stage FINISH, not cancelled). */
    void broke(Player player, Block block) {
        if (block.getType() == Material.SNOW || block.getType() == Material.ICE) return;   // shovelling is its own rule
        halo(player, block);
    }

    /** A player emptied a bucket: no ice forms in his water for a while. */
    void bucket(Player player, Block block) { halo(player, block); }

    private void halo(Player player, Block block) {
        if (!enabled() || !plugin.switches().on(WinterSwitches.Feature.BUILD_HALO) || player == null
                || !player.hasPermission(PERMISSION) || !plugin.controls(block.getWorld())) return;
        World world = block.getWorld();
        if (block.getY() < world.getHighestBlockYAt(block.getX(), block.getZ(), HeightMap.MOTION_BLOCKING) - SURFACE_DEPTH) return;
        int r = areas.settings().haloRadius();
        java.util.function.Supplier<BuildAreas.Permit> permits = () -> permit(player, world, block.getX(), block.getY(), block.getZ(), r);
        BuildAreas.Clear clear = areas.halo(player.getUniqueId(), world.getUID(), block.getX(), block.getZ(), permits);
        if (clear != null && plugin.terrain() != null) plugin.terrain().clearCircle(clear, null);
    }

    // ---- who may clear where -------------------------------------------------------------------------------------------

    /**
     * The columns the player may clear: Core's build check for bulk edits where Core has it (0.69.0), else Core's
     * regions with the flag {@code build}.
     */
    BuildAreas.Permit permit(Player player, World world, int x, int y, int z, int radius) {
        try {
            if (CoreApi.has("edit-ask")) return EditPermits.of(plugin, core, player, world, x, y, z, radius);
        } catch (LinkageError olderCore) {
            // fall through to the regions
        }
        var regions = core.area().regions();
        return (px, pz) -> regions.flag(new Location(world, px + 0.5, y, pz + 0.5), "build", true);
    }

    // ---- what the menu shows -------------------------------------------------------------------------------------------

    /** The switch in the player menu; null when it is not offered to this player. */
    WinterPlayerPanel.Build overview(Player player) {
        if (!available(player)) return null;
        var area = areas.area(player.getUniqueId());
        if (area.isEmpty()) return new WinterPlayerPanel.Build(false, areas.settings().radius(), 0);
        BuildAreas.Area a = area.get();
        return new WinterPlayerPanel.Build(a.on(), a.radius(), a.minutesLeft(areas.now()));
    }

    /** For {@code /winter status}: total positions the sweep cleared. */
    long cleared() {
        ClearSweep sweep = plugin.terrain() == null ? null : plugin.terrain().sweep();
        return sweep == null ? 0 : sweep.changedTotal();
    }
}
