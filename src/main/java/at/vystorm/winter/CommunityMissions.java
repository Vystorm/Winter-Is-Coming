package at.vystorm.winter;

import at.vystorm.winter.storage.MissionStore;
import java.util.*;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.ItemStack;

/** Seasonal community goals. Real accepted gameplay events contribute; administrative dates never do. */
public final class CommunityMissions implements Listener {
    public enum Objective { SNOWBALL_HIT, HOT_MEAL, ADVENT_DOOR }
    public record Definition(String id, Objective objective, int goal, String reward, int amount) {
        public Definition {
            if (id == null || !id.matches("[a-z0-9_-]{1,32}") || objective == null || goal < 1 || goal > 1_000_000
                    || reward == null || !reward.matches("[A-Z_]{1,80}") || amount < 1 || amount > 64)
                throw new IllegalArgumentException("Invalid community mission");
        }
    }
    public record Settings(boolean enabled, List<Definition> goals) {
        public Settings { goals = List.copyOf(goals); }
        public static Settings read(ConfigurationSection config) {
            ConfigurationSection root = config.getConfigurationSection("community-missions.goals");
            List<Definition> goals = new ArrayList<>();
            if (root == null) {
                goals.add(new Definition("snowball_festival", Objective.SNOWBALL_HIT, 100, "COOKIE", 8));
                goals.add(new Definition("warm_supper", Objective.HOT_MEAL, 50, "GOLDEN_CARROT", 4));
                goals.add(new Definition("advent_together", Objective.ADVENT_DOOR, 24, "EMERALD", 2));
            } else {
                if (root.getKeys(false).size() > 16) throw new IllegalArgumentException("At most 16 community missions");
                for (String id : root.getKeys(false)) {
                    ConfigurationSection entry = root.getConfigurationSection(id);
                    if (entry == null) throw new IllegalArgumentException("Mission must be a section: " + id);
                    Material material = Material.matchMaterial(entry.getString("reward", "COOKIE"));
                    int amount = entry.getInt("amount", 1);
                    if (material == null || material.isAir() || !material.isItem() || amount > material.getMaxStackSize())
                        throw new IllegalArgumentException("Invalid mission reward: " + id);
                    goals.add(new Definition(id, Objective.valueOf(entry.getString("objective", "").toUpperCase(Locale.ROOT)),
                            entry.getInt("goal", 100), material.name(), amount));
                }
            }
            return new Settings(config.getBoolean("community-missions.enabled", true), goals);
        }
    }
    private final WinterPlugin plugin;
    private final MissionStore store;
    private Settings settings;
    CommunityMissions(WinterPlugin plugin, MissionStore store, Settings settings) {
        this.plugin = plugin; this.store = store; this.settings = settings;
    }
    void configure(Settings value) { settings = value; }
    boolean active(Player player) {
        return settings.enabled() && !plugin.faulted() && plugin.state().data().manualDate == null
                && !plugin.state().data().thaw && player.hasPermission("winter.missions") && plugin.controls(player.getWorld());
    }
    public void contribute(Player player, Objective objective) {
        if (!active(player) || player.getGameMode() != org.bukkit.GameMode.SURVIVAL && player.getGameMode() != org.bukkit.GameMode.ADVENTURE) return;
        try {
            for (Definition definition : settings.goals()) {
                if (store.progress(plugin.season().seasonYear(), definition).definition().objective() != objective) continue;
                boolean completed = store.advance(plugin.season().seasonYear(), definition);
                if (completed) {
                    for (Player online : Bukkit.getOnlinePlayers()) {
                        if (online.hasPermission("winter.missions")) plugin.lang().send(online, "missions-complete", "mission", definition.id());
                        credit(online);
                    }
                }
            }
        } catch (RuntimeException failure) { plugin.storageFailure(failure); }
    }
    /** Rewards are durable and idempotent through the existing winter pouch, including later joiners. */
    void credit(Player player) {
        if (!settings.enabled() || plugin.faulted() || plugin.state().data().manualDate != null || !player.hasPermission("winter.missions")) return;
        for (MissionStore.Progress progress : store.completed(plugin.season().seasonYear())) {
            Material material = Material.matchMaterial(progress.definition().reward());
            if (material == null || material.isAir() || !material.isItem() || progress.definition().amount() > material.getMaxStackSize())
                throw new IllegalStateException("Stored mission reward is unavailable; retain mission data");
            plugin.gifts().credit(player.getUniqueId(), progress.rewardId(), new ItemStack(material, progress.definition().amount()));
        }
    }
    void show(Player player) {
        if (!player.hasPermission("winter.missions")) { plugin.lang().send(player,"missions-denied"); return; }
        if (!settings.enabled()) { plugin.lang().send(player,at.vystorm.winter.WinterSwitches.OFF_MESSAGE); return; }
        plugin.lang().send(player,"missions-title","season",plugin.season().seasonYear());
        for (Definition definition : settings.goals()) {
            MissionStore.Progress progress=store.progress(plugin.season().seasonYear(),definition);
            Definition fixed=progress.definition();
            plugin.lang().send(player,"missions-row","mission",fixed.id(),"objective",plugin.lang().plain(plugin.lang().language(player),"missions-objective-"+fixed.objective().name().toLowerCase(Locale.ROOT)),"progress",progress.count(),"goal",fixed.goal());
        }
        if (!active(player)) plugin.lang().send(player,"missions-paused");
        credit(player);
        plugin.lang().send(player,"missions-claim");
    }
    @EventHandler public void join(PlayerJoinEvent event) {
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (!event.getPlayer().isOnline()) return;
            try { credit(event.getPlayer()); } catch (RuntimeException failure) { plugin.storageFailure(failure); }
        });
    }
}
