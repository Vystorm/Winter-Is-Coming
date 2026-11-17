package at.vystorm.winter.season;

import static org.junit.jupiter.api.Assertions.*;
import java.time.LocalDate;
import java.time.MonthDay;
import org.junit.jupiter.api.Test;

class SeasonMilestoneTest {
    private final SeasonCalendar calendar=new SeasonCalendar(MonthDay.of(11,15),MonthDay.of(12,24),MonthDay.of(2,1),MonthDay.of(3,1));

    @Test void namesTheNextBoundaryInsideTheSeason() {
        assertEquals(new SeasonCalendar.Milestone("peak",LocalDate.of(2026,12,24)),calendar.nextMilestone(LocalDate.of(2026,11,20)).orElseThrow());
        assertEquals(new SeasonCalendar.Milestone("thaw",LocalDate.of(2027,2,1)),calendar.nextMilestone(LocalDate.of(2026,12,24)).orElseThrow());
        assertEquals(new SeasonCalendar.Milestone("end",LocalDate.of(2027,3,1)),calendar.nextMilestone(LocalDate.of(2027,2,10)).orElseThrow());
    }

    @Test void emptyOutsideTheSeasonAndOnItsLastDay() {
        assertTrue(calendar.nextMilestone(LocalDate.of(2026,10,28)).isEmpty());
        assertTrue(calendar.nextMilestone(LocalDate.of(2027,3,1)).isEmpty());
    }
}
