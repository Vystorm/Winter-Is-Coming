package at.vystorm.winter;

import Vystorm.vystorm_core.API;
import Vystorm.vystorm_core.CoreApi;
import Vystorm.vystorm_core._uiMethods.autoGui.GuiCallbacks;
import Vystorm.vystorm_core._uiMethods.menu.MenuCategory;
import Vystorm.vystorm_core.panel.PanelAction;
import Vystorm.vystorm_core.panel.Result;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Predicate;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

/**
 * The player menu "Winter" (1.28.0): opened by a plain {@code /winter} and from {@code /vmenu}. As a Core panel
 * ({@link WinterPlayerPanel}) where it can be shown, else this 27-slot chest with the same parts. The duel challenge
 * uses Core's player picker when Core has it ({@code player-picker}, 0.71.0, through {@link WinterPicks}), else a typed
 * name. Every button runs the same code as its command.
 */
final class WinterPlayerMenu {
    static final int SLOT_STATUS = 4, SLOT_WARMTH = 10, SLOT_SNOWFALL = 12, SLOT_MISSIONS = 14, SLOT_CLAIM = 16, SLOT_DUEL = 22, SLOT_BUILD = 20;
    private final WinterPlugin plugin;
    private final API core;
    private Predicate<Player> panelOpener = player -> false;

    WinterPlayerMenu(WinterPlugin plugin, API core) {
        this.plugin = plugin;
        this.core = core;
    }

    void panelOpener(Predicate<Player> opener) { panelOpener = opener; }

    /** Core main menu entry; {@code /winter} works without it. */
    void register() {
        try {
            var lang = plugin.lang();
            core.gui().registerMenu(plugin, "winter", lang.ref("player-panel-title"), Material.SNOWBALL,
                    p -> plugin.isEnabled(), this::open, MenuCategory.WORLD.group(), lang.ref("player-panel-subtitle"));
        } catch (RuntimeException unavailable) {
            plugin.getLogger().info("Core menu entry for the Winter menu unavailable: " + unavailable);
        }
    }

    /** {@code /winter} for players: the panel, else the chest. */
    void open(Player player) {
        if (panelOpener.test(player)) return;
        openChest(player);
    }

    static boolean pickerAvailable() {
        try { return CoreApi.has("player-picker"); } catch (LinkageError olderCore) { return false; }
    }

    // ---------------------------------------------------------------- the snapshot both routes show

    WinterPlayerPanel.Overview overview(Player player) {
        boolean faulted = plugin.faulted();
        var coldLoop = plugin.coldLoop();
        boolean coldOn = !faulted && coldLoop != null && plugin.cold().enabled();
        var snowfall = plugin.snowfall();
        Boolean snowfallOn = snowfall != null && snowfall.settings().enabled() && player.hasPermission("winter.snowfall")
                ? !snowfall.optedOut(player) : null;
        var missions = plugin.missions();
        List<WinterPlayerPanel.Mission> rows = null;
        boolean paused = false;
        if (!faulted && missions != null) {
            try {
                rows = missions.rows(player);
                paused = rows != null && !missions.active(player);
            } catch (RuntimeException failure) {
                plugin.storageFailure(failure);
                rows = null;
            }
        }
        boolean claim = !plugin.faulted() && player.hasPermission("winter.claim");
        int pending = claim ? plugin.gifts().pending(player.getUniqueId()) : 0;
        boolean duel = !plugin.faulted() && plugin.snowballFight() != null && plugin.duelsOn() && player.hasPermission(SnowballFight.PERMISSION);
        return new WinterPlayerPanel.Overview(plugin.date(), plugin.phaseName(), plugin.strengthPercent(), coldOn,
                coldOn ? Math.round(coldLoop.level(player.getUniqueId()) * 100) : 0,
                coldOn ? (int) Math.ceil(coldLoop.reserve(player.getUniqueId())) : 0,
                coldOn ? coldLoop.protection(player) : 0,
                snowfallOn, rows, paused, pending, claim, duel, pickerAvailable(),
                faulted || plugin.building() == null ? null : plugin.building().overview(player));
    }

