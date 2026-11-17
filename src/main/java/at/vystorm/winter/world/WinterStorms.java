package at.vystorm.winter.world;

import at.vystorm.winter.WinterPlugin;
import java.util.*;
import org.bukkit.*;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

/**
 * Winter weather events: now and then a world under winter control gets a snowstorm or a blizzard. Players are warned
 * a few minutes before; during the storm it snows, players outside get colder faster, see dense snow and (blizzard)
 * pulsing darkness, and the action bar tells them to find shelter. The schedule lives in memory: a restart simply
 * plans the next storm. Runs every second on the server thread.
 */
public final class WinterStorms {
    public enum Kind { SNOWSTORM, BLIZZARD; public String key() { return name().toLowerCase(Locale.ROOT); } }

    public record Options(boolean enabled, int minGapMinutes, int maxGapMinutes, int warningMinutes, int durationMinutes,
                          double blizzardChance, double snowstormCold, double blizzardCold, boolean darkness) {
        public static Options read(FileConfiguration c) {
            int min = Math.clamp(c.getInt("storms.min-gap-minutes", 60), 5, 1440);
            int max = Math.clamp(c.getInt("storms.max-gap-minutes", 150), min, 2880);
            return new Options(c.getBoolean("storms.enabled", true), min, max, Math.clamp(c.getInt("storms.warning-minutes", 2), 0, 30),
                    Math.clamp(c.getInt("storms.duration-minutes", 6), 1, 60), Math.clamp(c.getDouble("storms.blizzard-chance", 0.35), 0, 1),
                    Math.clamp(c.getDouble("storms.snowstorm-cold", 1.6), 1, 5), Math.clamp(c.getDouble("storms.blizzard-cold", 2.5), 1, 5),
                    c.getBoolean("storms.darkness", true));
        }
    }

    /** One planned or running storm; times in epoch millis. */
    public record Storm(Kind kind, long warnAt, long start, long end) {
        boolean running(long now) { return now >= start && now < end; }
    }

    /**
     * Plans the next storm after {@code now}: the gap is drawn between the two limits and stretched when the winter is
     * weak ({@code intensity} below 1); null when there is no winter.
     */
    public static Storm plan(long now, Options o, double intensity, Random random) {
        if (!o.enabled() || intensity <= 0) return null;
        double minutes = o.minGapMinutes() + random.nextDouble() * (o.maxGapMinutes() - o.minGapMinutes());
        minutes /= Math.clamp(intensity, 0.25, 1);
        long start = now + Math.round(minutes * 60_000);
        Kind kind = random.nextDouble() < o.blizzardChance() * Math.clamp(intensity, 0, 1) ? Kind.BLIZZARD : Kind.SNOWSTORM;
        return new Storm(kind, start - o.warningMinutes() * 60_000L, start, start + o.durationMinutes() * 60_000L);
    }

    /** Cold multiplier for players outside. */
    public static double coldFactor(Kind kind, Options o) {
        return kind == null ? 1 : kind == Kind.BLIZZARD ? o.blizzardCold() : o.snowstormCold();
    }

    private final WinterPlugin plugin;
    private final Random random = new Random();
    private final Map<UUID, Storm> storms = new HashMap<>();
    private final Set<UUID> warned = new HashSet<>(), started = new HashSet<>();
    private Options options;
    private int seconds;

    public WinterStorms(WinterPlugin plugin) { this.plugin = plugin; this.options = Options.read(plugin.getConfig()); }

    public Options options() { return options; }

    /** The storm running in {@code world} right now. */
    public Optional<Kind> active(World world) {
        Storm storm = storms.get(world.getUID());
        return storm != null && storm.running(System.currentTimeMillis()) ? Optional.of(storm.kind()) : Optional.empty();
    }

    public Optional<Storm> planned(World world) { return Optional.ofNullable(storms.get(world.getUID())); }

    public double coldFactor(World world) { return coldFactor(active(world).orElse(null), options); }

