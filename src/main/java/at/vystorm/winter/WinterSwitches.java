package at.vystorm.winter;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;

/**
 * The feature switches added in 1.24.0: one boolean per feature, read once with the rest of the settings and swapped
 * on reload. A missing key means "as before" (its default). Older switches ({@code frost.enabled},
 * {@code cold.enabled} ...) stay where the code always read them.
 */
public final class WinterSwitches {
    /** A switch that is new in 1.24.0: its key in {@code config.yml} and the behaviour of older versions. */
    public enum Feature {
        SNOW("snow.enabled"),
        SPRING_THAW("snow.spring-thaw.enabled"),
        DRIFTS("snow.drifts.enabled"),
        SOFT_EDGES("snow.soft-edges.enabled"),
        WIND_BLOWN("snow.wind-blown.enabled"),
        SPOTS("season-spots.enabled"),
        HOLIDAYS("holidays.enabled"),
        COUNTDOWN("holidays.countdown.enabled"),
        HOT_MEALS("cold.hot-meals.enabled"),
        WARM_HANDS("cold.warm-hands.enabled"),
        DUELS("snowball-fight.duels.enabled"),
        HIT_CHILL("snowball-fight.hit-chill.enabled"),
        SLED_RUNS("sledding.runs.enabled"),
        ADMIN_MENU("admin-menu.enabled"),
        DATE_TOOLS("date-tools.enabled"),
        WORLD_EVENTS("world-events.enabled"),
        TUTORIAL("tutorial.enabled"),
        /** 1.26.0: the rankings winter:snowball-hits and winter:snowball-wins (Core 0.67.0). */
        RANKINGS("rankings.enabled"),
        /**
         * 1.28.0: a snow-free halo around blocks players place or break near the surface. Off unless the owner switches
         * it on (the only switch whose missing key means false).
         */
        BUILD_HALO("build-mode.halo.enabled", false),
        /** 1.28.0: the plugin's snow under a block a player placed stays through the thaw. */
        UNDER_BLOCKS("build-mode.under-blocks.enabled");

        private final String path;
        private final boolean byDefault;
        Feature(String path) { this(path, true); }
        Feature(String path, boolean byDefault) { this.path = path; this.byDefault = byDefault; }
        public String path() { return path; }
        /** The value of a missing key and of the shipped config.yml. */
        public boolean byDefault() { return byDefault; }
    }

    /** Booleans that existed before 1.24.0 under another name; they keep their key and are declared as features. */
    public static final List<String> DECLARED = List.of(
            "snow.cover-leaves", "snow.cover-small-plants", "snow.remove-tall-plants", "snow.ramps",
            "snow.cover-stairs-and-slabs", "protection.protect-core-regions",
            "weather.packet-snow", "weather.warm-biome-particles", "holidays.advent", "holidays.christmas",
            "holidays.new-year", "holidays.fireworks", "cold.damage", "cold.breath", "cold.warnings",
            "advent-calendar.catch-up", "advent-calendar.join-reminder", "storms.darkness");

    /** The switches that were already named {@code enabled} before 1.24.0. */
    public static final List<String> OLDER = List.of(
            "frost.enabled", "weather.enabled", "spawns.enabled", "cold.enabled", "cold.reserve.enabled",
            "advent-calendar.enabled", "snowball-fight.enabled", "community-missions.enabled", "lamps.enabled",
            "seasons.enabled", "storms.enabled", "sledding.enabled", "build-mode.enabled", "snowfall.enabled");

    /** Shipped defaults of the older switches that are off out of the box. */
    private static final Set<String> OLDER_OFF_BY_DEFAULT = Set.of("seasons.enabled", "snowfall.enabled");

    /** Everything at its default: the state of a config written before 1.24.0. */
    public static final WinterSwitches DEFAULTS = new WinterSwitches(featureDefaults(), olderDefaults());

    private final Set<Feature> on;
    private final java.util.Map<String, Boolean> older;

    private WinterSwitches(Set<Feature> on, java.util.Map<String, Boolean> older) { this.on = on; this.older = older; }

    private static EnumSet<Feature> featureDefaults() {
        EnumSet<Feature> on = EnumSet.noneOf(Feature.class);
        for (Feature feature : Feature.values()) if (feature.byDefault) on.add(feature);
        return on;
    }

    private static java.util.Map<String, Boolean> olderDefaults() {
        java.util.Map<String, Boolean> values = new java.util.HashMap<>();
        for (String path : OLDER) values.put(path, !OLDER_OFF_BY_DEFAULT.contains(path));
        return java.util.Map.copyOf(values);
    }

    public static WinterSwitches read(ConfigurationSection config) {
        EnumSet<Feature> on = EnumSet.noneOf(Feature.class);
        for (Feature feature : Feature.values()) if (config.getBoolean(feature.path, feature.byDefault)) on.add(feature);
        java.util.Map<String, Boolean> older = new java.util.HashMap<>();
        for (String path : OLDER) older.put(path, config.getBoolean(path, !OLDER_OFF_BY_DEFAULT.contains(path)));
        return new WinterSwitches(on, java.util.Map.copyOf(older));
    }

