package at.vystorm.winter.season;

import java.time.LocalDate;
import java.time.MonthDay;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;

/** Computes the seasonal intensity and calendar holidays for a configured winter. */
public final class SeasonCalendar {
    private static final int COMMON_YEAR = 2001;
    private static final int DAYS_PER_YEAR = 365;

    private final MonthDay start;
    private final MonthDay peak;
    private final MonthDay thaw;
    private final MonthDay end;

    public SeasonCalendar(MonthDay start, MonthDay peak, MonthDay thaw, MonthDay end) {
        this.start = validateBoundary(start, "start");
        this.peak = validateBoundary(peak, "peak");
        this.thaw = validateBoundary(thaw, "thaw");
        this.end = validateBoundary(end, "end");

        long peakOffset = forwardDistance(this.start, this.peak);
        long thawOffset = forwardDistance(this.start, this.thaw);
        long endOffset = forwardDistance(this.start, this.end);
        if (!(peakOffset > 0 && peakOffset < thawOffset && thawOffset < endOffset
                && endOffset < DAYS_PER_YEAR)) {
            throw new IllegalArgumentException("Boundaries must be in forward order start, peak, thaw, end within one year");
        }
    }

    public State at(LocalDate date) {
        Objects.requireNonNull(date, "date");
        int seasonYear = date.getYear();
        if (monthDay(date).compareTo(start) < 0) {
            seasonYear--;
        }
        LocalDate seasonStart = start.atYear(seasonYear);
        LocalDate seasonPeak = boundaryDate(peak, seasonYear);
        LocalDate seasonThaw = boundaryDate(thaw, seasonYear);
        LocalDate seasonEnd = boundaryDate(end, seasonYear);
        long elapsed = ChronoUnit.DAYS.between(seasonStart, date);
        long peakDay = ChronoUnit.DAYS.between(seasonStart, seasonPeak);
        long thawDay = ChronoUnit.DAYS.between(seasonStart, seasonThaw);
        long endDay = ChronoUnit.DAYS.between(seasonStart, seasonEnd);

        String phase;
        double intensity;
        if (elapsed < 0 || elapsed > endDay) {
            phase = "OFF";
            intensity = 0.0;
        } else if (elapsed <= peakDay) {
            phase = "FROST";
            intensity = (double) elapsed / peakDay;
        } else if (elapsed <= thawDay) {
            phase = "WINTER";
            intensity = 1.0;
        } else {
            phase = "THAW";
            intensity = 1.0 - (double) (elapsed - thawDay) / (endDay - thawDay);
        }

        HolidayInfo holiday = holiday(date);
        return new State(intensity, phase, seasonYear, holiday.holiday, holiday.adventWeek);
    }

    /**
     * Start, peak, thaw and end dates of the season containing {@code date}, or of the next
     * season when {@code date} lies between two seasons.
     */
    public List<LocalDate> boundaries(LocalDate date) {
        Objects.requireNonNull(date, "date");
        int seasonYear = date.getYear();
        if (monthDay(date).compareTo(start) < 0) {
            seasonYear--;
        }
        if (date.isAfter(boundaryDate(end, seasonYear))) {
            seasonYear++;
        }
        return List.of(start.atYear(seasonYear), boundaryDate(peak, seasonYear),
                boundaryDate(thaw, seasonYear), boundaryDate(end, seasonYear));
    }

    /** Next upcoming peak, thaw or end inside the running season; empty outside a season. */
    public java.util.Optional<Milestone> nextMilestone(LocalDate date) {
        if (at(date).phase().equals("OFF")) return java.util.Optional.empty();
        List<LocalDate> dates = boundaries(date);
        String[] names = {"peak", "thaw", "end"};
        for (int i = 1; i < dates.size(); i++)
            if (dates.get(i).isAfter(date)) return java.util.Optional.of(new Milestone(names[i - 1], dates.get(i)));
        return java.util.Optional.empty();
    }

    public record Milestone(String name, LocalDate date) { }

    /** First day of the next season that has not started yet on {@code date}. */
    /** A date inside the winter at {@code progress} (0..1), for clocks that run winter faster than the real year. */
    public LocalDate winterDate(double progress) {
        LocalDate from = start.atYear(2001), to = end.atYear(2001);
        if (to.isBefore(from)) to = to.plusYears(1);
        return from.plusDays(Math.round(java.time.temporal.ChronoUnit.DAYS.between(from, to) * Math.clamp(progress, 0d, 1d)));
    }

    /** The next winter peak on or after {@code date}. */
    public LocalDate nextPeak(LocalDate date) {
        LocalDate candidate = peak.atYear(date.getYear());
        return candidate.isBefore(date) ? peak.atYear(date.getYear() + 1) : candidate;
    }

    public LocalDate nextStart(LocalDate date) {
        LocalDate first = boundaries(date).get(0);
        return first.isAfter(date) ? first : start.atYear(first.getYear() + 1);
    }

    private static MonthDay validateBoundary(MonthDay boundary, String name) {
        Objects.requireNonNull(boundary, name);
        if (boundary.getMonthValue() == 2 && boundary.getDayOfMonth() == 29) {
            throw new IllegalArgumentException(name + " boundary cannot be February 29");
        }
        return boundary;
    }

    private static long forwardDistance(MonthDay from, MonthDay to) {
        LocalDate fromDate = from.atYear(COMMON_YEAR);
        LocalDate toDate = to.atYear(COMMON_YEAR);
        long distance = ChronoUnit.DAYS.between(fromDate, toDate);
        return distance > 0 ? distance : distance + DAYS_PER_YEAR;
    }

    private static MonthDay monthDay(LocalDate date) {
        return MonthDay.from(date);
    }

    private LocalDate boundaryDate(MonthDay boundary, int seasonYear) {
        int year = boundary.compareTo(start) < 0 ? seasonYear + 1 : seasonYear;
        return boundary.atYear(year);
    }

    private static HolidayInfo holiday(LocalDate date) {
        int month = date.getMonthValue();
        int day = date.getDayOfMonth();
        if (month == 12 && day >= 24 && day <= 26) {
            return new HolidayInfo("CHRISTMAS", 0);
        }
        if ((month == 12 && day == 31) || (month == 1 && day == 1)) {
            return new HolidayInfo("NEW_YEAR", 0);
        }

        LocalDate christmas = LocalDate.of(date.getYear(), 12, 25);
        LocalDate fourthSunday = christmas.minusDays(christmas.getDayOfWeek().getValue());
        LocalDate firstSunday = fourthSunday.minusWeeks(3);
        if (!date.isBefore(firstSunday) && !date.isAfter(LocalDate.of(date.getYear(), 12, 23))) {
            int week = (int) (ChronoUnit.DAYS.between(firstSunday, date) / 7) + 1;
            return new HolidayInfo("ADVENT", Math.min(week, 4));
        }
        return new HolidayInfo("NONE", 0);
    }

    public record State(double intensity, String phase, int seasonYear, String holiday, int adventWeek) { }

    private record HolidayInfo(String holiday, int adventWeek) { }
}
