package at.vystorm.winter;

import Vystorm.vystorm_core.API;
import Vystorm.vystorm_core._uiMethods.autoGui.GuiCallbacks;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

/** Winter administration with a panel opener and the original chest fallback. */
final class WinterAdminMenu {
    static final String PERMISSION = "winter.admin";
    private final WinterPlugin plugin;
    private final API core;
    WinterAdminMenu(WinterPlugin plugin, API core) {
        this.plugin = plugin;
        this.core = core;
    }
    private java.util.function.Predicate<Player> panelOpener = player -> false;
    void panelOpener(java.util.function.Predicate<Player> opener) {
        panelOpener = opener;
    }

    void open(Player player) {
        if (!player.hasPermission(PERMISSION)) {
            plugin.lang().send(player, "denied");
            return;
        }
        if (panelOpener.test(player)) return;
        openChest(player);
    }

    void openChest(Player player) {
        if (!player.hasPermission(PERMISSION)) {
            plugin.lang().send(player, "denied");
            return;
        }
        Map<Integer, Consumer<Player>> actions = new HashMap<>();
        Inventory[] holder = new Inventory[1];
        holder[0] = core.gui().createInventory(plugin, null, player.getUniqueId(), 27, plugin.lang().text(player, "admin-title"), new GuiCallbacks() {
            @Override public void click(InventoryClickEvent event) {
                event.setCancelled(true);
                Consumer<Player> action = actions.get(event.getRawSlot());
                if (action != null) Bukkit.getScheduler().runTask(plugin, () -> {
                    if (player.isOnline() && player.hasPermission(PERMISSION)) action.accept(player);
                });
            }
        });
        Inventory inv = holder[0];
        var lang = plugin.lang();
        inv.setItem(4, lang.lore(lang.name(new ItemStack(Material.CLOCK), player, "admin-status", "date", plugin.date()), player, "admin-status-lore", "phase", plugin.phaseName(), "mode", plugin.modeName(), "strength", plugin.strengthPercent()));
        String dateTools = WinterSwitches.Feature.DATE_TOOLS.path();
        button(player, inv, actions, 10, Material.COMPASS, "admin-auto", p -> {
            if (!plugin.needs(p, dateTools)) plugin.commandResume(p);
            open(p);
        });
        button(player, inv, actions, 11, Material.SNOW_BLOCK, "admin-winter", p -> {
            if (!plugin.needs(p, dateTools)) plugin.commandSetDate(p, plugin.settings().calendar().nextPeak(plugin.date()).toString());
            open(p);
        });
        button(player, inv, actions, 12, Material.WRITABLE_BOOK, "admin-date", p -> {
            if (!plugin.needs(p, dateTools)) askDate(p);
        });
        button(player, inv, actions, 13, Material.LAVA_BUCKET, "admin-thaw", p -> {
            plugin.commandThaw(p);
            open(p);
        });
        if (plugin.clock().enabled()) button(player, inv, actions, 14, Material.OAK_SAPLING, "admin-next", p -> {
            plugin.nextSeason(p);
            open(p);
        });
        button(player, inv, actions, 15, Material.COMPARATOR, "admin-settings", p -> {
            p.closeInventory();
            core.settings().config().open(p, plugin.getName());
        });
        inv.setItem(22, lang.lore(lang.name(new ItemStack(Material.POWDER_SNOW_BUCKET), player, "admin-speed"), player, "admin-speed-lore", "value", plugin.settings().changes()));
        actions.put(22, this::askSpeed);
        button(player, inv, actions, 16, Material.YELLOW_STAINED_GLASS, "admin-lamp", p -> {
            p.closeInventory();
            if (!plugin.needs(p, "lamps.enabled")) plugin.lamps().toggle(p);
        });
        player.openInventory(inv);
    }
    private void button(Player player, Inventory inv, Map<Integer, Consumer<Player>> actions, int slot, Material icon, String key, Consumer<Player> action) {
        var lang = plugin.lang();
        inv.setItem(slot, lang.lore(lang.name(new ItemStack(icon), player, key), player, key + "-lore"));
        actions.put(slot, action);
    }
    static final int MIN_SPEED = 4, MAX_SPEED = 512;
    /** Changes per second; columns searched follow so the budget can be spent. */
    static int[] speedSettings(float value) {
        int speed = Math.clamp(Math.round(value), MIN_SPEED, MAX_SPEED);
        return new int[]{speed, Math.clamp(speed * 6, 24, 8192)};
    }
    private void askSpeed(Player player) {
        player.closeInventory();
        var lang = plugin.lang();
        var input = Vystorm.vystorm_core.compat.dialog.DialogInput.numberRange("speed", lang.component(player, "admin-speed-label"), MIN_SPEED, MAX_SPEED)
                .step(4).initial((float)Math.clamp(plugin.settings().changes(), MIN_SPEED, MAX_SPEED)).width(300).build();
        core.dialogs().open(plugin, player, new Vystorm.vystorm_core.compat.dialog.DialogRequest(lang.component(player, "admin-speed"),
                java.util.List.of(Vystorm.vystorm_core.compat.dialog.DialogBody.plainMessage(lang.component(player, "admin-speed-text"), 300)), java.util.List.of(input),
                java.util.List.of(new Vystorm.vystorm_core.compat.dialog.DialogButton(lang.component(player, "admin-speed-apply"), (p, r) -> {
                    Float value = r.getFloat("speed");
                    if (value == null || !p.hasPermission(PERMISSION)) return;
                    applySpeed(p, value);
                    open(p);
                })), p -> p.isOnline() && p.hasPermission(PERMISSION), () -> open(player), lang.text(player, "admin-back"), 1));
    }
    void applySpeed(Player player, float value) {
        if (!plugin.isEnabled() || plugin.faulted() || !player.isOnline() || !player.hasPermission(PERMISSION) || !Float.isFinite(value)) return;
        int[] s = speedSettings(value);
        plugin.getConfig().set("performance.period-ticks", 20);
        plugin.getConfig().set("performance.changes-per-cycle", s[0]);
        plugin.getConfig().set("performance.columns-per-cycle", s[1]);
        plugin.saveConfig();
        plugin.commandReload(Bukkit.getConsoleSender());
        if (!plugin.faulted()) plugin.lang().send(player, "admin-speed-set", "value", s[0]);
    }
    /** Admin slider "Snowfall amount": 0 switches the falling snow off, 1..400 switches it on with that many flakes per second. */
    void applySnowfall(Player player, float value) {
        if (!plugin.isEnabled() || plugin.faulted() || !player.isOnline() || !player.hasPermission(PERMISSION) || !Float.isFinite(value)) return;
        int amount = at.vystorm.winter.world.Snowfall.sliderAmount(value);
        plugin.getConfig().set("snowfall.enabled", amount > 0);
        if (amount > 0) plugin.getConfig().set("snowfall.amount", amount);
        plugin.saveConfig();
        plugin.commandReload(Bukkit.getConsoleSender());
        if (!plugin.faulted()) plugin.lang().send(player, amount > 0 ? "admin-snowfall-set" : "admin-snowfall-unset", "value", amount);
    }
    private void askDate(Player player) {
        player.closeInventory();
        var lang = plugin.lang();
        core.dialogs().prompt(plugin, player, lang.ref("admin-date"), lang.ref("admin-date-question"), plugin.date().toString(),
                text -> {
                    plugin.commandSetDate(player, text.trim());
                    open(player);
                }, () -> open(player), p -> p.isOnline() && p.hasPermission(PERMISSION));
    }
}