    /** For tests: everything at its default except the given features, which are off. */
    public static WinterSwitches without(Feature... off) {
        EnumSet<Feature> on = featureDefaults();
        for (Feature feature : off) on.remove(feature);
        return new WinterSwitches(on, olderDefaults());
    }

    /** For tests: everything at its default plus the given features, which are on. */
    public static WinterSwitches with(Feature... extra) {
        EnumSet<Feature> on = featureDefaults();
        on.addAll(List.of(extra));
        return new WinterSwitches(on, olderDefaults());
    }

    public boolean on(Feature feature) { return on.contains(feature); }

    /** Same switch state (used to tell whether a reload changed a switch). */
    @Override public boolean equals(Object other) {
        return other instanceof WinterSwitches that && on.equals(that.on) && older.equals(that.older);
    }

    @Override public int hashCode() { return 31 * on.hashCode() + older.hashCode(); }

    /** An older {@code enabled} switch ({@code cold.enabled}, {@code lamps.enabled} ...) as read with the last load. */
    public boolean on(String olderPath) {
        Boolean value = older.get(olderPath);
        if (value == null) throw new IllegalArgumentException("not a switch: " + olderPath);
        return value;
    }

    /** Any switch by its key: a 1.24.0 feature or an older {@code enabled} switch, as read with the last load. */
    public boolean onPath(String path) {
        for (Feature feature : Feature.values()) if (feature.path.equals(path)) return on(feature);
        return on(path);
    }

    /** The one language key every switched-off command answers with. */
    public static final String OFF_MESSAGE = "feature-off";

    /**
     * The answer of a command or button whose own switch is on but which needs ANOTHER switch that is off; it names
     * that switch ({@code {name}} readable, {@code {switch}} the key).
     */
    public static final String NEEDS_MESSAGE = "feature-needs";

    /**
     * Whether the terrain loop may lay new snow and ice ({@code snow.enabled}). With the master switch off nothing new
     * forms; a forced thaw ({@code /winter thaw}) still clears what is there.
     */
    public boolean laysSnow() { return on(Feature.SNOW); }

    /** Whether the terrain loop looks at the plugin's existing snow and ice at all: master switch on, or a forced thaw. */
    public boolean touchesSnow(boolean forcedThaw) { return forcedThaw || on(Feature.SNOW); }

    /**
     * Whether the season's own melting is held back: {@code snow.spring-thaw.enabled} is false, no forced thaw runs and
     * the calendar is past the peak ({@code THAW}) or outside the season ({@code OFF}). Protection, excluded biomes and
     * season spots still remove snow.
     */
    public boolean holdsSeasonMelt(boolean forcedThaw, String phase) {
        return !forcedThaw && !on(Feature.SPRING_THAW) && ("THAW".equals(phase) || "OFF".equals(phase));
    }

    /** Every switch of the plugin in the order of {@code config.yml}: new, older and declared ones. */
    public static List<String> allPaths() {
        List<String> all = new ArrayList<>();
        for (Feature feature : Feature.values()) all.add(feature.path);
        all.addAll(OLDER);
        all.addAll(DECLARED);
        return all;
    }

    /** {@code snow.spring-thaw.enabled} -> {@code features.snow-spring-thaw}: the language key of a switch description. */
    public static String descriptionKey(String path) {
        String subject = path.endsWith(".enabled") ? path.substring(0, path.length() - ".enabled".length()) : path;
        return "features." + subject.replace('.', '-');
    }

    /** {@code snow.spring-thaw.enabled} -> {@code switch-names.snow-spring-thaw}: the language key of a switch's name. */
    public static String labelKey(String path) {
        return "switch-names." + descriptionKey(path).substring("features.".length());
    }

    /** Settings that are not switches but new since 1.24.0; {@link #addMissing} writes them into old files too. */
    public static final List<String> ADDED_KEYS = List.of("snow.wind-blown.angle", "snow.wind-blown.wind-from",
            "snow.wind-blown.max-height", "snow.wind-blown.max-layers", "snow.wind-blown.leaves-block",
            "snow.cover", "snow.biome-depth", "build-mode.max-radius", "build-mode.after-minutes", "build-mode.max-areas",
            "build-mode.cooldown-seconds", "build-mode.clear-per-tick", "build-mode.halo.radius", "build-mode.halo.minutes",
            "build-mode.halo.max");

