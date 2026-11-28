package at.vystorm.winter;

import Vystorm.vystorm_core.panel.Panel;
import Vystorm.vystorm_core.panel.PanelContext;
import Vystorm.vystorm_core.panel.Result;
import Vystorm.vystorm_core.panel.Ui;
import at.vystorm.winter.world.Snowfall;
import java.time.LocalDate;
import java.util.Locale;
import org.bukkit.Material;
import org.bukkit.entity.Player;

/** Administration rendered entirely from a snapshot; actions re-check live state. */
final class WinterAdminPanel {
    static final String NAME = "winter-admin";
    record Overview(LocalDate date, String phase, String mode, long strength, int speed, boolean seasons, int snowfall, Building building) {
        Overview(LocalDate date, String phase, String mode, long strength, int speed, boolean seasons, int snowfall) {
            this(date, phase, mode, strength, speed, seasons, snowfall, null);
        }
    }
    /** Snow-free building (1.28.0): areas in use, the cap and the halos; null while build mode is off. */
    record Building(int areas, int max, int halos) {}
    static Building building(WinterBuilding building) {
        if (building == null || !building.enabled()) return null;
        var areas = building.areas();
        return new Building(areas.counted(), areas.settings().maxAreas(), areas.halos());
    }
    interface Actions {
        Result check(Player player);
        Result run(Player player, String action);
        Result date(Player player, String date);
        Result speed(Player player, double speed);
        Result snowfall(Player player, double amount);
        void settings(Player player);
        void lamp(Player player);
    }

    static Panel panel(PanelContext ctx, Overview o, Actions actions) {
        Ui.Box controls = Ui.col().gap(6).children(
                Ui.kv().id("status")
                        .row(ctx.text("panel-phase"), ctx.text("panel-phase-" + o.phase().toLowerCase(Locale.ROOT)))
                        .row(ctx.text("panel-mode"), ctx.text("panel-mode-" + o.mode().toLowerCase(Locale.ROOT))),
                Ui.progress(o.strength(), 100).id("strength").label(ctx.text("panel-strength", "value", o.strength())),
                Ui.row().wrap().children(button(ctx, actions, "auto", Material.COMPASS), button(ctx, actions, "winter", Material.SNOW_BLOCK)),
                Ui.form("set-date", ctx.text("admin-speed-apply")).children(
                        Ui.input("date", ctx.text("admin-date-question")).value(o.date().toString()).maxLength(10))
                        .onSubmit((a, values) -> {
                            Result result = actions.date(a.player(), String.valueOf(values.getOrDefault("date", "")).trim());
                            if (result.success()) a.refresh();
                            return result;
                        }),
                button(ctx, actions, "thaw", Material.LAVA_BUCKET).danger().confirm(ctx.text("admin-thaw"), ctx.text("admin-thaw-lore")));
        if (o.seasons()) controls.child(button(ctx, actions, "next", Material.OAK_SAPLING));
        Ui.Box performance = Ui.col().gap(6).children(
                Ui.text(ctx.text("admin-speed-text")),
                Ui.number("speed", ctx.text("admin-speed-label"))
                        .range(WinterAdminMenu.MIN_SPEED, WinterAdminMenu.MAX_SPEED, 4)
                        .value(Math.clamp(o.speed(), WinterAdminMenu.MIN_SPEED, WinterAdminMenu.MAX_SPEED)).decimals(0)
                        .onChange((a, value) -> {
                            Result result = actions.speed(a.player(), value);
                            if (result.success()) a.refresh();
                            return result;
                        }),
                Ui.text(ctx.text("admin-snowfall-text")),
                Ui.number("snowfall", ctx.text("admin-snowfall-label"))
                        .range(0, Snowfall.MAX_AMOUNT, 10)
                        .value(Math.clamp(o.snowfall(), 0, Snowfall.MAX_AMOUNT)).decimals(0)
                        .onChange((a, value) -> {
                            Result result = actions.snowfall(a.player(), value);
                            if (result.success()) a.refresh();
                            return result;
                        }));
        var tabs = Ui.tabs("admin-tabs").tab("season", ctx.text("panel-season"), controls)
                .tab("performance", ctx.text("admin-speed"), performance);
        // Snow-free building (1.28.0): what players use now, and where a permanent snow-free place comes from.
        if (o.building() != null) tabs.tab("building", ctx.text("admin-build"), Ui.col().gap(6).children(
                Ui.text(ctx.text("admin-build-text", "areas", o.building().areas(), "max", o.building().max(), "halos", o.building().halos())).id("build-text"),
                Ui.text(ctx.text("admin-build-permanent")).id("build-permanent").style(Ui.TextStyle.MUTED)));
        return Panel.page(ctx.text("admin-title")).subtitle(ctx.text("admin-status", "date", o.date()))
                .icon(Ui.item(Material.SNOW_BLOCK)).size(Panel.Size.MEDIUM)
                .children(tabs)
                .footer(Ui.button("settings").label(ctx.text("admin-settings")).icon(Ui.item(Material.COMPARATOR)).ghost().onClick(a -> {
                    Result allowed = actions.check(a.player());
                    if (!allowed.success()) return allowed;
                    a.closeAndThen(actions::settings);
                    return Result.ok();
                }), Ui.button("lamp").label(ctx.text("admin-lamp")).icon(Ui.item(Material.YELLOW_STAINED_GLASS)).onClick(a -> {
                    Result allowed = actions.check(a.player());
                    if (!allowed.success()) return allowed;
                    a.closeAndThen(actions::lamp);
                    return Result.ok();
                }));
    }

    private static Ui.Button button(PanelContext ctx, Actions actions, String key, Material icon) {
        return Ui.button(key).label(ctx.text("admin-" + key)).tooltip(ctx.text("admin-" + key + "-lore")).icon(Ui.item(icon)).onClick(a -> {
            Result result = actions.run(a.player(), key);
            if (result.success()) a.refresh();
            return result;
        });
    }
}
