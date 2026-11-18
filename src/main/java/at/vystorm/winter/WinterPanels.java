package at.vystorm.winter;

import Vystorm.vystorm_core.panel.PanelOpenResult;
import Vystorm.vystorm_core.API;
import Vystorm.vystorm_core._uiMethods.settings.ConfigEditorInterface;
import Vystorm.vystorm_core.compat.PlatformCompatInterface;
import Vystorm.vystorm_core.panel.PanelSpec;
import Vystorm.vystorm_core.panel.Panels;
import Vystorm.vystorm_core.panel.Result;
import at.vystorm.winter.season.AdventCalendar;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

/** Core panels for the calendar and the admin menu; the chest menus stay as fallback. */
final class WinterPanels {
    private WinterPanels() {}

    static void register(WinterPlugin plugin, API core, CalendarMenu calendar, WinterAdminMenu admin) {
        Panels panels = core.gui().panels().of(plugin);
        PlatformCompatInterface platform = core.compat().platform();
        CalendarPanel calendarPanel = new CalendarPanel(plugin, platform, calendar, calendarActions(plugin, platform, calendar));
        WinterAdminPanel.Actions adminActions = adminActions(plugin, core.settings().config(), admin);
        panels.register(CalendarPanel.NAME, PanelSpec.builder(plugin.lang().ref("calendar-menu"))
                .access(p -> plugin.isEnabled() && !plugin.faulted() && plugin.calendarSettings().enabled() && p.hasPermission(CalendarMenu.PERMISSION))
                .render(calendarPanel::render).fallback(calendar::openChest).build());
        calendar.panelOpener(p -> open(plugin, panels, p, CalendarPanel.NAME));
        panels.register(WinterAdminPanel.NAME, PanelSpec.builder(plugin.lang().ref("admin-title"))
                .access(p -> plugin.isEnabled() && !plugin.faulted() && adminMenuOn(plugin) && p.hasPermission(WinterAdminMenu.PERMISSION))
                .render(ctx -> WinterAdminPanel.panel(ctx, new WinterAdminPanel.Overview(plugin.date(), plugin.phaseName(), plugin.modeName(),
                        plugin.strengthPercent(), plugin.settings().changes(), plugin.clock().enabled(), plugin.snowfallSlider()), adminActions))
                .fallback(admin::openChest).build());
        admin.panelOpener(p -> open(plugin, panels, p, WinterAdminPanel.NAME));
        // Keep the existing seasonal /vmenu entry, whose visibility differs from command access.
        plugin.getServer().getPluginManager().registerEvents(new Listener() {
            @EventHandler public void quit(PlayerQuitEvent event) { calendarPanel.forget(event.getPlayer().getUniqueId()); }
        }, plugin);
    }

    /** admin-menu.enabled (1.24.0); a plugin without switches read yet counts as on. */
    static boolean adminMenuOn(WinterPlugin plugin) {
        WinterSwitches switches = plugin.switches();
        return switches == null || switches.on(WinterSwitches.Feature.ADMIN_MENU);
    }

    static boolean open(WinterPlugin plugin, Panels panels, Player player, String name) {
        try { return panels.open(player, name) != PanelOpenResult.NONE; }
        catch (RuntimeException failure) {
            plugin.getLogger().fine("Core panel did not open; using chest menu: " + failure);
            return false;
        }
    }

    private static Result check(WinterPlugin plugin, Player player, String permission, String denied) {
        if (!player.hasPermission(permission)) return Result.error(plugin.lang().plain(player, denied));
        if (!plugin.isEnabled() || !player.isOnline()) return Result.error(plugin.lang().plain(player, "panel-unavailable"));
        if (plugin.faulted()) return Result.error(plugin.lang().plain(player, "faulted"));
        return Result.ok();
    }

