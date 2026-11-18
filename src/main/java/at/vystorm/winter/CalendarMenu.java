package at.vystorm.winter;

import Vystorm.vystorm_core.API;
import Vystorm.vystorm_core._uiMethods.autoGui.GuiCallbacks;
import Vystorm.vystorm_core._uiMethods.menu.MenuCategory;
import at.vystorm.winter.season.AdventCalendar;
import at.vystorm.winter.season.AdventCalendar.Door;
import java.time.LocalDate;
import java.util.*;
import net.kyori.adventure.text.Component;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

/** Daily Advent calendar with a panel opener and the original chest fallback. */
final class CalendarMenu implements Listener {
    static final String PERMISSION = "winter.calendar";
    private static final int INFO_SLOT = 49, GIFTS_SLOT = 53;
    private static final int[] LAYOUT = AdventCalendar.layout();
    private final WinterPlugin plugin;
    private final API core;
    CalendarMenu(WinterPlugin plugin, API core) {
        this.plugin = plugin;
        this.core = core;
    }
    private java.util.function.Predicate<Player> panelOpener = player -> false;
    void panelOpener(java.util.function.Predicate<Player> opener) {
        panelOpener = opener;
    }

    /** Core main menu entry (November and December); /winter calendar works without it. */
    void register() {
        try {
            var lang = plugin.lang();
            core.gui().registerMenu(plugin, "advent-calendar", lang.ref("calendar-menu"), Material.CHEST,
                    p -> plugin.calendarSettings().enabled() && p.hasPermission(PERMISSION) && today(p).getMonthValue() >= 11,
                    this::open, MenuCategory.WORLD.group(), lang.ref("calendar-menu-desc"));
        } catch (RuntimeException unavailable) {
            plugin.getLogger().info("Core menu entry for the Advent calendar unavailable: " + unavailable);
        }
    }

    /** Real calendar date: administrative date previews never open real doors. */
    LocalDate today() {
        return LocalDate.now(plugin.settings().zone());
    }
    /** Admins see the date set in the admin menu; their doors are remembered only until the date changes and give nothing. */
    private final Map<UUID, Set<Integer>> preview = new HashMap<>();
    boolean previewing(Player player) {
        return plugin.manualDate() && player.hasPermission(WinterAdminMenu.PERMISSION);
    }
    LocalDate today(Player player) {
        return previewing(player) ? plugin.date() : today();
    }
    Set<Integer> opened(Player player, int year) {
        return previewing(player) ? preview.getOrDefault(player.getUniqueId(), Set.of()) : plugin.calendarStore().opened(player.getUniqueId(), year);
    }
    void clearPreview() {
        preview.clear();
    }

    void open(Player player) {
        if (!plugin.calendarSettings().enabled()) {
            plugin.lang().send(player, at.vystorm.winter.WinterSwitches.OFF_MESSAGE);
            return;
        }
        if (!player.hasPermission(PERMISSION)) {
            plugin.lang().send(player, "calendar-denied");
            return;
        }
        if (panelOpener.test(player)) return;
        openChest(player);
    }

    void openChest(Player player) {
        var s = plugin.calendarSettings();
        if (!s.enabled()) {
            plugin.lang().send(player, at.vystorm.winter.WinterSwitches.OFF_MESSAGE);
            return;
        }
        if (!player.hasPermission(PERMISSION)) {
            plugin.lang().send(player, "calendar-denied");
            return;
        }
        if (previewing(player)) plugin.lang().send(player, "calendar-test-note", "date", plugin.date());
        LocalDate today = today(player);
        Inventory[] holder = new Inventory[1];
        holder[0] = core.gui().createInventory(plugin, null, player.getUniqueId(), AdventCalendar.MENU_SIZE,
                plugin.lang().text(player, "calendar-title", "year", today.getYear()), new GuiCallbacks() {
                    @Override public void click(InventoryClickEvent event) {
                        event.setCancelled(true);
                        int slot = event.getRawSlot();
                        int day = dayAt(slot);
                        if (day > 0) Bukkit.getScheduler().runTask(plugin, () -> {
                            if (!player.isOnline()) return;
                            openDoor(player, day);
                            if (!plugin.faulted()) fill(holder[0], player);
                        });
                        else if (slot == GIFTS_SLOT) Bukkit.getScheduler().runTask(plugin, () -> {
                            if (!player.isOnline()) return;
                            player.closeInventory();
                            plugin.claim(player);
                        });
                    }
                });
        fill(holder[0], player);
        player.openInventory(holder[0]);
    }

    /** Door number in a menu slot, 0 for any other slot. */
    static int dayAt(int slot) {
        for (int i = 0; i < LAYOUT.length; i++) if (LAYOUT[i] == slot) return i + 1;
        return 0;
    }

