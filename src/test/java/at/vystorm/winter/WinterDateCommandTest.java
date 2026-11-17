package at.vystorm.winter;

import Vystorm.vystorm_core.lang.Lang;
import at.vystorm.winter.season.SeasonCalendar;
import java.time.LocalDate;
import java.time.MonthDay;
import java.util.List;
import org.bukkit.command.CommandSender;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class WinterDateCommandTest {
    private final SeasonCalendar calendar = new SeasonCalendar(MonthDay.of(11, 15), MonthDay.of(12, 24),
            MonthDay.of(2, 1), MonthDay.of(3, 1));


    @Test void parsesOnlyStrictIsoDates() {
        assertEquals(LocalDate.of(2026, 12, 24), WinterPlugin.parseDate("2026-12-24"));
        assertNull(WinterPlugin.parseDate("24.12.2026"));
        assertNull(WinterPlugin.parseDate("2026-13-01"));
        assertNull(WinterPlugin.parseDate("2027-02-29"));
    }

    @Test void suggestsTodayAndSeasonBoundariesInOrder() {
        assertEquals(List.of("2026-10-27", "2026-11-15", "2026-12-24", "2027-02-01", "2027-03-01"),
                WinterPlugin.dateSuggestions(calendar, LocalDate.of(2026, 10, 27), ""));
        assertEquals(List.of("2027-02-01", "2027-03-01"),
                WinterPlugin.dateSuggestions(calendar, LocalDate.of(2026, 10, 27), "2027"));
        assertEquals(List.of("2026-11-15", "2026-12-24", "2027-02-01", "2027-03-01"),
                WinterPlugin.dateSuggestions(calendar, LocalDate.of(2026, 11, 15), ""));
    }
}