    static CalendarPanel.Actions calendarActions(WinterPlugin plugin, PlatformCompatInterface platform, CalendarMenu menu) {
        return new CalendarPanel.Actions() {
            public Result check(Player player) {
                Result allowed = WinterPanels.check(plugin, player, CalendarMenu.PERMISSION, "calendar-denied");
                if (!allowed.success()) return allowed;
                return plugin.calendarSettings().enabled() ? Result.ok() : Result.error(plugin.lang().plain(player, at.vystorm.winter.WinterSwitches.OFF_MESSAGE));
            }
            public Result openDoor(Player player, int day) {
                Result allowed = check(player);
                if (!allowed.success()) return allowed;
                if (day < 1 || day > AdventCalendar.DOORS) return Result.error(plugin.lang().plain(player, "panel-unavailable"));
                if (!plugin.settings().includes(player.getWorld())) return Result.error(plugin.lang().plain(player, "calendar-world"));
                var date = menu.today(player);
                if (AdventCalendar.state(day, date, menu.opened(player, date.getYear()).contains(day), plugin.calendarSettings().catchUp()) != AdventCalendar.Door.READY)
                    return Result.error(plugin.lang().plain(player, "panel-unavailable"));
                menu.openDoor(player, day);
                return plugin.faulted() ? Result.error(plugin.lang().plain(player, "faulted")) : Result.ok();
            }
            public void gifts(Player player) {
                Result allowed = check(player);
                if (allowed.success()) plugin.claim(player);
                else platform.sendMessage(player, net.kyori.adventure.text.Component.text(allowed.message()));
            }
        };
    }

    static WinterAdminPanel.Actions adminActions(WinterPlugin plugin, ConfigEditorInterface config, WinterAdminMenu menu) {
        return new WinterAdminPanel.Actions() {
            public Result check(Player player) {
                Result allowed = WinterPanels.check(plugin, player, WinterAdminMenu.PERMISSION, "denied");
                if (!allowed.success() || adminMenuOn(plugin)) return allowed;
                return Result.error(plugin.lang().plain(player, WinterSwitches.OFF_MESSAGE));
            }
            private Result outcome(Player player) { return plugin.faulted() ? Result.error(plugin.lang().plain(player, "faulted")) : Result.ok(); }
            public Result run(Player player, String action) {
                Result allowed = check(player);
                if (!allowed.success()) return allowed;
                switch (action) {
                    case "auto" -> plugin.commandResume(player);
                    case "winter" -> plugin.commandSetDate(player, plugin.settings().calendar().nextPeak(plugin.date()).toString());
                    case "thaw" -> plugin.commandThaw(player);
                    case "next" -> {
                        if (!plugin.clock().enabled()) return Result.error(plugin.lang().plain(player, "panel-unavailable"));
                        plugin.nextSeason(player);
                    }
                    default -> { return Result.error(plugin.lang().plain(player, "panel-unavailable")); }
                }
                return outcome(player);
            }
            public Result date(Player player, String date) {
                Result allowed = check(player);
                if (!allowed.success()) return allowed;
                if (WinterPlugin.parseDate(date) == null) return Result.error(plugin.lang().plain(player, "invalid-date", "input", date));
                plugin.commandSetDate(player, date);
                return outcome(player);
            }
            public Result speed(Player player, double speed) {
                Result allowed = check(player);
                if (!allowed.success()) return allowed;
                if (!Double.isFinite(speed) || speed < WinterAdminMenu.MIN_SPEED || speed > WinterAdminMenu.MAX_SPEED)
                    return Result.error(plugin.lang().plain(player, "panel-unavailable"));
                menu.applySpeed(player, (float) speed);
                return outcome(player);
            }
            public Result snowfall(Player player, double amount) {
                Result allowed = check(player);
                if (!allowed.success()) return allowed;
                if (!Double.isFinite(amount) || amount < 0 || amount > at.vystorm.winter.world.Snowfall.MAX_AMOUNT)
                    return Result.error(plugin.lang().plain(player, "panel-unavailable"));
                menu.applySnowfall(player, (float) amount);
                return outcome(player);
            }
            public void settings(Player player) {
                if (check(player).success()) config.open(player, plugin.getName());
            }
            public void lamp(Player player) { if (check(player).success()) plugin.lamps().toggle(player); }
        };
    }
}
