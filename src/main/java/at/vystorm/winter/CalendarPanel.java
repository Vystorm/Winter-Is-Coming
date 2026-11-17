package at.vystorm.winter;

import Vystorm.vystorm_core.panel.Panel;
import Vystorm.vystorm_core.panel.PanelContext;
import Vystorm.vystorm_core.panel.Result;
import Vystorm.vystorm_core.panel.Ui;
import at.vystorm.winter.season.AdventCalendar;
import at.vystorm.winter.season.AdventCalendar.Door;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.BiConsumer;
import org.bukkit.Material;
import org.bukkit.entity.Player;

/** Pure calendar rendering; rewards and preview rules remain in CalendarMenu. */
final class CalendarPanel {
    static final String NAME = "advent-calendar";
    record Row(int day, Door state, Material reward, int amount, String rewardTranslationKey) {
        String key() { return Integer.toString(day); }
        String textKey() { return "calendar-door-" + state.name().toLowerCase(Locale.ROOT); }
        Material icon() {
            return switch (state) {
                case LOCKED -> Material.IRON_DOOR;
                case READY -> Material.CHEST;
                case OPENED -> reward;
                case MISSED -> Material.GRAY_STAINED_GLASS_PANE;
            };
        }
    }
    record Overview(LocalDate date, List<Row> rows, int opened, int waiting, int pending,
                    boolean preview, String selected) {}
    interface Actions {
        Result check(Player player);
        Result openDoor(Player player, int day);
        void gifts(Player player);
    }

    private final WinterPlugin plugin;
    private final Vystorm.vystorm_core.compat.PlatformCompatInterface platform;
    private final CalendarMenu menu;
    private final Actions actions;
    private final Map<UUID, String> selected = new HashMap<>();

    CalendarPanel(WinterPlugin plugin, Vystorm.vystorm_core.compat.PlatformCompatInterface platform, CalendarMenu menu, Actions actions) {
        this.plugin = plugin;
        this.platform = platform;
        this.menu = menu;
        this.actions = actions;
    }

    void forget(UUID player) { selected.remove(player); }

    Panel render(PanelContext ctx) {
        Player player = ctx.player();
        LocalDate date = menu.today(player);
        var opened = menu.opened(player, date.getYear());
        var settings = plugin.calendarSettings();
        List<Row> rows = java.util.stream.IntStream.rangeClosed(1, AdventCalendar.DOORS).mapToObj(day -> {
            var reward = AdventCalendar.rewardFor(day, settings.rewards());
            return new Row(day, AdventCalendar.state(day, date, opened.contains(day), settings.catchUp()), reward.material(), reward.amount(),
                    platform.translationKey(reward.material()));
        }).toList();
        var overview = new Overview(date, rows, opened.size(), AdventCalendar.waiting(date, opened, settings.catchUp()),
                plugin.faulted() ? 0 : plugin.gifts().pending(player.getUniqueId()), menu.previewing(player), selected.get(player.getUniqueId()));
        return panel(ctx, overview, actions, (p, key) -> selected.put(p.getUniqueId(), key));
    }

    static Panel panel(PanelContext ctx, Overview o, Actions actions, BiConsumer<Player, String> select) {
        String chosen = o.rows().stream().filter(r -> r.key().equals(o.selected())).map(Row::key).findFirst()
                .orElseGet(() -> o.rows().stream().filter(r -> r.state() == Door.READY).map(Row::key).findFirst().orElse("1"));
        Ui.Tabs tabs = Ui.tabs("doors");
        for (int page = 0; page < 3; page++) {
            int first = page * 8 + 1, last = first + 7;
            List<Row> rows = o.rows().stream().filter(r -> r.day() >= first && r.day() <= last).toList();
            Row row = rows.stream().filter(r -> r.key().equals(chosen)).findFirst().orElse(rows.getFirst());
            Ui.ListView list = Ui.list("days-" + page).selected(row.key()).grow(2).onSelect((a, key) -> {
                Result allowed = actions.check(a.player());
                if (!allowed.success()) return allowed;
                if (rows.stream().noneMatch(r -> r.key().equals(key))) return Result.error(ctx.text("panel-unavailable"));
                select.accept(a.player(), key);
                a.refresh();
                return Result.ok();
            });
            for (Row item : rows) list.item(Ui.listItem(item.key(), ctx.text(item.textKey(), "day", item.day())).icon(Ui.item(item.icon())));
            Ui.Box detail = Ui.col().grow(3).gap(6).children(
                    Ui.heading(ctx.text(row.textKey(), "day", row.day())).level(2),
                    Ui.badge(ctx.text("panel-door-" + row.state().name().toLowerCase(Locale.ROOT))),
                    Ui.text(description(ctx, o, row)));
            if (row.state() == Door.OPENED) detail.child(Ui.item(row.reward()).count(row.amount()).size(32));
            detail.child(Ui.button("open-" + row.day()).label(ctx.text("calendar-open-button")).icon(Ui.item(Material.CHEST)).primary()
                    .disabled(row.state() != Door.READY).onClick(a -> {
                        Result result = actions.openDoor(a.player(), row.day());
                        if (result.success()) a.refresh();
                        return result;
                    }));
            tabs.tab("days-" + first, ctx.text("panel-days", "first", first, "last", last), Ui.row().children(list, detail));
            if (rows.stream().anyMatch(r -> r.key().equals(chosen))) tabs.select("days-" + first);
        }
        Panel panel = Panel.page(ctx.text("calendar-title", "year", o.date().getYear()))
                .subtitle(ctx.text("calendar-info", "date", o.date())).icon(Ui.item(Material.CHEST)).size(Panel.Size.LARGE)
                .children(Ui.progress(o.opened(), AdventCalendar.DOORS).id("calendar-progress")
                        .label(ctx.text("calendar-info-lore", "opened", o.opened(), "doors", AdventCalendar.DOORS, "waiting", o.waiting())), tabs)
                .footer(Ui.button("gifts").label(ctx.text("calendar-gifts", "pending", o.pending()))
                        .tooltip(ctx.text("calendar-gifts-lore")).icon(Ui.item(Material.CHEST_MINECART)).onClick(a -> {
                            Result allowed = actions.check(a.player());
                            if (!allowed.success()) return allowed;
                            a.closeAndThen(actions::gifts);
                            return Result.ok();
                        }));
        if (o.preview()) panel.child(Ui.text(ctx.text("calendar-test-note", "date", o.date())).id("preview-note").style(Ui.TextStyle.WARN));
        return panel;
    }

    private static Object description(PanelContext ctx, Overview o, Row row) {
        return switch (row.state()) {
            case LOCKED -> ctx.text("calendar-door-locked-lore", "date", LocalDate.of(o.date().getYear(), 12, row.day()));
            case READY -> ctx.text("calendar-door-ready-lore");
            case MISSED -> ctx.text("calendar-door-missed-lore");
            case OPENED -> ctx.component("calendar-door-opened-lore", "amount", row.amount(), "item",
                    net.kyori.adventure.text.Component.translatable(row.rewardTranslationKey()));
        };
    }
}
