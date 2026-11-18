package at.vystorm.winter;

import Vystorm.vystorm_core._advancedMethods.placeholderHandler.PlaceholderInterface;
import Vystorm.vystorm_core._advancedMethods.placeholderHandler.PlaceholderNamespace;
import Vystorm.vystorm_core._advancedMethods.placeholderHandler.PlaceholderValue;
import Vystorm.vystorm_core.lang.Lang;
import at.vystorm.winter.season.SeasonCalendar;
import java.time.LocalDate;
import java.util.Set;
import java.util.UUID;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

/**
 * The {@code winter} placeholders (1.24.0, Vystorm Core 0.65.0): {@code %vystorm_winter_phase%}, {@code {winter:cold}}
 * and so on. Values are plain text, numbers without formatting. A value whose feature is switched off is empty.
 * Season values read volatile fields and answer on any thread; values of a player's cold, snowball scores and Advent
 * doors read server-thread maps and only answer on the server thread.
 */
final class WinterPlaceholders {
    static final String NAMESPACE = "winter";

    /** What the values read, looked up at call time so a reload is seen at once. */
    interface Source {
        SeasonCalendar.State season();
        String holiday();
        LocalDate date();
        Set<UUID> activeWorlds();
        /** Days to the next peak, thaw or end; null when there is none. */
        Integer daysToMilestone();
        boolean holidaysOn();
        boolean coldOn();
        boolean reserveOn();
        double cold(UUID player);
        double warmth(UUID player);
        /** Cold-blocking pieces, 0 to 4. */
        int protection(Player player);
        boolean snowballOn();
        /** Hits, wins and rank (0 = none) in the current season. */
        int[] snowball(UUID player);
        boolean calendarOn();
        /** Doors opened this year and doors waiting today. */
        int[] calendar(UUID player);
    }

    private WinterPlaceholders() {}

    static PlaceholderNamespace register(PlaceholderInterface placeholders, Plugin owner, Lang lang, Source s) {
        PlaceholderNamespace ns = placeholders.register(owner, NAMESPACE);
        add(ns, lang, PlaceholderValue.global("phase", () -> s.season() == null ? null : s.season().phase()).asyncSafe());
        add(ns, lang, PlaceholderValue.global("strength", () -> s.season() == null ? null : Math.round(s.season().intensity() * 100)).asyncSafe());
        add(ns, lang, PlaceholderValue.global("holiday", () -> !s.holidaysOn() ? "" : s.holiday()).asyncSafe());
        add(ns, lang, PlaceholderValue.global("season_year", () -> s.season() == null ? null : s.season().seasonYear()).asyncSafe());
        add(ns, lang, PlaceholderValue.global("date", () -> s.date() == null ? null : s.date().toString()));
        add(ns, lang, PlaceholderValue.global("days_to_next_milestone", () -> {
            Integer days = s.daysToMilestone();
            return days == null ? "" : days;
        }));
        add(ns, lang, PlaceholderValue.player("active", player -> s.activeWorlds().contains(player.getWorld().getUID())).asyncSafe());
        add(ns, lang, PlaceholderValue.viewer("cold", player -> !s.coldOn() ? "" : Math.round(s.cold(player.getUniqueId()) * 100)));
        add(ns, lang, PlaceholderValue.viewer("warmth_seconds", player -> !s.coldOn() || !s.reserveOn() ? "" : (int) Math.ceil(s.warmth(player.getUniqueId()))));
        add(ns, lang, PlaceholderValue.player("cold_protection", player -> !s.coldOn() ? "" : s.protection(player) * 25));
        add(ns, lang, PlaceholderValue.viewer("snowball_hits", player -> !s.snowballOn() ? "" : s.snowball(player.getUniqueId())[0]));
        add(ns, lang, PlaceholderValue.viewer("snowball_wins", player -> !s.snowballOn() ? "" : s.snowball(player.getUniqueId())[1]));
        add(ns, lang, PlaceholderValue.viewer("snowball_rank", player -> !s.snowballOn() ? "" : s.snowball(player.getUniqueId())[2]));
        add(ns, lang, PlaceholderValue.viewer("calendar_opened", player -> !s.calendarOn() ? "" : s.calendar(player.getUniqueId())[0]));
        add(ns, lang, PlaceholderValue.viewer("calendar_waiting", player -> !s.calendarOn() ? "" : s.calendar(player.getUniqueId())[1]));
        return ns;
    }

    private static void add(PlaceholderNamespace ns, Lang lang, PlaceholderValue value) {
        ns.add(lang == null ? value : value.description(lang.ref("placeholder." + value.key())));
    }

    /** The keys in registration order (for the language files and the README). */
    static final java.util.List<String> KEYS = java.util.List.of("phase", "strength", "holiday", "season_year", "date",
            "days_to_next_milestone", "active", "cold", "warmth_seconds", "cold_protection", "snowball_hits", "snowball_wins",
            "snowball_rank", "calendar_opened", "calendar_waiting");
}
