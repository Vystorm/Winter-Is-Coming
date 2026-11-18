package at.vystorm.winter;

import Vystorm.vystorm_core.API;
import at.vystorm.winter.fun.SnowballDuels;
import at.vystorm.winter.fun.SnowballDuels.Challenge;
import at.vystorm.winter.fun.SnowballDuels.Duel;
import at.vystorm.winter.storage.SnowballScores;
import java.util.*;
import net.kyori.adventure.text.Component;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.entity.Snowball;
import org.bukkit.event.*;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;

/**
 * Snowball fights in winter worlds: hits on other players count for a seasonal leaderboard and chill the target a
 * little; two players can fight a timed duel (first to N hits). Hits are read from the damage event in Core's stage OBSERVE, so
 * PvP protection that cancels the hit also cancels the point.
 */
final class SnowballFight implements Listener, AutoCloseable {
    static final String PERMISSION = "winter.snowball";
    private static final int TOP = 5;
    private final WinterPlugin plugin;
    private final SnowballScores scores;
    private final API core;
    private final SnowballDuels duels = new SnowballDuels();
    SnowballFight(WinterPlugin plugin, SnowballScores scores, API core) {
        this.plugin = plugin;
        this.core = core;
        this.scores = scores;
    }

    SnowballScores scores() {
        return scores;
    }
    SnowballDuels duels() {
        return duels;
    }
    int season() {
        return plugin.season().seasonYear();
    }

    /** Whether snowball fights count for this player right now. */
    boolean active(Player player) {
        return plugin.snowballSettings().enabled() && !plugin.faulted() && player.isOnline()
                && (player.getGameMode() == GameMode.SURVIVAL || player.getGameMode() == GameMode.ADVENTURE)
                && player.hasPermission(PERMISSION) && plugin.controls(player.getWorld()) && !plugin.season().phase().equals("THAW");
    }

    /** Hits are counted through Core's stages in OBSERVE: only a hit that no plugin cancelled counts. */
    void handleDamageEvents(Vystorm.vystorm_core._coreMethods.eventRegulator.EventStages stages) {
        stages.handle(plugin, EntityDamageByEntityEvent.class, Vystorm.vystorm_core._coreMethods.eventRegulator.EventStage.OBSERVE, this::hit);
    }

