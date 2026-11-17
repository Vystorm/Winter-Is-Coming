package at.vystorm.winter.season;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.LocalDate;
import java.time.MonthDay;
import java.util.List;
import org.junit.jupiter.api.Test;

class SeasonBoundariesTest {
    private final SeasonCalendar calendar = new SeasonCalendar(MonthDay.of(11, 15), MonthDay.of(12, 24),
            MonthDay.of(2, 1), MonthDay.of(3, 1));

    @Test
    void offSeasonListsTheUpcomingSeason() {
        assertEquals(List.of(LocalDate.of(2026, 11, 15), LocalDate.of(2026, 12, 24), LocalDate.of(2027, 2, 1), LocalDate.of(2027, 3, 1)),
                calendar.boundaries(LocalDate.of(2026, 10, 27)));
        assertEquals(LocalDate.of(2026, 11, 15), calendar.nextStart(LocalDate.of(2026, 10, 27)));
        assertEquals(LocalDate.of(2026, 11, 15), calendar.nextStart(LocalDate.of(2026, 3, 2)));
    }

    @Test
    void inSeasonListsTheCurrentSeasonAndNextStartIsAYearLater() {
        assertEquals(LocalDate.of(2026, 11, 15), calendar.boundaries(LocalDate.of(2027, 1, 10)).get(0));
        assertEquals(LocalDate.of(2027, 3, 1), calendar.boundaries(LocalDate.of(2027, 3, 1)).get(3));
        assertEquals(LocalDate.of(2027, 11, 15), calendar.nextStart(LocalDate.of(2026, 11, 15)));
        assertEquals(LocalDate.of(2027, 11, 15), calendar.nextStart(LocalDate.of(2027, 3, 1)));
    }
}