    // ---------------------------------------------------------------- actions (panel and chest)

    WinterPlayerPanel.Actions actions() {
        return new WinterPlayerPanel.Actions() {
            public Result snowfall(Player player, boolean on) {
                var snowfall = plugin.snowfall();
                if (snowfall == null || !snowfall.settings().enabled() || !player.hasPermission("winter.snowfall"))
                    return Result.error(plugin.lang().plain(player, "panel-unavailable"));
                if (snowfall.optedOut(player) == on) plugin.commandSnowfall(player);
                return Result.ok();
            }
            public Result build(Player player, boolean on) {
                var building = plugin.building();
                if (building == null || !building.available(player)) return Result.error(plugin.lang().plain(player, "panel-unavailable"));
                WinterBuilding.Answer answer = building.set(player, on);
                if (!answer.changed()) return on ? Result.error(plugin.lang().plain(player, answer.key(), answer.placeholders())) : Result.ok();
                plugin.lang().send(player, answer.key(), answer.placeholders());
                return Result.ok();
            }
            public void claim(Player player) { plugin.claim(player); }
            public Result pickDuel(PanelAction action) {
                if (!pickerAvailable() || plugin.snowballFight() == null) return Result.error(plugin.lang().plain(action.player(), "panel-unavailable"));
                return WinterPicks.duel(core, plugin, plugin.snowballFight(), action);
            }
            public Result duel(Player player, String name) {
                if (name.isEmpty()) return Result.ok();
                plugin.commandDuel(player, name);
                return Result.ok();
            }
        };
    }

    // ---------------------------------------------------------------- the chest

    /** The plain chest fallback (Core without {@code panel-fallback-context}, or no panels at all). */
    void openChest(Player player) {
        openChest(player, pickerAvailable() && plugin.snowballFight() != null
                ? p -> WinterPicks.duelToChest(core, plugin, plugin.snowballFight(), p, this::openChest) : null);
    }