    private void fill(Inventory inventory, Player player) {
        var lang = plugin.lang();
        var s = plugin.calendarSettings();
        LocalDate today = today(player);
        int year = today.getYear();
        Set<Integer> opened = opened(player, year);
        for (int slot = 0; slot < 45; slot++) inventory.setItem(slot, blank(slot % 2 == 0 ? Material.RED_STAINED_GLASS_PANE : Material.GREEN_STAINED_GLASS_PANE));
        for (int slot = 45; slot < 54; slot++) inventory.setItem(slot, blank(Material.WHITE_STAINED_GLASS_PANE));
        for (int day = 1; day <= AdventCalendar.DOORS; day++) {
            Door door = AdventCalendar.state(day, today, opened.contains(day), s.catchUp());
            AdventCalendar.Reward reward = AdventCalendar.rewardFor(day, s.rewards());
            ItemStack item = switch (door) {
                case LOCKED -> lang.lore(lang.name(new ItemStack(Material.IRON_DOOR, day), player, "calendar-door-locked", "day", day),
                        player, "calendar-door-locked-lore", "date", LocalDate.of(year, 12, day));
                case READY -> glint(lang.lore(lang.name(new ItemStack(Material.CHEST, day), player, "calendar-door-ready", "day", day), player, "calendar-door-ready-lore"));
                case OPENED -> lang.lore(lang.name(new ItemStack(reward.material()), player, "calendar-door-opened", "day", day),
                        player, "calendar-door-opened-lore", "amount", reward.amount(), "item", Component.translatable(core.compat().platform().translationKey(reward.material())));
                case MISSED -> lang.lore(lang.name(new ItemStack(Material.GRAY_STAINED_GLASS_PANE, day), player, "calendar-door-missed", "day", day), player, "calendar-door-missed-lore");
            };
            inventory.setItem(LAYOUT[day - 1], item);
        }
        inventory.setItem(INFO_SLOT, lang.lore(lang.name(new ItemStack(Material.CLOCK), player, "calendar-info", "date", today),
                player, "calendar-info-lore", "opened", opened.size(), "doors", AdventCalendar.DOORS, "waiting", AdventCalendar.waiting(today, opened, s.catchUp())));
        int pending = plugin.faulted() ? 0 : plugin.gifts().pending(player.getUniqueId());
        inventory.setItem(GIFTS_SLOT, lang.lore(lang.name(new ItemStack(pending > 0 ? Material.CHEST_MINECART : Material.MINECART), player, "calendar-gifts", "pending", pending), player, "calendar-gifts-lore"));
    }

    /** Credits the door's gift (stable ID, so it can never be doubled) and delivers it right away. */
    void openDoor(Player player, int day) {
        if (!player.isOnline()) return;
        var lang = plugin.lang();
        var s = plugin.calendarSettings();
        if (plugin.faulted()) {
            lang.send(player, "faulted");
            return;
        }
        if (!s.enabled() || !player.hasPermission(PERMISSION)) return;
        if (!plugin.settings().includes(player.getWorld())) {
            lang.send(player, "calendar-world");
            return;
        }
        LocalDate today = today(player);
        int year = today.getYear();
        UUID id = player.getUniqueId();
        Door door = AdventCalendar.state(day, today, opened(player, year).contains(day), s.catchUp());
        if (door == Door.LOCKED) lang.send(player, "calendar-door-locked-click", "day", day, "date", LocalDate.of(year, 12, day));
        if (door != Door.READY) return;
        AdventCalendar.Reward reward = AdventCalendar.rewardFor(day, s.rewards());
        if (previewing(player)) {
            preview.computeIfAbsent(id, k -> new TreeSet<>()).add(day);
            lang.send(player, "calendar-test-opened", "day", day, "amount", reward.amount(), "item", Component.translatable(core.compat().platform().translationKey(reward.material())));
            return;
        }
        try {
            plugin.gifts().credit(id, AdventCalendar.giftId(year, day), new ItemStack(reward.material(), reward.amount()));
            plugin.calendarStore().open(id, year, day);
            if (plugin.missions() != null) plugin.missions().contribute(player, CommunityMissions.Objective.ADVENT_DOOR);
            if (plugin.faulted()) {
                lang.send(player, "faulted");
                return;
            }
            switch (plugin.gifts().claim(player)) {
                case DELIVERED -> {
                    lang.send(player, "calendar-opened", "day", day, "amount", reward.amount(), "item", Component.translatable(core.compat().platform().translationKey(reward.material())));
                    player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.6f, 1.4f);
                }
                case FULL, BUSY, EMPTY -> lang.send(player, "calendar-kept", "day", day);
                case RECOVERY_REQUIRED -> lang.send(player, "gift-recovery");
            }
        } catch (RuntimeException failure) {
            plugin.storageFailure(failure);
            lang.send(player, "faulted");
        }
    }

    @EventHandler public void join(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (player.isOnline()) remind(player);
        }, 60);
    }

    /** Clickable chat hint a moment after joining while doors are waiting. */
    void remind(Player player) {
        var s = plugin.calendarSettings();
        if (!s.enabled() || !s.joinReminder() || plugin.faulted() || !player.hasPermission(PERMISSION)) return;
        LocalDate today = today();
        int waiting = AdventCalendar.waiting(today, plugin.calendarStore().opened(player.getUniqueId(), today.getYear()), s.catchUp());
        if (waiting == 0) return;
        var lang = plugin.lang();
        core.compat().platform().sendMessage(player, lang.component(player, "calendar-reminder", "count", waiting).append(Component.space())
                .append(core.message().chat().button(lang.ref("calendar-open-button"), lang.ref("calendar-open-hover"), p -> open(p))));
    }

    private static ItemStack blank(Material material) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.setHideTooltip(true);
        item.setItemMeta(meta);
        return item;
    }
    private static ItemStack glint(ItemStack item) {
        ItemMeta meta = item.getItemMeta();
        meta.setEnchantmentGlintOverride(true);
        item.setItemMeta(meta);
        return item;
    }
}
