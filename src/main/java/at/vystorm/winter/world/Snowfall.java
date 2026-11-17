package at.vystorm.winter.world;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

/**
 * Visible falling snow (1.22.0): white dust particles in a box around every player, sent to that player only, independent
 * of the real weather and the biome. The decisions (settings clamping, flakes per run with a carried fraction, whether to
 * skip a player) are static methods on plain values and tested without a server.
 */
public final class Snowfall {
    /** The task period in ticks; five runs make one second. */
    public static final int PERIOD_TICKS = 4;
    private static final double RUNS_PER_SECOND = 20.0 / PERIOD_TICKS;
    public static final int MIN_AMOUNT = 1, MAX_AMOUNT = 400, MIN_RADIUS = 4, MAX_RADIUS = 24, MIN_HEIGHT = 3, MAX_HEIGHT = 16;
    public static final double MIN_SIZE = 0.5, MAX_SIZE = 2.0;
    private static final String PREF = "snowfall";

    /** Settings as read from {@code snowfall.*}, every number already clamped. */
    public record Settings(boolean enabled, int amount, int radius, int height, boolean onlyUnderSky, boolean onlyInWinter, double flakeSize) {
        public static final Settings OFF = new Settings(false, 200, 10, 8, true, true, 1.5);

        public static Settings read(ConfigurationSection config) {
            ConfigurationSection s = config == null ? null : config.getConfigurationSection("snowfall");
            if (s == null) return OFF;
            return new Settings(s.getBoolean("enabled", false), clamp(s.getInt("amount", 200), MIN_AMOUNT, MAX_AMOUNT),
                    clamp(s.getInt("radius", 10), MIN_RADIUS, MAX_RADIUS), clamp(s.getInt("height", 8), MIN_HEIGHT, MAX_HEIGHT),
                    s.getBoolean("only-under-sky", true), s.getBoolean("only-in-winter", true), clampSize(s.getDouble("flake-size", 1.5)));
        }
    }

    /** Flakes to send this run and the fraction to carry into the next one. */
    public record Step(int flakes, double carry) {}

    /** Flake size 0.5..2.0; anything else (also NaN) falls back to the limits or 1.0. */
    static double clampSize(double size) { return Double.isFinite(size) ? Math.max(MIN_SIZE, Math.min(MAX_SIZE, size)) : 1.0; }

    static int clamp(int value, int min, int max) { return Math.max(min, Math.min(max, value)); }

    /** Flakes for one run from the per-second amount; the fraction that does not fit carries over (1..4 per second work). */
    public static Step step(int amountPerSecond, double carry) {
        double previous = Double.isFinite(carry) ? Math.max(0, Math.min(1, carry)) : 0;
        double total = clamp(amountPerSecond, MIN_AMOUNT, MAX_AMOUNT) / RUNS_PER_SECOND + previous;
        int flakes = (int) Math.floor(total + 1e-9);
        return new Step(flakes, Math.max(0, total - flakes));
    }

    /** The admin slider: 0 = off, otherwise 1..400 (rounded, out-of-range values clamp). */
    public static int sliderAmount(double value) {
        if (!Double.isFinite(value) || value < 0.5) return 0;
        return clamp((int) Math.round(value), MIN_AMOUNT, MAX_AMOUNT);
    }

    /** True when a roof is over the head: the highest block of the column is above the player's head (feet + 1). */
    public static boolean roofed(int highestBlockY, int feetBlockY) { return highestBlockY > feetBlockY + 1; }

    /** Whether this player gets flakes this run, from plain values. */
    public static boolean shows(boolean enabled, boolean playerOptedOut, boolean chunkLoaded, boolean onlyUnderSky, boolean roofed,
                                boolean onlyInWinter, boolean winterActive, boolean insideSpots) {
        if (!enabled || playerOptedOut || !chunkLoaded) return false;
        if (onlyUnderSky && roofed) return false;
        if (onlyInWinter && !winterActive) return false;
        return insideSpots;
    }

