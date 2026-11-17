package at.vystorm.winter.season;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Locale;

/** Which season it is: the real calendar, or one season per month or week counted from a fixed start date. */
public record SeasonClock(boolean enabled, Mode mode, LocalDate anchor) {
    public enum Mode { REAL, MONTHLY, WEEKLY;
        public static Mode parse(String text) {
            try { return valueOf(String.valueOf(text).trim().toUpperCase(Locale.ROOT)); } catch (IllegalArgumentException e) { return REAL; }
        }
    }
    public enum Season { SPRING, SUMMER, AUTUMN, WINTER }
    public record Now(Season season, double progress) {}
    public static final LocalDate DEFAULT_ANCHOR = LocalDate.of(2026, 1, 5);
    private static final Season[] ORDER = Season.values();

    public static LocalDate anchor(String text) {
        try { return LocalDate.parse(String.valueOf(text).trim()); } catch (RuntimeException e) { return DEFAULT_ANCHOR; }
    }

    /** {@code winterNow}: the winter calendar is active on this date (only used by the real clock). */
    public Now at(LocalDate date, boolean winterNow) {
        return switch (mode) {
            case WEEKLY -> {
                long days = ChronoUnit.DAYS.between(anchor, date);
                yield new Now(ORDER[(int) Math.floorMod(Math.floorDiv(days, 7L), 4L)], Math.floorMod(days, 7L) / 7d);
            }
            case MONTHLY -> {
                long months = ChronoUnit.MONTHS.between(anchor.withDayOfMonth(1), date.withDayOfMonth(1));
                yield new Now(ORDER[(int) Math.floorMod(months, 4L)], (date.getDayOfMonth() - 1) / (double) date.lengthOfMonth());
            }
            case REAL -> {
                if (winterNow) yield new Now(Season.WINTER, 0.5);
                int month = date.getMonthValue();
                if (month < 3 || month == 12) yield new Now(Season.WINTER, 0.5);
                int first = month < 6 ? 3 : month < 9 ? 6 : 9;
                LocalDate start = LocalDate.of(date.getYear(), first, 1);
                yield new Now(first == 3 ? Season.SPRING : first == 6 ? Season.SUMMER : Season.AUTUMN,
                        Math.min(1, ChronoUnit.DAYS.between(start, date) / (double) ChronoUnit.DAYS.between(start, start.plusMonths(3))));
            }
        };
    }

    /** First day of the following season. */
    public LocalDate next(LocalDate date, boolean winterNow, LocalDate realWinterStart) {
        return switch (mode) {
            case WEEKLY -> date.plusDays(7 - Math.floorMod(ChronoUnit.DAYS.between(anchor, date), 7L));
            case MONTHLY -> date.withDayOfMonth(1).plusMonths(1);
            case REAL -> switch (at(date, winterNow).season()) {
                case WINTER -> { LocalDate march = LocalDate.of(date.getYear(), 3, 1); yield march.isAfter(date) ? march : march.plusYears(1); }
                case SPRING -> LocalDate.of(date.getYear(), 6, 1);
                case SUMMER -> LocalDate.of(date.getYear(), 9, 1);
                case AUTUMN -> realWinterStart;
            };
        };
    }
}
