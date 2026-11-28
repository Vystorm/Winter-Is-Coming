package at.vystorm.winter;

import Vystorm.vystorm_core.panel.Panel;
import Vystorm.vystorm_core.panel.PanelAction;
import Vystorm.vystorm_core.panel.PanelContext;
import Vystorm.vystorm_core.panel.Result;
import Vystorm.vystorm_core.panel.Ui;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import org.bukkit.Material;
import org.bukkit.entity.Player;

/**
 * The player panel "Winter" behind a plain {@code /winter} (1.28.0): season and warmth status, the own falling-snow
 * switch, the community missions with the claim button and a snowball duel challenge. Rendered from a snapshot; every
 * action re-checks the live state and runs the same code as its command.
 */
final class WinterPlayerPanel {
    static final String NAME = "winter";

    /** One community mission row; {@code objective} is already in the player's language. */
    record Mission(String id, String objective, int progress, int goal) {}

    /**
     * What the panel shows. {@code snowfall} is null when the falling snow is off on the server or the player may not
     * switch it; {@code missions} is null when the missions are off or not allowed; {@code picker} says whether the
     * duel button opens Core's player picker (else a name field).
     */
    record Overview(LocalDate date, String phase, long strength, boolean coldOn, long cold, int warmth, int protection,
                    Boolean snowfall, List<Mission> missions, boolean missionsPaused, int pending, boolean claim,
                    boolean duel, boolean picker, Build build) {
        /** Without the snow-free building switch (tests of the parts before 1.28.0). */
        Overview(LocalDate date, String phase, long strength, boolean coldOn, long cold, int warmth, int protection,
                 Boolean snowfall, List<Mission> missions, boolean missionsPaused, int pending, boolean claim,
                 boolean duel, boolean picker) {
            this(date, phase, strength, coldOn, cold, warmth, protection, snowfall, missions, missionsPaused, pending, claim, duel, picker, null);
        }
    }

    /**
     * The snow-free building switch (1.28.0): on, the radius, and while off the minutes the spot stays snow-free (0 =
     * no area). Null in {@link Overview} when it is not offered.
     */
    record Build(boolean on, int radius, long minutesLeft) {
        String text(PanelContext ctx) {
            if (on) return ctx.text("player-panel-build-on-text", "radius", radius);
            if (minutesLeft > 0) return ctx.text("player-panel-build-after-text", "minutes", minutesLeft);
            return ctx.text("player-panel-build-off-text", "radius", radius);
        }
    }

    interface Actions {
        Result snowfall(Player player, boolean on);
        /** The snow-free building switch. */
        Result build(Player player, boolean on);
        void claim(Player player);
        /** The duel button: Core's player picker (only called when {@link Overview#picker()}). */
        Result pickDuel(PanelAction action);
        /** The typed name of the fallback field. */
        Result duel(Player player, String name);
    }

    private WinterPlayerPanel() {}

    static Panel panel(PanelContext ctx, Overview o, Actions actions) {
        Ui.Box status = Ui.col().gap(6).children(
                Ui.kv().id("season")
                        .row(ctx.text("panel-phase"), ctx.text("panel-phase-" + o.phase().toLowerCase(Locale.ROOT)))
                        .row(ctx.text("player-panel-date"), o.date().toString()),
                Ui.progress(o.strength(), 100).id("strength").label(ctx.text("panel-strength", "value", o.strength())));
        if (o.coldOn()) status.child(Ui.text(ctx.text("warmth-overview", "cold", o.cold(), "seconds", o.warmth(), "protection", o.protection() * 25)).id("warmth"));
        if (o.snowfall() != null) status.child(Ui.toggle("snowfall", ctx.text("player-panel-snowfall")).value(o.snowfall())
                .description(ctx.text("player-panel-snowfall-text")).onChange((a, on) -> {
                    Result result = actions.snowfall(a.player(), on);
                    if (result.success()) a.refresh();
                    return result;
                }));
        if (o.build() != null) status.child(Ui.toggle("build", ctx.text("player-panel-build")).value(o.build().on())
                .description(o.build().text(ctx)).onChange((a, on) -> {
                    Result result = actions.build(a.player(), on);
                    if (result.success()) a.refresh();
                    return result;
                }));

        Panel panel = Panel.page(ctx.text("player-panel-title")).subtitle(ctx.text("player-panel-subtitle"))
                .icon(Ui.item(Material.SNOWBALL)).size(Panel.Size.MEDIUM).child(status);

        if (o.missions() != null) {
            Ui.Box missions = Ui.col().id("missions").gap(4).child(Ui.heading(ctx.text("player-panel-missions")).level(2));
            for (Mission m : o.missions())
                missions.child(Ui.progress(m.progress(), m.goal()).id("mission-" + m.id())
                        .label(ctx.text("missions-row", "mission", m.id(), "objective", m.objective(),
                                "progress", m.progress(), "goal", m.goal())));
            if (o.missionsPaused()) missions.child(Ui.text(ctx.text("missions-paused")).id("missions-paused").style(Ui.TextStyle.MUTED));
            panel.child(missions);
        }

        Ui.Box buttons = Ui.row().wrap().gap(4);
        if (o.claim()) buttons.child(Ui.button("claim").label(ctx.text("player-panel-claim", "pending", o.pending()))
                .tooltip(ctx.text("player-panel-claim-text")).icon(Ui.item(o.pending() > 0 ? Material.CHEST_MINECART : Material.MINECART)).primary()
                .onClick(a -> {
                    a.closeAndThen(actions::claim);
                    return Result.ok();
                }));
        if (o.duel() && o.picker()) buttons.child(Ui.button("duel").label(ctx.text("player-panel-duel"))
                .tooltip(ctx.text("player-panel-duel-text")).icon(Ui.item(Material.SNOWBALL)).onClick(actions::pickDuel));
        panel.child(buttons);
        if (o.duel() && !o.picker()) panel.child(Ui.input("duel-name", ctx.text("player-panel-duel-name")).maxLength(16).submitOnEnter()
                .placeholder(ctx.text("player-panel-duel-text")).onChange((a, name) -> actions.duel(a.player(), name == null ? "" : name.trim())));
        return panel;
    }
}
