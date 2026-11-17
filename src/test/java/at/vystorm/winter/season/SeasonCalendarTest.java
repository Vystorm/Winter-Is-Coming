package at.vystorm.winter.season;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.LocalDate;
import java.time.MonthDay;
import org.junit.jupiter.api.Test;

class SeasonCalendarTest {
    private SeasonCalendar calendar() {
        return new SeasonCalendar(MonthDay.of(11, 1), MonthDay.of(12, 1),
                MonthDay.of(2, 1), MonthDay.of(3, 1));
    }

    @Test
    void reportsLinearPhasesAndExactBoundariesAcrossYearRollover() {
        SeasonCalendar calendar = calendar();
        assertState(calendar, LocalDate.of(2025, 11, 1), 0, "FROST", 2025, "NONE", 0);
        assertState(calendar, LocalDate.of(2025, 11, 16), 0.5, "FROST", 2025, "NONE", 0);
        assertState(calendar, LocalDate.of(2025, 12, 1), 1, "FROST", 2025, "ADVENT", 1);
        assertState(calendar, LocalDate.of(2026, 1, 1), 1, "WINTER", 2025, "NEW_YEAR", 0);
        assertState(calendar, LocalDate.of(2026, 2, 1), 1, "WINTER", 2025, "NONE", 0);
        assertState(calendar, LocalDate.of(2026, 2, 15), 0.5, "THAW", 2025, "NONE", 0);
        assertState(calendar, LocalDate.of(2026, 3, 1), 0, "THAW", 2025, "NONE", 0);
    }

    @Test
    void reportsOffOutsideSeasonAndStartsNewSeasonYear() {
        SeasonCalendar calendar = calendar();
        assertState(calendar, LocalDate.of(2025, 10, 31), 0, "OFF", 2024, "NONE", 0);
        assertState(calendar, LocalDate.of(2026, 11, 1), 0, "FROST", 2026, "NONE", 0);
    }

    @Test
    void leapDayDatesWorkEvenThoughLeapDayBoundariesAreRejected() {
        SeasonCalendar calendar = calendar();
        assertState(calendar, LocalDate.of(2024, 2, 29), 1.0 / 29.0, "THAW", 2023, "NONE", 0);
        assertThrows(IllegalArgumentException.class, () -> new SeasonCalendar(
                MonthDay.of(2, 29), MonthDay.of(3, 10), MonthDay.of(4, 10), MonthDay.of(5, 10)));
    }

    @Test
    void validatesStrictAnnualBoundaryOrder() {
        assertThrows(IllegalArgumentException.class, () -> new SeasonCalendar(
                MonthDay.of(11, 1), MonthDay.of(12, 1), MonthDay.of(11, 20), MonthDay.of(12, 20)));
        assertThrows(IllegalArgumentException.class, () -> new SeasonCalendar(
                MonthDay.of(11, 1), MonthDay.of(11, 1), MonthDay.of(2, 1), MonthDay.of(3, 1)));
    }

    @Test
    void assignsAdventWeeksAndChristmasWhenChristmasFallsOnSunday() {
        SeasonCalendar calendar = calendar();
        assertState(calendar, LocalDate.of(2022, 11, 27), 26.0 / 30.0, "FROST", 2022, "ADVENT", 1);
        assertState(calendar, LocalDate.of(2022, 12, 4), 1, "WINTER", 2022, "ADVENT", 2);
        assertState(calendar, LocalDate.of(2022, 12, 11), 1, "WINTER", 2022, "ADVENT", 3);
        assertState(calendar, LocalDate.of(2022, 12, 18), 1, "WINTER", 2022, "ADVENT", 4);
        assertState(calendar, LocalDate.of(2022, 12, 23), 1, "WINTER", 2022, "ADVENT", 4);
        assertState(calendar, LocalDate.of(2022, 12, 24), 1, "WINTER", 2022, "CHRISTMAS", 0);
        assertState(calendar, LocalDate.of(2022, 12, 25), 1, "WINTER", 2022, "CHRISTMAS", 0);
        assertState(calendar, LocalDate.of(2022, 12, 26), 1, "WINTER", 2022, "CHRISTMAS", 0);
        assertState(calendar, LocalDate.of(2022, 12, 31), 1, "WINTER", 2022, "NEW_YEAR", 0);
    }

    private static void assertState(SeasonCalendar calendar, LocalDate date, double intensity,
                                    String phase, int year, String holiday, int adventWeek) {
        SeasonCalendar.State state = calendar.at(date);
        assertEquals(intensity, state.intensity(), 1.0e-9, date.toString());
        assertEquals(phase, state.phase(), date.toString());
        assertEquals(year, state.seasonYear(), date.toString());
        assertEquals(holiday, state.holiday(), date.toString());
        assertEquals(adventWeek, state.adventWeek(), date.toString());
    }
}