    /** Admin: starts a storm now (no warning) for the configured duration. */
    public void start(World world, Kind kind) {
        long now = System.currentTimeMillis();
        storms.put(world.getUID(), new Storm(kind, now, now, now + options.durationMinutes() * 60_000L));
        warned.add(world.getUID()); started.remove(world.getUID());
    }

    /** Admin: ends a running storm and plans the next one. */
    public boolean stop(World world) {
        Storm storm = storms.get(world.getUID());
        if (storm == null || !storm.running(System.currentTimeMillis())) return false;
        storms.put(world.getUID(), new Storm(storm.kind(), storm.warnAt(), storm.start(), System.currentTimeMillis()));
        return true;
    }

    public void tick() {
        options = Options.read(plugin.getConfig());
        seconds++;
        long now = System.currentTimeMillis();
        double intensity = plugin.season().intensity();
        boolean winter = options.enabled() && intensity > 0 && !plugin.season().phase().equals("THAW");
        for (World world : Bukkit.getWorlds()) {
            UUID id = world.getUID();
            if (!winter || !plugin.controls(world) || world.getEnvironment() != World.Environment.NORMAL) {
                if (storms.remove(id) != null) { warned.remove(id); started.remove(id); }
                continue;
            }
            Storm storm = storms.get(id);
            if (storm == null || now >= storm.end()) {
                if (storm != null && started.remove(id)) tellWorld(world, "storm.ended", storm.kind());
                warned.remove(id);
                storms.put(id, storm = plan(now, options, intensity, random));
                if (storm == null) { storms.remove(id); continue; }
            }
            if (now >= storm.warnAt() && now < storm.start() && warned.add(id))
                tellWorld(world, "storm.warning", storm.kind(), "minutes", Math.max(1, (storm.start() - now + 59_999) / 60_000));
            if (!storm.running(now)) continue;
            if (started.add(id)) tellWorld(world, "storm.started", storm.kind(), "minutes", Math.max(1, (storm.end() - now + 59_999) / 60_000));
            effects(world, storm.kind());
        }
    }

    private void effects(World world, Kind kind) {
        int shown = 0;
        for (Player player : world.getPlayers()) {
            if (player.getGameMode() != GameMode.SURVIVAL && player.getGameMode() != GameMode.ADVENTURE) continue;
            if (player.getEyeLocation().getBlock().getLightFromSky() < 12) continue;
            if (plugin.settings().excludedBiomes().contains(BiomeKeys.key(player.getLocation().getBlock().getBiome()))) continue;
            if (shown++ < 64) {
                Location at = player.getLocation().add(0, 1.5, 0);
                int count = kind == Kind.BLIZZARD ? 90 : 40;
                player.spawnParticle(Particle.SNOWFLAKE, at, count, 4, 2.5, 4, 0.12);
                player.spawnParticle(Particle.WHITE_ASH, at, count / 2, 3, 2, 3, 0.02);
            }
            if (kind == Kind.BLIZZARD && options.darkness()) {
                PotionEffect current = player.getPotionEffect(PotionEffectType.DARKNESS);
                if (current == null || current.getDuration() < 30)
                    player.addPotionEffect(new PotionEffect(PotionEffectType.DARKNESS, 80, 0, true, false, false));
            }
            if (Math.floorMod(seconds + player.getUniqueId().hashCode(), 8) == 0)
                plugin.lang().actionBar(player, "storm.shelter", "kind", plugin.lang().plain(player, "storm.kind." + kind.key()));
        }
    }

    private void tellWorld(World world, String key, Kind kind, Object... more) {
        for (Player player : world.getPlayers()) {
            Object[] args = new Object[more.length + 2];
            args[0] = "kind"; args[1] = plugin.lang().plain(player, "storm.kind." + kind.key());
            System.arraycopy(more, 0, args, 2, more.length);
            plugin.lang().send(player, key, args);
            if (key.equals("storm.warning")) player.playSound(player.getLocation(), "minecraft:item.elytra.flying", 0.25f, 0.5f);
        }
    }

    public void close() { storms.clear(); warned.clear(); started.clear(); }
}