    /** What the plugin tells the snowfall: winter state and spot limits for a position. */
    public interface Host {
        /** True while the plugin's winter is active in this world (controlled world, intensity above 0, no thaw). */
        boolean winterActive(World world);
        /** True when the position is inside a season spot, or snow is not limited to spots. */
        boolean insideSpots(UUID world, double x, double z);
    }

    private final Plugin plugin;
    private final Host host;
    private final Vystorm.vystorm_core._uiMethods.settings.PlayerPrefsInterface prefs;
    private volatile Settings settings = Settings.OFF;
    private final Map<UUID, Double> carry = new ConcurrentHashMap<>();
    private final Map<UUID, Boolean> optedOut = new ConcurrentHashMap<>();
    private final Set<UUID> memoryOff = ConcurrentHashMap.newKeySet();
    private volatile boolean prefsBroken;

    /** @param prefs Core's player preferences, {@code api.settings().prefs()} */
    public Snowfall(Plugin plugin, Host host, Vystorm.vystorm_core._uiMethods.settings.PlayerPrefsInterface prefs) { this.plugin = plugin; this.host = host; this.prefs = prefs; }

    public void configure(Settings next) { settings = next; if (!next.enabled()) carry.clear(); }

    public Settings settings() { return settings; }

    /** True when the player switched the effect off for himself (cached; Core PlayerPrefs, memory when Core has none). */
    public boolean optedOut(Player player) {
        UUID id = player.getUniqueId();
        Boolean cached = optedOut.get(id);
        if (cached != null) return cached;
        boolean off = memoryOff.contains(id);
        if (!off && !prefsBroken) {
            try { off = !prefs.bool(plugin, player, PREF, true); }
            catch (RuntimeException unavailable) { prefsBroken = true; }
        }
        optedOut.put(id, off);
        return off;
    }

    /** Toggles the player's own switch; returns true when flakes are now on for him. */
    public boolean toggle(Player player) {
        boolean nowOn = optedOut(player);
        UUID id = player.getUniqueId();
        optedOut.put(id, !nowOn);
        if (nowOn) memoryOff.remove(id); else memoryOff.add(id);
        if (!prefsBroken) {
            try { prefs.setBool(plugin, player, PREF, nowOn); }
            catch (RuntimeException unavailable) { prefsBroken = true; }
        }
        return nowOn;
    }

    public void forget(UUID id) { carry.remove(id); optedOut.remove(id); }

    /** One run for all online players (server thread, every {@link #PERIOD_TICKS} ticks). */
    public void tick(Iterable<? extends Player> players) {
        Settings s = settings;
        if (!s.enabled()) return;
        for (Player player : players) {
            if (!player.isOnline() || player.isDead()) continue;
            World world = player.getWorld();
            Location at = player.getLocation();
            boolean loaded = world.isChunkLoaded(at.getBlockX() >> 4, at.getBlockZ() >> 4);
            boolean roofed = s.onlyUnderSky() && loaded && roofed(world.getHighestBlockYAt(at.getBlockX(), at.getBlockZ()), at.getBlockY());
            boolean winter = !s.onlyInWinter() || host.winterActive(world);
            boolean spots = host.insideSpots(world.getUID(), at.getX(), at.getZ());
            if (!shows(true, optedOut(player), loaded, s.onlyUnderSky(), roofed, s.onlyInWinter(), winter, spots)) continue;
            Step step = step(s.amount(), carry.getOrDefault(player.getUniqueId(), 0.0));
            carry.put(player.getUniqueId(), step.carry());
            if (step.flakes() <= 0) continue;
            Location eye = player.getEyeLocation();
            // count form: the offsets are the spread of the flakes around the centre point, one packet per player and run
            // 1.22.1: the offsets are a gaussian spread, not a box edge. With the full radius most flakes landed 10 to 30
            // blocks away and were too small to see; a spread of radius/2.5 keeps nearly all of them inside the box.
            player.spawnParticle(Particle.DUST, eye.getX(), eye.getY() + s.height() / 3.0, eye.getZ(), step.flakes(),
                    s.radius() / 2.5, s.height() / 3.0, s.radius() / 2.5, 0, new Particle.DustOptions(Color.WHITE, (float) s.flakeSize()));
        }
    }
}