    /**
     * Draws the chest; {@code pickDuel} starts the player picker for the duel button (it decides where the pick comes
     * back to), null means a typed name.
     */
    void openChest(Player player, Consumer<Player> pickDuel) {
        var lang = plugin.lang();
        WinterPlayerPanel.Overview o;
        try {
            o = overview(player);
        } catch (RuntimeException failure) {
            plugin.storageFailure(failure);
            lang.send(player, "faulted");
            return;
        }
        Inventory[] holder = new Inventory[1];
        holder[0] = core.gui().createInventory(plugin, null, player.getUniqueId(), 27, lang.text(player, "player-panel-title"), new GuiCallbacks() {
            @Override public void click(InventoryClickEvent event) {
                event.setCancelled(true);
                if (event.getClickedInventory() != event.getView().getTopInventory()) return;
                int slot = event.getRawSlot();
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (player.isOnline()) chestAction(player, slot, pickDuel);
                });
            }
        });
        for (var e : chestItems(player, o).entrySet()) holder[0].setItem(e.getKey(), e.getValue());
        player.openInventory(holder[0]);
    }

    /** One chest button; package-private for tests. */
    void chestAction(Player player, int slot, Consumer<Player> pickDuel) {
        WinterPlayerPanel.Actions actions = actions();
        switch (slot) {
            case SLOT_SNOWFALL -> {
                var snowfall = plugin.snowfall();
                if (snowfall == null || !snowfall.settings().enabled() || !player.hasPermission("winter.snowfall")) return;
                plugin.commandSnowfall(player);
                openChest(player, pickDuel);
            }
            case SLOT_CLAIM -> {
                if (!player.hasPermission("winter.claim")) return;
                player.closeInventory();
                actions.claim(player);
            }
            case SLOT_DUEL -> {
                if (plugin.snowballFight() == null || !plugin.duelsOn() || !player.hasPermission(SnowballFight.PERMISSION)) return;
                player.closeInventory();
                if (pickDuel != null) pickDuel.accept(player);
                else core.dialogs().prompt(plugin, player, plugin.lang().ref("player-panel-duel"), plugin.lang().ref("player-panel-duel-name"), "",
                        name -> Bukkit.getScheduler().runTask(plugin, () -> {
                            if (player.isOnline()) actions.duel(player, name == null ? "" : name.trim());
                        }), () -> { }, Player::isOnline);
            }
            case SLOT_BUILD -> {
                var building = plugin.building();
                if (building == null || !building.available(player)) return;
                WinterBuilding.Answer answer = building.toggle(player);
                plugin.lang().send(player, answer.key(), answer.placeholders());
                openChest(player, pickDuel);
            }
            default -> { }
        }
    }

    java.util.Map<Integer, ItemStack> chestItems(Player player, WinterPlayerPanel.Overview o) {
        var lang = plugin.lang();
        java.util.Map<Integer, ItemStack> out = new java.util.HashMap<>();
        ItemStack status = lang.name(new ItemStack(Material.SNOW_BLOCK), player, "player-panel-status",
                "phase", lang.plain(player, "panel-phase-" + o.phase().toLowerCase(java.util.Locale.ROOT)), "value", o.strength());
        out.put(SLOT_STATUS, lang.lore(status, player, "player-panel-status-lore", "date", o.date()));
        if (o.coldOn()) out.put(SLOT_WARMTH, lang.name(new ItemStack(Material.CAMPFIRE), player, "warmth-overview",
                "cold", o.cold(), "seconds", o.warmth(), "protection", o.protection() * 25));
        if (o.snowfall() != null) out.put(SLOT_SNOWFALL, lang.lore(lang.name(new ItemStack(o.snowfall() ? Material.LIME_DYE : Material.GRAY_DYE), player,
                o.snowfall() ? "player-panel-snowfall-on" : "player-panel-snowfall-off"), player, "player-panel-snowfall-text"));
        if (o.missions() != null) {
            ItemStack book = lang.name(new ItemStack(Material.BOOK), player, "player-panel-missions");
            List<Component> lore = new ArrayList<>();
            for (WinterPlayerPanel.Mission m : o.missions())
                lore.add(plain(lang.component(player, "missions-row", "mission", m.id(), "objective", m.objective(), "progress", m.progress(), "goal", m.goal())));
            if (o.missionsPaused()) lore.add(plain(lang.component(player, "missions-paused")));
            core.compat().platform().lore(book, lore);
            out.put(SLOT_MISSIONS, book);
        }
        if (o.claim()) out.put(SLOT_CLAIM, lang.lore(lang.name(new ItemStack(o.pending() > 0 ? Material.CHEST_MINECART : Material.MINECART), player,
                "player-panel-claim", "pending", o.pending()), player, "player-panel-claim-text"));
        if (o.build() != null) {
            WinterPlayerPanel.Build b = o.build();
            ItemStack item = lang.name(new ItemStack(b.on() ? Material.BRICKS : b.minutesLeft() > 0 ? Material.LIME_DYE : Material.GRAY_DYE), player,
                    b.on() ? "player-panel-build-on" : "player-panel-build-off", "radius", b.radius());
            out.put(SLOT_BUILD, b.on() ? lang.lore(item, player, "player-panel-build-on-text", "radius", b.radius())
                    : b.minutesLeft() > 0 ? lang.lore(item, player, "player-panel-build-after-text", "minutes", b.minutesLeft())
                    : lang.lore(item, player, "player-panel-build-off-text", "radius", b.radius()));
        }
        if (o.duel()) out.put(SLOT_DUEL, lang.lore(lang.name(new ItemStack(Material.SNOWBALL), player, "player-panel-duel"), player, "player-panel-duel-text"));
        return out;
    }

    private static Component plain(Component line) {
        return line.colorIfAbsent(NamedTextColor.GRAY).decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE);
    }
}