    /** Stage OBSERVE, registered in {@link #handleDamageEvents}. */
    public void hit(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Snowball snowball) || !(snowball.getShooter() instanceof Player thrower) || !(event.getEntity() instanceof Player target)) return;
        if (!active(thrower) || !active(target)) return;
        tick(); // Resolve the deadline before a hit can change the timed result.
        if (!duels.countHit(thrower.getUniqueId(), target.getUniqueId(), System.currentTimeMillis())) return;
        scores.hit(thrower.getUniqueId(), thrower.getName(), season());
        if (plugin.missions() != null) plugin.missions().contribute(thrower, CommunityMissions.Objective.SNOWBALL_HIT);
        if (plugin.coldLoop() != null) plugin.coldLoop().chill(target, plugin.snowballSettings().chill());
        var lang = plugin.lang();
        Optional<Duel> duel = duels.hitInDuel(thrower.getUniqueId(), target.getUniqueId());
        if (duel.isEmpty()) {
            lang.actionBar(thrower, "snowball-hit", "hits", scores.row(thrower.getUniqueId(), season()).hits());
            return;
        }
        Duel d = duel.get();
        if (d.end() == null) {
            lang.actionBar(thrower, "snowball-duel-score", "you", d.score(thrower.getUniqueId()), "them", d.score(target.getUniqueId()), "player", target.getName());
            lang.actionBar(target, "snowball-duel-score", "you", d.score(target.getUniqueId()), "them", d.score(thrower.getUniqueId()), "player", thrower.getName());
            thrower.playSound(thrower.getLocation(), Sound.ENTITY_ARROW_HIT_PLAYER, 0.5f, 1.6f);
        }
        else finish(d);
    }

    @EventHandler public void quit(PlayerQuitEvent event) {
        tick();
        duels.leave(event.getPlayer().getUniqueId()).ifPresent(this::finish);
    }

    /** Runs every second: ends duels whose time is up. */
    void tick() {
        for (Duel duel : duels.expire(System.currentTimeMillis())) finish(duel);
    }

    /** Announces the result to both duelists and counts the win. */
    private void finish(Duel duel) {
        var lang = plugin.lang();
        UUID winner = duel.winner();
        String first = name(duel.first()), second = name(duel.second());
        if (winner != null) scores.win(winner, name(winner), season());
        for (UUID id : List.of(duel.first(), duel.second())) {
            Player player = Bukkit.getPlayer(id);
            if (player == null) continue;
            UUID other = duel.opponent(id);
            String score = duel.score(id) + ":" + duel.score(other);
            if (winner == null) lang.send(player, "snowball-duel-draw", "player", name(other), "score", score);
            else if (duel.end() == SnowballDuels.End.SURRENDER) lang.send(player, winner.equals(id) ? "snowball-surrender-won" : "snowball-surrender-lost", "player", name(other));
            else if (duel.end() == SnowballDuels.End.FORFEIT) lang.send(player, "snowball-duel-forfeit", "player", name(other));
            else if (winner.equals(id)) {
                lang.send(player, "snowball-duel-won", "player", name(other), "score", score);
                player.playSound(player.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.7f, 1.2f);
            } else lang.send(player, "snowball-duel-lost", "player", name(other), "score", score);
        }
        plugin.getLogger().fine("Snowball duel " + first + " vs " + second + " ended: " + duel.end());
    }

    /** Challenge (or accept the challenge of) another player; used by /winter duel, the chat button and the player menu. */
    void challenge(Player from, Player to) {
        var lang = plugin.lang();
        var s = plugin.snowballSettings();
        if (!plugin.duelsOn()) {
            lang.send(from, WinterSwitches.OFF_MESSAGE);
            return;
        }
        if (!from.hasPermission(PERMISSION)) {
            lang.send(from, "snowball-denied");
            return;
        }
        if (!to.isOnline()) {
            lang.send(from, "snowball-offline");
            return;
        }
        if (!active(from) || !active(to)) {
            lang.send(from, "snowball-duel-world", "player", to.getName());
            return;
        }
        Challenge result = duels.challenge(from.getUniqueId(), to.getUniqueId(), System.currentTimeMillis(), s.duelHits(), s.duelSeconds() * 1000L);
        switch (result) {
            case SELF -> lang.send(from, "snowball-duel-self");
            case BUSY -> lang.send(from, "snowball-duel-busy");
            case PENDING -> lang.send(from, "snowball-duel-pending", "player", to.getName());
            case SENT -> {
                lang.send(from, "snowball-duel-sent", "player", to.getName(), "seconds", SnowballDuels.CHALLENGE_MILLIS / 1000);
                Component invite = lang.component(to, "snowball-duel-invite", "player", from.getName(), "hits", s.duelHits());
                UUID challenger = from.getUniqueId();
                invite = invite.append(Component.space()).append(core.message().chat().button(lang.ref("snowball-accept-button"), lang.ref("snowball-accept-hover"), PERMISSION, p -> {
                    Player back = Bukkit.getPlayer(challenger);
                    if (back == null) lang.send(p, "snowball-offline");
                    else challenge(p, back);
                }));
                core.compat().platform().sendMessage(to, invite);
                to.playSound(to.getLocation(), Sound.BLOCK_NOTE_BLOCK_BELL, 0.7f, 1.2f);
            }
            case STARTED -> {
                for (Player player : List.of(from, to)) {
                    lang.send(player, "snowball-duel-start", "player", (player == from ? to : from).getName(), "hits", s.duelHits(), "seconds", s.duelSeconds());
                    topUp(player, s.duelSnowballs());
                    player.playSound(player.getLocation(), Sound.EVENT_RAID_HORN, 0.4f, 1.5f);
                }
            }
        }
    }

    /** Allow leaving even if fights were disabled or the player moved out of winter. */
    void surrender(Player player) {
        if (!player.hasPermission(PERMISSION)) {
            plugin.lang().send(player, "snowball-denied");
            return;
        }
        duels.surrender(player.getUniqueId(), System.currentTimeMillis()).ifPresentOrElse(this::finish,
                () -> plugin.lang().send(player, "snowball-no-duel"));
    }

    /** Fills the inventory up to the configured number of snowballs; never drops anything. */
    private static void topUp(Player player, int wanted) {
        if (wanted <= 0) return;
        int have = 0;
        for (ItemStack item : player.getInventory().getStorageContents()) if (item != null && item.getType() == Material.SNOWBALL) have += item.getAmount();
        if (have < wanted) player.getInventory().addItem(new ItemStack(Material.SNOWBALL, wanted - have));
    }

    /** /winter snowball: own score and the season's best players. */
    void stats(Player player) {
        var lang = plugin.lang();
        if (!plugin.snowballSettings().enabled()) {
            lang.send(player, WinterSwitches.OFF_MESSAGE);
            return;
        }
        if (!player.hasPermission(PERMISSION)) {
            lang.send(player, "snowball-denied");
            return;
        }
        int season = season();
        SnowballScores.Row own = scores.row(player.getUniqueId(), season);
        lang.send(player, "snowball-stats", "hits", own.hits(), "wins", own.wins(), "rank", rankText(player, season));
        List<SnowballScores.Row> top = scores.top(TOP, season);
        if (top.isEmpty()) {
            lang.send(player, "snowball-top-empty");
            return;
        }
        lang.send(player, "snowball-top-header", "season", season);
        for (int i = 0; i < top.size(); i++) lang.send(player, "snowball-top-line", "rank", i + 1, "name", top.get(i).name(), "hits", top.get(i).hits(), "wins", top.get(i).wins());
        duels.duelOf(player.getUniqueId()).ifPresent(d -> lang.send(player, "snowball-duel-running", "player", name(d.opponent(player.getUniqueId())),
                "you", d.score(player.getUniqueId()), "them", d.score(d.opponent(player.getUniqueId())),
                "seconds", Math.max(0, (d.endsAt() - System.currentTimeMillis()) / 1000)));
    }

    private String rankText(Player player, int season) {
        int rank = scores.rank(player.getUniqueId(), season);
        return rank == 0 ? "-" : "#" + rank;
    }

    /** Shift-right-click player menu entry. */
    void registerPlayerMenu() {
        SnowballMenuEntry.register(plugin, core.gui().playerMenu(), this);
    }

    void save() {
        try {
            scores.saveIfDirty();
        } catch (RuntimeException failure) {
            plugin.storageFailure(failure);
        }
    }

    private static String name(UUID id) {
        Player online = Bukkit.getPlayer(id);
        if (online != null) return online.getName();
        String offline = Bukkit.getOfflinePlayer(id).getName();
        return offline == null ? id.toString().substring(0, 8) : offline;
    }

    @Override public void close() {
        save();
        duels.clear();
    }
}
