package at.vystorm.winter;

import static org.junit.jupiter.api.Assertions.*;

import Vystorm.vystorm_core._advancedMethods.placeholderHandler.PlaceholderTesting;
import at.vystorm.winter.season.SeasonCalendar;
import java.time.LocalDate;
import java.util.Set;
import java.util.UUID;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;

/** The winter placeholders resolve through Core's real parser, and a switched-off feature gives an empty value. */
class WinterPlaceholdersTest {
    /** A plugin state that tests switch feature by feature. */
    private static final class Fake implements WinterPlaceholders.Source {
        boolean holidays = true, cold = true, reserve = true, snowball = true, calendar = true;
        public SeasonCalendar.State season() { return new SeasonCalendar.State(0.5, "FROST", 2026, "ADVENT", 2); }
        public String holiday() { return "ADVENT"; }
        public LocalDate date() { return LocalDate.of(2026, 12, 5); }
        public Set<UUID> activeWorlds() { return Set.of(); }
        public Integer daysToMilestone() { return 19; }
        public boolean holidaysOn() { return holidays; }
        public boolean coldOn() { return cold; }
        public boolean reserveOn() { return reserve; }
        public double cold(UUID player) { return 0.42; }
        public double warmth(UUID player) { return 12.2; }
        public int protection(Player player) { return 2; }
        public boolean snowballOn() { return snowball; }
        public int[] snowball(UUID player) { return new int[]{7, 2, 3}; }
        public boolean calendarOn() { return calendar; }
        public int[] calendar(UUID player) { return new int[]{4, 1}; }
    }

    private final PlaceholderTesting testing = PlaceholderTesting.detached();
    private final Plugin owner = PlaceholderTesting.plugin("WinterIsComing");
    private final OfflinePlayer alex = PlaceholderTesting.viewer("Alex");
    private final Fake state = new Fake();

    private String resolve(String text) {
        return testing.resolve(alex, text);
    }

    @Test void givenRegisteredValues_whenResolved_thenPlainTextInBothForms() {
        WinterPlaceholders.register(testing.placeholders(), owner, null, state);
        assertEquals("FROST 50", resolve("%vystorm_winter_phase% %vystorm_winter_strength%"));
        assertEquals("ADVENT 2026 2026-12-05 19", resolve("{winter:holiday} {winter:season_year} {winter:date} {winter:days_to_next_milestone}"));
        assertEquals("42 13", resolve("{winter:cold} {winter:warmth_seconds}"));
        assertEquals("7 2 3", resolve("%vystorm_winter_snowball_hits% %vystorm_winter_snowball_wins% %vystorm_winter_snowball_rank%"));
        assertEquals("4 1", resolve("{winter:calendar_opened} {winter:calendar_waiting}"));
        assertEquals(WinterPlaceholders.KEYS.size(), testing.placeholders().namespace("winter").orElseThrow().values().size());
    }

    @Test void givenFeaturesOff_whenResolved_thenEmptyValues() {
        WinterPlaceholders.register(testing.placeholders(), owner, null, state);
        state.holidays = false;
        state.cold = false;
        state.snowball = false;
        state.calendar = false;
        assertEquals("[][][][][][]", resolve("[{winter:holiday}][{winter:cold}][{winter:warmth_seconds}][{winter:snowball_hits}][{winter:calendar_opened}][{winter:calendar_waiting}]"));
        state.cold = true;
        state.reserve = false;
        assertEquals("[42][]", resolve("[{winter:cold}][{winter:warmth_seconds}]"));
        assertEquals("FROST", resolve("{winter:phase}"), "season values belong to the calendar and stay");
    }

    @Test void givenOtherThread_whenResolved_thenOnlySeasonValuesAnswer() {
        WinterPlaceholders.register(testing.placeholders(), owner, null, state);
        assertEquals("FROST 50 ADVENT {winter:cold}", testing.resolveOffThread(alex, "{winter:phase} {winter:strength} {winter:holiday} {winter:cold}"));
    }

    @Test void givenPluginDisabled_whenResolved_thenPlaceholdersStayAsWritten() {
        WinterPlaceholders.register(testing.placeholders(), owner, null, state);
        testing.disable(owner);
        assertEquals("{winter:phase}", resolve("{winter:phase}"));
    }
}