    /**
     * Writes the switches that are new in 1.24.0 (and {@link #ADDED_KEYS}) into an existing {@code config.yml}, with their comments, and leaves
     * every other key alone (the file is registered without Core's full merge because a deleted mission goal must stay
     * deleted). The old file is copied to {@code config.yml.bak-<time>} first. Returns the number of keys added.
     */
    public static int addMissing(Plugin plugin) {
        File file = new File(plugin.getDataFolder(), "config.yml");
        if (!file.isFile()) return 0;
        YamlConfiguration defaults = new YamlConfiguration();
        try (InputStream in = plugin.getResource("config.yml")) {
            if (in == null) return 0;
            defaults.load(new InputStreamReader(in, StandardCharsets.UTF_8));
        } catch (IOException | InvalidConfigurationException failure) {
            return 0;
        }
        try {
            Merge merge = merge(file, defaults);
            if (merge.added() > 0) plugin.getLogger().info("Added " + merge.added() + " new key(s) to config.yml (a backup of the old file is next to it)");
            if (merge.haloOff()) plugin.getLogger().info("config.yml: build-mode.halo.enabled was still the untouched default of the 1.28.0 test build"
                    + " and is now false, the new default (set it to true or use /vadmin features to get the circle back)");
            return merge.added() + (merge.haloOff() ? 1 : 0);
        } catch (IOException | InvalidConfigurationException failure) {
            plugin.getLogger().warning("config.yml was left as it is, new keys use their defaults: " + failure.getMessage());
            return 0;
        }
    }

    /** What {@link #merge} changed: keys added, and whether the 1.28.0 test build's halo default was switched off. */
    record Merge(int added, boolean haloOff) {}

    /** Keys added plus the one-time halo step; 0 when the file was left as it is. */
    static int addMissing(File file, YamlConfiguration defaults) throws IOException, InvalidConfigurationException {
        Merge merge = merge(file, defaults);
        return merge.added() + (merge.haloOff() ? 1 : 0);
    }

    static Merge merge(File file, YamlConfiguration defaults) throws IOException, InvalidConfigurationException {
        YamlConfiguration disk = new YamlConfiguration();
        disk.load(file);
        boolean haloOff = candidateHaloOff(disk, defaults);
        int added = 0;
        for (Feature feature : Feature.values()) if (add(disk, defaults, feature.path)) added++;
        for (String path : ADDED_KEYS) if (add(disk, defaults, path)) added++;
        if (added == 0 && !haloOff) return new Merge(0, false);
        String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HHmmss"));
        Files.copy(file.toPath(), file.toPath().resolveSibling(file.getName() + ".bak-" + stamp), StandardCopyOption.REPLACE_EXISTING);
        disk.save(file);
        return new Merge(added, haloOff);
    }

    /**
     * The comment the unreleased 1.28.0 test build wrote above {@code build-mode.halo.enabled: true}, in its shipped
     * file and in the "missing new key" merge alike. The default is false since, with another comment.
     */
    static final String CANDIDATE_HALO_COMMENT = "Without any click: a block a player places, or a block he breaks near the surface, keeps a small circle around it"
            + " free of the plugin's snow and ice for a while, so new roofs, paths, walls and water are not snowed over or frozen.";

    /**
     * One-time step for files written by the 1.28.0 test build, which shipped the halo switched on: when the halo block
     * is still exactly what that build wrote (its comment, true, radius 6, 30 minutes, at most 4096), the switch is set
     * to false and gets the new comment. A copied default and an owner who left it on on purpose look the same; any edit
     * to that block (another value or comment) counts as a choice and is kept. After the step the comment is the new
     * one, so it never runs twice.
     */
    static boolean candidateHaloOff(YamlConfiguration disk, YamlConfiguration defaults) {
        String path = Feature.BUILD_HALO.path;
        if (!disk.isBoolean(path) || !disk.getBoolean(path)) return false;
        if (disk.getInt("build-mode.halo.radius", -1) != 6 || disk.getDouble("build-mode.halo.minutes", -1) != 30
                || disk.getInt("build-mode.halo.max", -1) != 4096) return false;
        if (!CANDIDATE_HALO_COMMENT.equals(comment(disk.getComments(path)))) return false;
        disk.set(path, false);
        if (!defaults.getComments(path).isEmpty()) disk.setComments(path, defaults.getComments(path));
        return true;
    }

    private static String comment(List<String> lines) {
        StringBuilder text = new StringBuilder();
        for (String line : lines) {
            if (line == null || line.isBlank()) continue;
            if (!text.isEmpty()) text.append(' ');
            text.append(line.trim());
        }
        return text.toString();
    }

    private static boolean add(YamlConfiguration disk, YamlConfiguration defaults, String path) {
        if (disk.contains(path, true) || !defaults.contains(path, true)) return false;
        List<String> created = new ArrayList<>();
        for (int dot = path.indexOf('.'); dot > 0; dot = path.indexOf('.', dot + 1)) {
            String parent = path.substring(0, dot);
            if (!disk.contains(parent, true)) created.add(parent);
            else if (!disk.isConfigurationSection(parent)) return false;
        }
        disk.set(path, defaults.get(path));
        created.add(path);
        for (String key : created) {
            if (!defaults.getComments(key).isEmpty()) disk.setComments(key, defaults.getComments(key));
            if (!defaults.getInlineComments(key).isEmpty()) disk.setInlineComments(key, defaults.getInlineComments(key));
        }
        return true;
    }
}
