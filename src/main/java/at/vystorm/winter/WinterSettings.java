package at.vystorm.winter;

import at.vystorm.winter.season.SeasonCalendar;
import java.time.MonthDay;
import java.time.ZoneId;
import java.util.Set;
import java.util.stream.Collectors;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.configuration.file.FileConfiguration;

/** Immutable, validated configuration. Reload swaps it only after full validation. */
public record WinterSettings(Set<String> worlds, ZoneId zone, SeasonCalendar calendar, int layers, boolean treeSnow, boolean vegetationSnow, double driftStrength, int transitionBlocks,
        Set<String> excludedBiomes, Set<Material> clearMaterials, boolean frost, int oceanRadius,
        int inlandRadius, int searchBudget, Set<Material> shores, boolean protectRegions, String regionFlag,
        int period, int columns, int changes, int maxTracked, boolean weather, double snowChance,
        int weatherSeconds, boolean particles, boolean packetSnow, boolean spawns, double skeleton, double spider, double creeper,
        boolean advent, boolean christmas, boolean newYear, MonthDay christmasStart, MonthDay christmasEnd,
        boolean fireworks, Material reward, int rewardAmount, Terrain terrain) {
    public static WinterSettings read(FileConfiguration c) {
        Material reward = Material.valueOf(c.getString("holidays.reward-material", "COOKIE"));
        if (!reward.isItem() || reward.isAir()) throw new IllegalArgumentException("Reward must be an item");
        return new WinterSettings(Set.copyOf(c.getStringList("worlds")), ZoneId.of(c.getString("timezone", "Europe/Vienna")),
                new SeasonCalendar(date(c,"calendar.start"),date(c,"calendar.peak"),date(c,"calendar.thaw"),date(c,"calendar.end")),
                integer(c,"snow.max-layers",1,64),c.getBoolean("snow.cover-leaves",true),c.getBoolean("snow.cover-small-plants",true),
                driftStrength(c),transitionBlocks(c),
                Set.copyOf(c.getStringList("snow.excluded-biomes")),materials(c,"snow.clear-materials"),
                c.getBoolean("frost.enabled"),integer(c,"frost.ocean-radius",0,128),integer(c,"frost.inland-radius",0,128),
                integer(c,"frost.search-budget",1,65536),materials(c,"frost.shore-materials"),c.getBoolean("protection.protect-core-regions"),
                c.getString("protection.region-flag","winter"),integer(c,"performance.period-ticks",1,1200),
                integer(c,"performance.columns-per-cycle",1,8192),integer(c,"performance.changes-per-cycle",1,512),
                integer(c,"performance.max-tracked-blocks",1,1000000),c.getBoolean("weather.enabled"),number(c,"weather.snow-chance-at-peak",0,1),
                integer(c,"weather.interval-seconds",10,86400),c.getBoolean("weather.warm-biome-particles"),c.getBoolean("weather.packet-snow",true),c.getBoolean("spawns.enabled"),
                number(c,"spawns.skeleton-weight-at-peak",0,3),number(c,"spawns.spider-weight-at-peak",0,3),number(c,"spawns.creeper-weight-at-peak",0,3),
                holiday(c,"holidays.advent"),holiday(c,"holidays.christmas"),holiday(c,"holidays.new-year"),
                date(c,"holidays.christmas-start"),date(c,"holidays.christmas-end"),holiday(c,"holidays.fireworks"),
                reward,integer(c,"holidays.reward-amount",1,reward.getMaxStackSize()),
                Terrain.read(c));
    }
    /** Per-block terrain switches, read once with the rest of the settings. */
    public record Terrain(boolean ramps, boolean coverStairsAndSlabs, boolean removeTallPlants) {
        public static Terrain read(FileConfiguration c) {
            return new Terrain(c.getBoolean("snow.ramps", true), c.getBoolean("snow.cover-stairs-and-slabs", true), c.getBoolean("snow.remove-tall-plants", true));
        }
    }
    /**
     * One holiday key under the master switch {@code holidays.enabled} (1.24.0): off = no holiday, no new gift, no
     * countdown and no fireworks, whatever the single keys say; a missing master key = on.
     */
    static boolean holiday(FileConfiguration c, String key) { return c.getBoolean("holidays.enabled", true) && c.getBoolean(key); }
    /** {@code snow.drift-strength}, 0 while {@code snow.drifts.enabled} is false (the value is still checked). */
    static double driftStrength(FileConfiguration c) {
        double value = optionalNumber(c, "snow.drift-strength", 0.45, 0, 1);
        return c.getBoolean("snow.drifts.enabled", true) ? value : 0;
    }
    /** {@code snow.transition-blocks}, 0 while {@code snow.soft-edges.enabled} is false (the value is still checked). */
    static int transitionBlocks(FileConfiguration c) {
        int value = optionalInteger(c, "snow.transition-blocks", 4, 0, 12);
        return c.getBoolean("snow.soft-edges.enabled", true) ? value : 0;
    }
    public boolean includes(World world) { return world.getEnvironment() == World.Environment.NORMAL && worlds.contains(world.getName()); }
    private static MonthDay date(FileConfiguration c,String key) { return MonthDay.parse("--"+c.getString(key)); }
    private static Set<Material> materials(FileConfiguration c,String key) { return c.getStringList(key).stream().map(Material::valueOf).collect(Collectors.toUnmodifiableSet()); }
    private static int integer(FileConfiguration c,String key,int min,int max) {
        int n=c.getInt(key); if(n<min||n>max) throw new IllegalArgumentException(key+" must be "+min+".."+max);return n;
    }
    private static int optionalInteger(FileConfiguration c,String key,int fallback,int min,int max) {
        int n=c.getInt(key,fallback);if(n<min||n>max)throw new IllegalArgumentException(key+" must be "+min+".."+max);return n;
    }
    private static double optionalNumber(FileConfiguration c,String key,double fallback,double min,double max) {
        double n=c.getDouble(key,fallback);if(!Double.isFinite(n)||n<min||n>max)throw new IllegalArgumentException(key+" must be "+min+".."+max);return n;
    }
    private static double number(FileConfiguration c,String key,double min,double max) {
        double n=c.getDouble(key);if(!Double.isFinite(n)||n<min||n>max) throw new IllegalArgumentException(key+" must be "+min+".."+max);return n;
    }
}
