package at.vystorm.winter.season;

import java.time.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class HolidayRulesTest {
    @Test void newYearAndCrossYearChristmasKeepOneGiftIdentity(){
        MonthDay start=MonthDay.of(12,24),end=MonthDay.of(1,5);
        assertEquals(HolidayRules.giftId(LocalDate.of(2026,12,31),"NEW_YEAR",0,start,end),HolidayRules.giftId(LocalDate.of(2027,1,1),"NEW_YEAR",0,start,end));
        assertEquals(HolidayRules.giftId(LocalDate.of(2026,12,24),"CHRISTMAS",0,start,end),HolidayRules.giftId(LocalDate.of(2027,1,5),"CHRISTMAS",0,start,end));
        assertNotEquals(HolidayRules.giftId(LocalDate.of(2026,12,1),"ADVENT",1,start,end),HolidayRules.giftId(LocalDate.of(2026,12,8),"ADVENT",2,start,end));
    }
    @Test void midnightRequiresLiveForwardCrossingWithoutReplay(){
        var before=ZonedDateTime.of(2026,12,31,23,59,59,0,ZoneId.of("Europe/Vienna"));var after=before.plusSeconds(2);
        assertTrue(HolidayRules.crossedNewYear(before,after));
        assertFalse(HolidayRules.crossedNewYear(after,before));
        assertFalse(HolidayRules.crossedNewYear(null,after));
        assertFalse(HolidayRules.crossedNewYear(before,after.plusMinutes(1)));
        assertFalse(HolidayRules.crossedNewYear(after,after.plusSeconds(1)));
    }
}
